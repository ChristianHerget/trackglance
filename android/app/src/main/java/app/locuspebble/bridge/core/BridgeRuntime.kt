package app.locuspebble.bridge.core

import android.content.Context
import app.locuspebble.bridge.locus.LocusGateway
import app.locuspebble.bridge.pebble.PebbleMessages
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.DefaultPebbleSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BridgeRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val locus = LocusGateway(appContext)
    private val sender = DefaultPebbleSender(appContext)
    private var updateJob: Job? = null
    private val commandMutex = Mutex()
    private val commandResults = CommandResultCache()
    private val heartRateGate = HeartRateSampleGate()
    private val heartRateSamples = Channel<HeartRateSample>(Channel.CONFLATED)
    @Volatile private var transitioningUntil = 0L

    init {
        scope.launch {
            for (sample in heartRateSamples) forwardHeartRate(sample)
        }
    }

    fun handleHeartRate(sessionId: Long, sequence: Long, bpm: Int, sampledAtEpochSeconds: Long): Boolean {
        val now = System.currentTimeMillis() / 1000
        if (!heartRateGate.accept(sessionId, sequence, bpm, sampledAtEpochSeconds, now)) return false
        heartRateSamples.trySend(HeartRateSample(bpm))
        BridgeState.update { it.copy(lastWatchHeartRate = bpm) }
        return true
    }

    private suspend fun forwardHeartRate(sample: HeartRateSample) {
        val initial = withContext(Dispatchers.IO) { locus.readSnapshot() }
        if (initial.state != BridgeProtocol.RecordingState.RECORDING) return
        if (!withContext(Dispatchers.IO) { locus.sendHeartRate(sample.bpm) }) return
        val forwardedAt = System.currentTimeMillis()
        BridgeState.update { it.copy(lastHeartRateForwardedEpochMillis = forwardedAt) }
        val deadline = forwardedAt + 1_500
        var snapshot = initial
        do {
            delay(100)
            snapshot = withContext(Dispatchers.IO) { locus.readSnapshot() }
        } while (snapshot.currentHeartRate != sample.bpm && System.currentTimeMillis() < deadline)
        sender.sendDataToPebble(BridgeProtocol.APP_UUID, PebbleMessages.snapshot(snapshot))
        updateStatus(snapshot)
    }

    fun watchAppOpened() {
        BridgeState.update { it.copy(watchAppOpen = true, lastError = null) }
        if (updateJob?.isActive == true) return
        transitioningUntil = System.currentTimeMillis() + 15_000
        updateJob = scope.launch {
            while (isActive) {
                refresh()
                val policy = RefreshPolicy(Preferences.refreshMode(appContext))
                delay(policy.nextDelayMillis(System.currentTimeMillis() < transitioningUntil))
            }
        }
    }

    fun watchAppClosed() {
        updateJob?.cancel()
        updateJob = null
        BridgeState.update { it.copy(watchAppOpen = false) }
    }

    suspend fun handleCommand(
        sessionId: Long,
        commandId: Long,
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
    ) {
        val result = commandMutex.withLock {
            commandResults.get(sessionId, commandId) ?: run {
                transitioningUntil = System.currentTimeMillis() + 15_000
                withContext(Dispatchers.IO) { locus.execute(command, profileName, waypointName) }
                    .also { commandResults.put(sessionId, commandId, it) }
            }
        }
        sender.sendDataToPebble(
            BridgeProtocol.APP_UUID,
            PebbleMessages.result(sessionId, commandId, result),
        )
        refresh()
    }

    suspend fun sendRecordingProfiles() {
        val names = withContext(Dispatchers.IO) { locus.recordingProfiles() }
        BridgeState.update {
            it.copy(
                locusProfiles = names,
                lastProfileRequestEpochMillis = System.currentTimeMillis(),
                lastError = if (names.isEmpty()) "Locus returned no recording profiles" else null,
            )
        }
        PebbleMessages.profileListChunks(names, (System.currentTimeMillis() and 0x7fffffff).toInt())
            .forEach { sender.sendDataToPebble(BridgeProtocol.APP_UUID, it) }
    }

    suspend fun refresh() {
        try {
            val snapshot = withContext(Dispatchers.IO) { locus.readSnapshot() }
            sender.sendDataToPebble(BridgeProtocol.APP_UUID, PebbleMessages.snapshot(snapshot))
            updateStatus(snapshot)
        } catch (error: Exception) {
            BridgeState.update { it.copy(lastError = error.message ?: error.javaClass.simpleName) }
        }
    }

    private fun updateStatus(snapshot: BridgeProtocol.Snapshot) = BridgeState.update {
        it.copy(
            locusAvailable = snapshot.state != BridgeProtocol.RecordingState.UNAVAILABLE,
            recordingState = snapshot.state,
            lastUpdateEpochMillis = System.currentTimeMillis(),
            currentLocusHeartRate = snapshot.currentHeartRate,
            lastError = null,
        )
    }

    fun close() {
        scope.cancel()
        sender.close()
    }

    companion object {
        @Volatile private var instance: BridgeRuntime? = null
        fun get(context: Context): BridgeRuntime = instance ?: synchronized(this) {
            instance ?: BridgeRuntime(context).also { instance = it }
        }
    }
}

private data class HeartRateSample(val bpm: Int)

object Preferences {
    private const val FILE = "bridge_preferences"
    private const val KEY_REFRESH_MODE = "refresh_mode"

    fun refreshMode(context: Context): RefreshMode {
        val name = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_REFRESH_MODE, RefreshMode.ADAPTIVE.name)
        return runCatching { RefreshMode.valueOf(name!!) }.getOrDefault(RefreshMode.ADAPTIVE)
    }

    fun setRefreshMode(context: Context, mode: RefreshMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_REFRESH_MODE, mode.name).apply()
    }
}
