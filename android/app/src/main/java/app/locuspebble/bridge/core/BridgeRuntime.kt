package app.locuspebble.bridge.core

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import app.locuspebble.bridge.locus.CommandExecution
import app.locuspebble.bridge.locus.LocusBridgeGateway
import app.locuspebble.bridge.locus.LocusGateway
import app.locuspebble.bridge.locus.RecordingProfilesResult
import app.locuspebble.bridge.pebble.ActiveWatchSlot
import app.locuspebble.bridge.pebble.DefaultPebbleDictionarySender
import app.locuspebble.bridge.pebble.PebbleMessages
import app.locuspebble.bridge.pebble.ReliablePebbleTransport
import app.locuspebble.bridge.pebble.TrustAdmission
import app.locuspebble.bridge.pebble.TrustLeaseResult
import app.locuspebble.bridge.pebble.TrustedPebbleCompanionProvider
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val snapshotEpochStore: SnapshotDeliveryEpochStore,
    private val profileTransferSerialStore: ProfileTransferSerialStore,
    private val refreshMode: () -> RefreshMode,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val trustedMutationGate: suspend (
        TrustAdmission,
        suspend () -> Unit,
    ) -> TrustLeaseResult<Unit> = { _, block -> TrustLeaseResult.Admitted(block()) },
    private val trustedPublicationGate: (suspend (
        TrustAdmission,
        suspend () -> Unit,
    ) -> TrustLeaseResult<Unit>)? = null,
    private val admissionCurrent: (TrustAdmission) -> Boolean = { true },
) : AutoCloseable {
    private val activeWatches = ActiveWatchSlot<AdmittedWatch>()
    private val lifecycleLock = Any()
    private val childJobs = mutableSetOf<Job>()
    private val commandMutex = Mutex()
    private val profileTransferMutex = Mutex()
    private val heartRateGate = HeartRateSampleGate()
    private val heartRateSamples = Channel<HeartRateSample>(Channel.CONFLATED)
    private val snapshotDelivery = SerializedSnapshotDelivery<AdmittedWatch, BridgeProtocol.Snapshot>(
        read = ::readSnapshotForDelivery,
        updateStatus = { snapshot, targets ->
            targets.singleAdmissionOrNull()?.let { admission ->
                publishIfCurrent(admission) { updateStatus(snapshot) }
            }
        },
        send = { snapshot, targets ->
            sendToAdmittedTargets(PebbleMessages.snapshot(snapshot), targets)
        },
    )
    private var updateJob: Job? = null
    private var heartRateJob: Job? = null
    
    @Volatile private var transitioningUntil = 0L

    init {
        
        synchronized(lifecycleLock) { ensureHeartRateConsumerLocked() }
    }

    fun handleHeartRate(
        watch: WatchIdentifier,
        sessionId: Long,
        sequence: Long,
        bpm: Int,
        sampledAtEpochSeconds: Long,
        admission: TrustAdmission,
    ): Boolean {
        if (!admissionCurrent(admission)) return false
        val accepted = heartRateGate.accept(
            watchId = watch.value,
            session = sessionId,
            sequence = sequence,
            bpm = bpm,
            sampledAt = sampledAtEpochSeconds,
            now = wallMillis() / 1000,
            trustGeneration = admission.generation,
        )
        if (!accepted) return false
        val sample = HeartRateSample(watch, bpm, admission)
        if (heartRateSamples.trySend(sample).isFailure) return false
        return true
    }

    private suspend fun forwardHeartRate(sample: HeartRateSample) {
        val source = AdmittedWatch(sample.watch, sample.admission)
        if (activeWatches.snapshot().singleOrNull()?.let { it != source } == true) return
        if (!publishIfCurrent(sample.admission) {
                BridgeState.update { it.copy(lastWatchHeartRate = sample.bpm) }
            }
        ) return
        val initial = readSnapshot(sample.admission)
        if (initial.state != BridgeProtocol.RecordingState.RECORDING) return
        if (activeWatches.snapshot().singleOrNull()?.let { it != source } == true) return
        var forwarded = false
        val admitted = trustedMutationGate(sample.admission) {
            forwarded = withContext(ioDispatcher) { locus.sendHeartRate(sample.bpm) }
        }
        if (admitted !is TrustLeaseResult.Admitted || !forwarded) return
        if (!admissionCurrent(sample.admission)) return

        if (!publishIfCurrent(sample.admission) {
                BridgeState.update { it.copy(lastHeartRateForwardedEpochMillis = wallMillis()) }
            }
        ) return
        val deadline = monotonicMillis() + HEART_RATE_CONFIRMATION_MILLIS
        var snapshot = initial
        do {
            delayMillis(HEART_RATE_POLL_MILLIS)
            snapshot = readSnapshot(sample.admission)
        } while (snapshot.currentHeartRate != sample.bpm && monotonicMillis() < deadline)

        deliverSnapshot(
            targets = listOf(AdmittedWatch(sample.watch, sample.admission)),
            failureMessage = "Could not deliver the Locus update to the source watch",
        )
    }

    fun watchAppOpened(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): Boolean = observeWatch(AdmittedWatch(watch, admission), markTransition = true)

    /** Recovers active-watch state when the process restarted while the watchapp stayed open. */
    fun watchObserved(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): Boolean = observeWatch(AdmittedWatch(watch, admission), markTransition = false)

    private fun observeWatch(target: AdmittedWatch, markTransition: Boolean): Boolean {
        synchronized(lifecycleLock) {
            ensureHeartRateConsumerLocked()
            val newlyOpened = if (markTransition) {
                activeWatches.opened(target)
            } else {
                if (!activeWatches.observed(target)) return false
                false
            }
            BridgeState.update {
                it.copy(watchAppOpen = true, lastError = if (markTransition) null else it.lastError)
            }
            if (newlyOpened || markTransition) {
                transitioningUntil = monotonicMillis() + TRANSITION_REFRESH_MILLIS
            }
            if (updateJob?.isActive == true) {
                if (newlyOpened) launchTracked { refreshTargets(listOf(target)) }
                return true
            }
            updateJob = launchTracked {
                while (isActive) {
                    var admission: TrustAdmission? = null
                    try {
                        val targets = activeWatches.snapshot()
                        if (targets.isEmpty()) return@launchTracked
                        admission = targets.singleAdmissionOrNull()
                        refreshTargets(targets)
                        val transitioning = monotonicMillis() < transitioningUntil
                        delayMillis(RefreshPolicy(refreshMode()).nextDelayMillis(transitioning))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        admission?.let { current ->
                            publishIfCurrent(current) { reportError(error) }
                        }
                        delayMillis(POLL_FAILURE_DELAY_MILLIS)
                    }
                }
            }
            return true
        }
    }

    fun watchAppClosed(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ) {
        synchronized(lifecycleLock) {
            activeWatches.closed(AdmittedWatch(watch, admission))
            if (activeWatches.isEmpty()) {
                updateJob?.cancel()
                updateJob = null
                BridgeState.update { it.copy(watchAppOpen = false) }
            }
        }
    }

    internal fun companionTrustLost() {
        while (heartRateSamples.tryReceive().isSuccess) {
            // Drain samples admitted under the previous companion identity.
        }
        synchronized(lifecycleLock) {
            activeWatches.clear()
            childJobs.toList().forEach { it.cancel() }
            childJobs.clear()
            updateJob = null
            heartRateJob = null
            BridgeState.update { it.withPebbleSelection(null) }
        }
    }

    suspend fun handleCommand(
        watch: WatchIdentifier,
        sessionId: Long,
        commandId: Long,
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
        admission: TrustAdmission,
    ): Boolean {
        val key = CommandJournal.Key(watch.value, sessionId, commandId)
        val fingerprint = CommandJournal.fingerprint(
            command,
            profileName.takeIf { command == BridgeProtocol.Command.START },
            waypointName.takeIf { command == BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE },
        )
        val target = AdmittedWatch(watch, admission)
        val delivered = snapshotDelivery.reserveThenMutateAndDeliverSnapshot(
            targets = listOf(target),
            reserve = { reserveSnapshotEpoch(wallMillis() / 1_000L, admission) },
            mutate = {
                commandMutex.withLock {
                    val begun = withContext(ioDispatcher) { commandJournal.begin(key, fingerprint) }
                    
                    when (begun) {
                        is CommandJournal.BeginResult.Completed -> CommandMutation(begun.result)
                        is CommandJournal.BeginResult.Execute -> {
                            transitioningUntil = monotonicMillis() + TRANSITION_REFRESH_MILLIS
                            val executed = executeUnderAdmission(
                                admission = admission,
                                command = command,
                                profileName = profileName,
                                waypointName = waypointName,
                            )
                            val confirmed = executed?.let { confirmExecution(admission, command, it) }
                                ?: CommandMutation(BridgeProtocol.Result.FAILED)
                            val completed = withContext(ioDispatcher) {
                                commandJournal.complete(begun.key, confirmed.result)
                            }
                            
                            if (completed) {
                                confirmed
                            } else {
                                confirmed.copy(result = BridgeProtocol.Result.FAILED)
                            }
                        }
                        CommandJournal.BeginResult.Collision,
                        CommandJournal.BeginResult.Pending,
                        
                        -> CommandMutation(BridgeProtocol.Result.FAILED)
                    }
                }
            },
            readAfterMutation = { epoch, mutation ->
                (mutation.latestSnapshot ?: readSnapshot(admission)).copy(sampledAtEpochSeconds = epoch)
            },
            finish = { mutation, snapshotDelivered ->
                snapshotDelivered && isActiveOrUntracked(target) && transport.send(
                    PebbleMessages.result(sessionId, commandId, mutation.result),
                    watch,
                    admission,
                )
            },
            reservationFailed = { false },
        )
        if (!delivered) publishIfCurrent(admission) {
            reportError(
                "Could not deliver the command result to the source watch",
            )
        }
        return delivered
    }

    /** The lease covers only Locus' exact state/profile lookup plus routed action transaction. */
    private suspend fun executeUnderAdmission(
        admission: TrustAdmission,
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
    ): CommandExecution? {
        var executed: CommandExecution? = null
        return try {
            when (trustedMutationGate(admission) {
                executed = withContext(ioDispatcher) {
                    locus.executeWithExpectedState(command, profileName, waypointName)
                }
            }) {
                is TrustLeaseResult.Admitted -> executed
                TrustLeaseResult.Stale,
                TrustLeaseResult.Untrusted,
                -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    /** Confirmation polls run outside the revocation lease and retain the ingress admission. */
    private suspend fun confirmExecution(
        admission: TrustAdmission,
        command: BridgeProtocol.Command,
        executed: CommandExecution,
    ): CommandMutation {
        if (executed.result != BridgeProtocol.Result.OK) return CommandMutation(executed.result)
        val expectedState = executed.expectedState ?: when (command) {
            BridgeProtocol.Command.ADD_WAYPOINT,
            BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE,
            -> return CommandMutation(executed.result)
            else -> return CommandMutation(BridgeProtocol.Result.FAILED)
        }
        val deadline = monotonicMillis() + BridgeProtocol.COMMAND_CONFIRMATION_MILLIS
        var pollsRemaining = COMMAND_CONFIRMATION_MAX_POLLS
        var latest = readSnapshot(admission)
        while (
            latest.state != expectedState &&
            pollsRemaining-- > 0 &&
            monotonicMillis() < deadline
        ) {
            delayMillis(COMMAND_CONFIRMATION_POLL_MILLIS)
            latest = readSnapshot(admission)
        }
        return CommandMutation(
            result = if (latest.state == expectedState) {
                BridgeProtocol.Result.OK
            } else {
                BridgeProtocol.Result.FAILED
            },
            latestSnapshot = latest,
        )
    }

    suspend fun sendRecordingProfiles(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): Boolean = profileTransferMutex.withLock {
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
                publishIfCurrent(admission) {
                    BridgeState.update {
                        it.copy(
                            lastProfileRequestEpochMillis = wallMillis(),
                            lastError = query.message,
                        )
                    }
                }
                return@withLock false
            }
        }
        val transfer = BridgeProtocol.profileTransfer(names)
        if (transfer == null) {
            publishIfCurrent(admission) {
                BridgeState.update {
                    it.copy(
                        lastProfileRequestEpochMillis = wallMillis(),
                        lastError = "Locus profile list is invalid or exceeds the watch transfer limit",
                    )
                }
            }
            return@withLock false
        }
        if (!publishIfCurrent(admission) {
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
            }
        ) return@withLock false
        val transferId = reserveProfileTransferId()
        val messages = PebbleMessages.profileListChunks(transfer, transferId)
        messages.forEach { message ->
            if (!isActiveOrUntracked(AdmittedWatch(watch, admission)) ||
                !transport.send(message, watch, admission)
            ) {
                publishIfCurrent(admission) {
                    reportError("Could not deliver a complete profile list to the source watch")
                }
                return@withLock false
            }
        }
        true
    }

    suspend fun refresh(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): Boolean {
        return refreshTargets(listOf(AdmittedWatch(watch, admission)))
    }

    private suspend fun refreshTargets(targets: Collection<AdmittedWatch>): Boolean =
        deliverSnapshot(targets, "Could not deliver the Locus snapshot")

    private suspend fun deliverSnapshot(
        targets: Collection<AdmittedWatch>,
        failureMessage: String,
    ): Boolean {
        val delivered = snapshotDelivery.deliver(targets)
        val admission = targets.singleAdmissionOrNull()
        if (!delivered && admission != null) {
            publishIfCurrent(admission) {
                reportError(failureMessage)
            }
        }
        return delivered
    }

    private suspend fun readSnapshot(
        admission: TrustAdmission? = null,
    ): BridgeProtocol.Snapshot = try {
        val now = wallMillis()
        withContext(ioDispatcher) { locus.readSnapshot(now) }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (admission == null) {
            reportError(error)
        } else {
            publishIfCurrent(admission) { reportError(error) }
        }
        BridgeProtocol.Snapshot(
            state = BridgeProtocol.RecordingState.UNAVAILABLE,
            sampledAtEpochSeconds = wallMillis() / 1000,
        )
    }

    private suspend fun readSnapshotForDelivery(
        targets: Collection<AdmittedWatch>,
    ): BridgeProtocol.Snapshot? {
        val admission = targets.singleAdmissionOrNull() ?: return null
        val snapshot = readSnapshot(admission)
        val next = reserveSnapshotEpoch(snapshot.sampledAtEpochSeconds, admission)
        return snapshot.copy(sampledAtEpochSeconds = next)
    }

    private suspend fun reserveSnapshotEpoch(
        observedEpochSeconds: Long,
        admission: TrustAdmission? = null,
    ): Long {
        return snapshotEpochStore.reserve(observedEpochSeconds)
    }

    private suspend fun reserveProfileTransferId(): Int {
        // Profile and snapshot ordering use independent stores. Never overwrite the snapshot
        // failure latch here: a concurrent profile reservation must not hide the actionable reason
        // that a command barrier or ordinary snapshot failed closed.
        return profileTransferSerialStore.reserve()
    }

    private suspend fun sendToAdmittedTargets(
        dictionary: io.rebble.pebblekit2.common.model.PebbleDictionary,
        targets: Collection<AdmittedWatch>,
    ): Boolean {
        val admission = targets.singleAdmissionOrNull() ?: return false
        val target = targets.singleOrNull() ?: return false
        if (!isActiveOrUntracked(target)) return false
        val watch = target.watch
        return transport.send(dictionary, watch, admission)
    }

    private fun isActiveOrUntracked(target: AdmittedWatch): Boolean =
        activeWatches.snapshot().singleOrNull()?.let { it == target } != false

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

    private suspend fun publishIfCurrent(
        admission: TrustAdmission,
        block: suspend () -> Unit,
    ): Boolean {
        val gate = trustedPublicationGate
        if (gate == null) {
            if (!admissionCurrent(admission)) return false
            block()
            return true
        }
        return gate(admission, block) is TrustLeaseResult.Admitted
    }

    private fun reportError(error: Throwable) = reportError(
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
    )

    private fun reportError(message: String) = BridgeState.update { it.copy(lastError = message) }


    private fun ensureHeartRateConsumerLocked() {
        if (heartRateJob?.isActive == true) return
        heartRateJob = launchTracked {
            for (sample in heartRateSamples) {
                try {
                    forwardHeartRate(sample)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    publishIfCurrent(sample.admission) { reportError(error) }
                }
            }
        }
    }

    private fun launchTracked(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        synchronized(lifecycleLock) { childJobs += job }
        job.invokeOnCompletion { synchronized(lifecycleLock) { childJobs -= job } }
        job.start()
        return job
    }

    override fun close() {
        synchronized(lifecycleLock) {
            childJobs.toList().forEach { it.cancel() }
            childJobs.clear()
            updateJob = null
            heartRateJob = null
        }
        heartRateSamples.close()
        scope.cancel()
        transport.close()
    }

    companion object {
        private const val TRANSITION_REFRESH_MILLIS = 15_000L
        private const val HEART_RATE_CONFIRMATION_MILLIS = 1_500L
        private const val HEART_RATE_POLL_MILLIS = 100L
        private const val COMMAND_CONFIRMATION_POLL_MILLIS = 100L
        private const val COMMAND_CONFIRMATION_MAX_POLLS = 15
        private const val POLL_FAILURE_DELAY_MILLIS = 1_000L
                
        @Volatile private var instance: BridgeRuntime? = null

        fun get(context: Context): BridgeRuntime = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        internal fun resetForCompanionTrustLoss() {
            val current = instance
            if (current == null) {
                BridgeState.update { it.withPebbleSelection(null) }
            } else {
                current.companionTrustLost()
            }
        }

        private fun create(context: Context): BridgeRuntime = BridgeRuntime(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            locus = LocusGateway(context),
            transport = ReliablePebbleTransport(DefaultPebbleDictionarySender(context)),
            commandJournal = CommandJournal(),
            snapshotEpochStore = SnapshotDeliveryEpochStore(),
            profileTransferSerialStore = ProfileTransferSerialStore(),
            refreshMode = { Preferences.refreshMode(context) },
            trustedMutationGate = { admission, block ->
                TrustedPebbleCompanionProvider.withInboundAdmission(context, admission, block)
            },
            trustedPublicationGate = { admission, block ->
                TrustedPebbleCompanionProvider.withOutboundAdmission(context, admission, block)
            },
            admissionCurrent = TrustedPebbleCompanionProvider::isAdmissionCurrent,
        )
    }
}

private data class HeartRateSample(
    val watch: WatchIdentifier,
    val bpm: Int,
    val admission: TrustAdmission,
)

private data class AdmittedWatch(
    val watch: WatchIdentifier,
    val admission: TrustAdmission,
)

private fun Collection<AdmittedWatch>.singleAdmissionOrNull(): TrustAdmission? =
    map(AdmittedWatch::admission).distinct().singleOrNull()

private data class CommandMutation(
    val result: BridgeProtocol.Result,
    val latestSnapshot: BridgeProtocol.Snapshot? = null,
)

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
