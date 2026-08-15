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
    @Volatile private var transitioningUntil = 0L

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

    suspend fun handleCommand(sessionId: Long, commandId: Long, command: BridgeProtocol.Command, profileName: String?) {
        val result = commandMutex.withLock {
            commandResults.get(sessionId, commandId) ?: run {
                transitioningUntil = System.currentTimeMillis() + 15_000
                withContext(Dispatchers.IO) { locus.execute(command, profileName) }
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
        PebbleMessages.profileListChunks(names, (System.currentTimeMillis() and 0x7fffffff).toInt())
            .forEach { sender.sendDataToPebble(BridgeProtocol.APP_UUID, it) }
    }

    suspend fun refresh() {
        try {
            val snapshot = withContext(Dispatchers.IO) { locus.readSnapshot() }
            sender.sendDataToPebble(BridgeProtocol.APP_UUID, PebbleMessages.snapshot(snapshot))
            BridgeState.update {
                it.copy(
                    locusAvailable = snapshot.state != BridgeProtocol.RecordingState.UNAVAILABLE,
                    recordingState = snapshot.state,
                    lastUpdateEpochMillis = System.currentTimeMillis(),
                    lastError = null,
                )
            }
        } catch (error: Exception) {
            BridgeState.update { it.copy(lastError = error.message ?: error.javaClass.simpleName) }
        }
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
