package app.locuspebble.bridge.core

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import app.locuspebble.bridge.locus.LocusBridgeGateway
import app.locuspebble.bridge.locus.LocusGateway
import app.locuspebble.bridge.locus.RecordingProfilesResult
import app.locuspebble.bridge.pebble.ActiveWatchRegistry
import app.locuspebble.bridge.pebble.DefaultPebbleDictionarySender
import app.locuspebble.bridge.pebble.PebbleMessages
import app.locuspebble.bridge.pebble.ReliablePebbleTransport
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BridgeRuntime internal constructor(
    private val scope: CoroutineScope,
    private val locus: LocusBridgeGateway,
    private val transport: ReliablePebbleTransport,
    private val commandJournal: CommandJournal,
    private val refreshMode: () -> RefreshMode,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    initialTransferId: Int = 0,
) : AutoCloseable {
    private val activeWatches = ActiveWatchRegistry<WatchIdentifier>()
    private val lifecycleLock = Any()
    private val commandMutex = Mutex()
    private val profileTransferMutex = Mutex()
    private val heartRateGate = HeartRateSampleGate()
    private val heartRateSamples = Channel<HeartRateSample>(Channel.CONFLATED)
    private val transferIds = AtomicInteger(initialTransferId.coerceAtLeast(0))
    private val snapshotDelivery = SerializedSnapshotDelivery<WatchIdentifier, BridgeProtocol.Snapshot>(
        read = ::readSnapshot,
        updateStatus = ::updateStatus,
        send = { snapshot, watches ->
            transport.send(PebbleMessages.snapshot(snapshot), watches)
        },
    )
    private var updateJob: Job? = null

    @Volatile private var transitioningUntil = 0L

    init {
        scope.launch {
            for (sample in heartRateSamples) {
                try {
                    forwardHeartRate(sample)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    reportError(error)
                }
            }
        }
    }

    fun handleHeartRate(
        watch: WatchIdentifier,
        sessionId: Long,
        sequence: Long,
        bpm: Int,
        sampledAtEpochSeconds: Long,
    ): Boolean {
        val accepted = heartRateGate.accept(
            watchId = watch.value,
            session = sessionId,
            sequence = sequence,
            bpm = bpm,
            sampledAt = sampledAtEpochSeconds,
            now = wallMillis() / 1000,
        )
        if (!accepted) return false
        if (heartRateSamples.trySend(HeartRateSample(watch, bpm)).isFailure) return false
        BridgeState.update { it.copy(lastWatchHeartRate = bpm) }
        return true
    }

    private suspend fun forwardHeartRate(sample: HeartRateSample) {
        val initial = readSnapshot()
        if (initial.state != BridgeProtocol.RecordingState.RECORDING) return
        if (!withContext(ioDispatcher) { locus.sendHeartRate(sample.bpm) }) return

        BridgeState.update { it.copy(lastHeartRateForwardedEpochMillis = wallMillis()) }
        val deadline = monotonicMillis() + HEART_RATE_CONFIRMATION_MILLIS
        var snapshot = initial
        do {
            delayMillis(HEART_RATE_POLL_MILLIS)
            snapshot = readSnapshot()
        } while (snapshot.currentHeartRate != sample.bpm && monotonicMillis() < deadline)

        deliverSnapshot(
            watches = listOf(sample.watch),
            failureMessage = "Could not deliver the Locus update to the source watch",
        )
    }

    fun watchAppOpened(watch: WatchIdentifier) = observeWatch(watch, markTransition = true)

    /** Recovers active-watch state when the process restarted while the watchapp stayed open. */
    fun watchObserved(watch: WatchIdentifier) = observeWatch(watch, markTransition = false)

    private fun observeWatch(watch: WatchIdentifier, markTransition: Boolean) {
        synchronized(lifecycleLock) {
            val newlyOpened = activeWatches.opened(watch)
            BridgeState.update {
                it.copy(watchAppOpen = true, lastError = if (markTransition) null else it.lastError)
            }
            if (newlyOpened || markTransition) {
                transitioningUntil = monotonicMillis() + TRANSITION_REFRESH_MILLIS
            }
            if (updateJob?.isActive == true) {
                if (newlyOpened) scope.launch { refresh(listOf(watch)) }
                return
            }
            updateJob = scope.launch {
                while (isActive) {
                    try {
                        val targets = activeWatches.snapshot()
                        if (targets.isEmpty()) return@launch
                        refresh(targets)
                        val transitioning = monotonicMillis() < transitioningUntil
                        delayMillis(RefreshPolicy(refreshMode()).nextDelayMillis(transitioning))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        reportError(error)
                        delayMillis(POLL_FAILURE_DELAY_MILLIS)
                    }
                }
            }
        }
    }

    fun watchAppClosed(watch: WatchIdentifier) {
        synchronized(lifecycleLock) {
            activeWatches.closed(watch)
            if (activeWatches.isEmpty()) {
                updateJob?.cancel()
                updateJob = null
                BridgeState.update { it.copy(watchAppOpen = false) }
            }
        }
    }

    suspend fun handleCommand(
        watch: WatchIdentifier,
        sessionId: Long,
        commandId: Long,
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
    ): Boolean {
        val key = CommandJournal.Key(watch.value, sessionId, commandId)
        val fingerprint = CommandJournal.fingerprint(
            command,
            profileName.takeIf { command == BridgeProtocol.Command.START },
            waypointName.takeIf { command == BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE },
        )
        val result = commandMutex.withLock {
            withContext(ioDispatcher) {
                when (val begun = commandJournal.begin(key, fingerprint)) {
                    is CommandJournal.BeginResult.Completed -> begun.result
                    is CommandJournal.BeginResult.Execute -> {
                        transitioningUntil = monotonicMillis() + TRANSITION_REFRESH_MILLIS
                        val executed = try {
                            locus.execute(command, profileName, waypointName)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            BridgeProtocol.Result.FAILED
                        }
                        if (commandJournal.complete(begun.key, executed)) {
                            executed
                        } else {
                            BridgeProtocol.Result.FAILED
                        }
                    }
                    CommandJournal.BeginResult.Collision,
                    CommandJournal.BeginResult.Pending,
                    CommandJournal.BeginResult.StorageFailure,
                    -> BridgeProtocol.Result.FAILED
                }
            }
        }
        val delivered = transport.send(
            PebbleMessages.result(sessionId, commandId, result),
            listOf(watch),
        )
        refresh(listOf(watch))
        if (!delivered) reportError("Could not deliver the command result to the source watch")
        return delivered
    }

    suspend fun sendRecordingProfiles(watch: WatchIdentifier): Boolean = profileTransferMutex.withLock {
        val query = try {
            withContext(ioDispatcher) { locus.recordingProfiles() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            RecordingProfilesResult.Failure(
                error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName,
            )
        }
        val names = when (query) {
            is RecordingProfilesResult.Success -> query.names
            is RecordingProfilesResult.Failure -> {
                BridgeState.update {
                    it.copy(
                        lastProfileRequestEpochMillis = wallMillis(),
                        lastError = query.message,
                    )
                }
                return@withLock false
            }
        }
        val transfer = BridgeProtocol.profileTransfer(names)
        if (transfer == null) {
            BridgeState.update {
                it.copy(
                    lastProfileRequestEpochMillis = wallMillis(),
                    lastError = "Locus profile list is invalid or exceeds the watch transfer limit",
                )
            }
            return@withLock false
        }
        BridgeState.update {
            it.copy(
                locusProfiles = names,
                lastProfileRequestEpochMillis = wallMillis(),
                lastError = when {
                    names.isEmpty() -> "Locus returned no recording profiles"
                    else -> null
                },
            )
        }
        val messages = PebbleMessages.profileListChunks(transfer, nextTransferId())
        messages.forEach { message ->
            if (!transport.send(message, listOf(watch))) {
                reportError("Could not deliver a complete profile list to the source watch")
                return@withLock false
            }
        }
        true
    }

    suspend fun refresh(watches: Collection<WatchIdentifier>): Boolean {
        return deliverSnapshot(watches, "Could not deliver the Locus snapshot")
    }

    private suspend fun deliverSnapshot(
        watches: Collection<WatchIdentifier>,
        failureMessage: String,
    ): Boolean {
        val delivered = snapshotDelivery.deliver(watches)
        if (!delivered) reportError(failureMessage)
        return delivered
    }

    private suspend fun readSnapshot(): BridgeProtocol.Snapshot = try {
        val now = wallMillis()
        withContext(ioDispatcher) { locus.readSnapshot(now) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        reportError(error)
        BridgeProtocol.Snapshot(
            state = BridgeProtocol.RecordingState.UNAVAILABLE,
            sampledAtEpochSeconds = wallMillis() / 1000,
        )
    }

    private fun updateStatus(snapshot: BridgeProtocol.Snapshot) = BridgeState.update {
        it.copy(
            locusAvailable = snapshot.state != BridgeProtocol.RecordingState.UNAVAILABLE,
            recordingState = snapshot.state,
            lastUpdateEpochMillis = wallMillis(),
            currentLocusHeartRate = snapshot.currentHeartRate,
            lastError = if (snapshot.state == BridgeProtocol.RecordingState.UNAVAILABLE) {
                "Locus is unavailable"
            } else {
                null
            },
        )
    }

    private fun reportError(error: Throwable) = reportError(
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
    )

    private fun reportError(message: String) = BridgeState.update { it.copy(lastError = message) }

    private fun nextTransferId(): Int = transferIds.getAndUpdate { current ->
        if (current == Int.MAX_VALUE) 0 else current + 1
    }

    override fun close() {
        synchronized(lifecycleLock) {
            updateJob?.cancel()
            updateJob = null
        }
        heartRateSamples.close()
        scope.cancel()
        transport.close()
    }

    companion object {
        private const val TRANSITION_REFRESH_MILLIS = 15_000L
        private const val HEART_RATE_CONFIRMATION_MILLIS = 1_500L
        private const val HEART_RATE_POLL_MILLIS = 100L
        private const val POLL_FAILURE_DELAY_MILLIS = 1_000L

        @Volatile private var instance: BridgeRuntime? = null

        fun get(context: Context): BridgeRuntime = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): BridgeRuntime = BridgeRuntime(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            locus = LocusGateway(context),
            transport = ReliablePebbleTransport(DefaultPebbleDictionarySender(context)),
            commandJournal = CommandJournal(SharedPreferencesCommandJournalStorage(context)),
            refreshMode = { Preferences.refreshMode(context) },
            initialTransferId = (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}

private data class HeartRateSample(val watch: WatchIdentifier, val bpm: Int)

object Preferences {
    private const val FILE = "bridge_preferences"
    private const val KEY_REFRESH_MODE = "refresh_mode"

    fun refreshMode(context: Context): RefreshMode {
        return runCatching {
            val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY_REFRESH_MODE, RefreshMode.ADAPTIVE.name)
            RefreshMode.valueOf(name ?: RefreshMode.ADAPTIVE.name)
        }.getOrDefault(RefreshMode.ADAPTIVE)
    }

    fun setRefreshMode(context: Context, mode: RefreshMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_REFRESH_MODE, mode.name)
        }
    }
}
