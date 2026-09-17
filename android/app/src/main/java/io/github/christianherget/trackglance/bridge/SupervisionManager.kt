package io.github.christianherget.trackglance.bridge

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import io.github.christianherget.trackglance.bridge.core.BridgeState
import io.github.christianherget.trackglance.bridge.core.SupervisionMode
import io.github.christianherget.trackglance.bridge.core.SupervisionRecording
import io.github.christianherget.trackglance.bridge.core.SupervisionSettings
import io.github.christianherget.trackglance.bridge.core.SupervisionSource
import io.github.christianherget.trackglance.bridge.core.WatchappSupervisor
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface SupervisionEvents {
    fun learningToken(): Long? = 0L

    fun observationToken(): Long = 0L

    fun observed(
        snapshot: BridgeProtocol.Snapshot,
        source: SupervisionSource? = null,
        token: Long? = null,
        observationToken: Long? = null,
    )

    fun opened()

    fun closed()
}

/** All supervisor mutations and effects run on the main dispatcher. No Activity is retained. */
class SupervisionManager internal constructor(context: Context) : SupervisionEvents {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "watchapp_supervision",
            Context.MODE_PRIVATE,
        )
    internal val machine =
        WatchappSupervisor(
            initialOutageId = java.util.UUID.randomUUID().mostSignificantBits,
            now = SystemClock::elapsedRealtime,
        )
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var learningEpoch = 0L
    @Volatile private var learningEnabled = false
    internal var changed: (() -> Unit)? = null
    internal var blocked = false
    internal var manualRequested = false
    internal var launchBusy = false

    init {
        machine.configure(readSettings())
        learningEnabled = machine.settings.mode != SupervisionMode.OFF
    }

    private fun readSettings(): SupervisionSettings {
        val mode = runCatching {
            SupervisionMode.valueOf(preferences.getString("mode", "OFF")!!)
        }
            .getOrDefault(SupervisionMode.OFF)
        val delay =
            runCatching { preferences.getInt("delay", SupervisionSettings.DEFAULT_DELAY_SECONDS) }
                .getOrDefault(SupervisionSettings.DEFAULT_DELAY_SECONDS)
                .takeIf { it in SupervisionSettings.DELAYS }
                ?: SupervisionSettings.DEFAULT_DELAY_SECONDS
        return SupervisionSettings(mode, delay)
    }

    fun configure(settings: SupervisionSettings) {
        scope.launch {
            if (
                machine.settings.mode == SupervisionMode.OFF || settings.mode == SupervisionMode.OFF
            )
                learningEpoch++
            learningEnabled = settings.mode != SupervisionMode.OFF
            if (settings != machine.settings) manualRequested = false
            machine.configure(settings)
            persist()
            publish()
        }
    }

    internal fun persist() {
        preferences.edit {
            putString("mode", machine.settings.mode.name)
            putInt("delay", machine.settings.delaySeconds)
        }
    }

    override fun learningToken(): Long? = learningEpoch.takeIf { learningEnabled }

    override fun observationToken(): Long = machine.generation

    override fun observed(
        snapshot: BridgeProtocol.Snapshot,
        source: SupervisionSource?,
        token: Long?,
        observationToken: Long?,
    ) {
        scope.launch {
            if (observationToken != null && observationToken != machine.generation) return@launch
            if (source != null && token != learningToken()) return@launch
            observeNow(snapshot)
            if (token != null && learningEnabled) source?.let(machine::learn)
            publish()
        }
    }

    internal fun observeNow(snapshot: BridgeProtocol.Snapshot) {
        val previousId = machine.recordingId
        machine.observe(
            SupervisionRecording.valueOf(
                snapshot.state.name.let { if (it == "UNAVAILABLE") "UNKNOWN" else it }
            ),
            snapshot.recordingStartMillis,
        )
        if (previousId != null && previousId != machine.recordingId) learningEpoch++
        if (!machine.eligible) manualRequested = false
    }

    override fun opened() {
        scope.launch {
            machine.opened()
            manualRequested = false
            publish()
        }
    }

    override fun closed() {
        scope.launch {
            machine.explicitlyClosed()
            publish()
        }
    }

    internal fun publish() {
        BridgeState.update { it.copy(supervision = machine.status(blocked)) }
        changed?.invoke()
    }

    companion object {
        @Volatile private var instance: SupervisionManager? = null

        fun get(context: Context): SupervisionManager =
            instance
                ?: synchronized(this) {
                    instance ?: SupervisionManager(context).also { instance = it }
                }
    }
}

internal fun SupervisionSettings.withNotificationAvailability(
    available: Boolean
): SupervisionSettings =
    if (!available && mode == SupervisionMode.NOTIFY) copy(mode = SupervisionMode.OFF) else this
