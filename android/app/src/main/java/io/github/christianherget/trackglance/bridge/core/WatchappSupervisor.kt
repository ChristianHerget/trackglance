package io.github.christianherget.trackglance.bridge.core

/** Process-local learning. The owner serializes events and executes effects using [generation]. */
enum class SupervisionMode {
    OFF,
    NOTIFY,
    AUTO_START,
}

enum class SupervisionSource {
    HEART_RATE,
    STEPS,
}

enum class SupervisionRecording {
    UNKNOWN,
    STOPPED,
    RECORDING,
    PAUSED,
}

enum class SupervisionPhase {
    OFF,
    WAITING,
    ACTIVE,
    PAUSED,
    CLOSED,
    SUSPENDED,
}

data class SupervisionSettings(
    val mode: SupervisionMode = SupervisionMode.OFF,
    val delaySeconds: Int = DEFAULT_DELAY_SECONDS,
) {
    init {
        require(delaySeconds in DELAYS)
    }

    val delayMillis
        get() = delaySeconds * MILLIS_PER_SECOND

    companion object {
        const val DEFAULT_DELAY_SECONDS = 30
        val DELAYS = listOf(15, 30, 45, 60, 75, 90)
        private const val MILLIS_PER_SECOND = 1000L
    }
}

data class SupervisionStatus(
    val settings: SupervisionSettings = SupervisionSettings(),
    val phase: SupervisionPhase = SupervisionPhase.OFF,
    val sources: Set<SupervisionSource> = emptySet(),
    val notificationsBlocked: Boolean = false,
)

@Suppress(
    "TooManyFunctions"
) // Explicit event handlers and transitions belong to one state machine.
class WatchappSupervisor(initialOutageId: Long = 0L, private val now: () -> Long) {
    var settings = SupervisionSettings()
        private set

    @Volatile
    var generation = 0L
        private set

    var outageId = initialOutageId
        private set

    var recordingId: Long? = null
        private set

    var recording = SupervisionRecording.UNKNOWN
        private set

    var closed = false
        private set

    var sources: Set<SupervisionSource> = emptySet()
        private set

    var attempts = 0
        private set

    var alert = false
        private set

    var dismissed = false
        private set

    var deadline: Long? = null
        private set

    var confirming = false
        private set

    var launching = false
        private set

    private var nextAttempt: Long? = null
    private var alerted = false
    private var recoveryUnconfirmed = false
    val eligible
        get() =
            settings.mode != SupervisionMode.OFF &&
                sources.isNotEmpty() &&
                closed &&
                recording == SupervisionRecording.RECORDING

    val needsPolling
        get() = settings.mode != SupervisionMode.OFF && closed && sources.isNotEmpty()

    fun status(blocked: Boolean = false) =
        SupervisionStatus(
            settings,
            when {
                settings.mode == SupervisionMode.OFF -> SupervisionPhase.OFF
                sources.isEmpty() -> SupervisionPhase.WAITING
                recording == SupervisionRecording.UNKNOWN -> SupervisionPhase.SUSPENDED
                recording == SupervisionRecording.PAUSED -> SupervisionPhase.PAUSED
                closed -> SupervisionPhase.CLOSED
                else -> SupervisionPhase.ACTIVE
            },
            sources,
            blocked,
        )

    fun configure(value: SupervisionSettings) {
        if (settings == value) return
        settings = value
        invalidateWork()
        if (value.mode == SupervisionMode.OFF) clearExpectation() else schedule()
    }

    fun observe(state: SupervisionRecording, id: Long?) {
        val knownId = id?.takeIf { it > 0 }
        val effective =
            if (state != SupervisionRecording.STOPPED && knownId == null)
                SupervisionRecording.UNKNOWN
            else state
        val previous = recording
        if (effective == SupervisionRecording.STOPPED) {
            clearExpectation()
            recording = effective
            return
        }
        if (effective != SupervisionRecording.UNKNOWN && recordingId != knownId) {
            clearExpectation()
            recordingId = knownId
        }
        recording = effective
        if (effective == SupervisionRecording.UNKNOWN) {
            if (previous != effective) invalidateWork()
        } else if (effective == SupervisionRecording.PAUSED) {
            if (previous != effective) resetOutage()
        } else if (previous != effective) {
            schedule()
        }
    }

    fun learn(source: SupervisionSource) {
        if (
            settings.mode == SupervisionMode.OFF ||
                recordingId == null ||
                recording !in setOf(SupervisionRecording.RECORDING, SupervisionRecording.PAUSED)
        )
            return
        sources = sources + source
        // Learning from traffic never starts or resolves an outage.
    }

    fun opened() {
        closed = false
        resetOutage()
    }

    fun explicitlyClosed() {
        if (closed) return
        closed = true
        resetOutage()
        schedule()
    }

    fun dismiss(token: Long) {
        if (token != outageId) return
        dismissed = true
        alert = false
    }

    /** Call only after a fresh recording observation, immediately before dispatch. */
    fun beginLaunch(manual: Boolean = false): Long? {
        val automaticAllowed =
            settings.mode == SupervisionMode.AUTO_START && attempts < MAX_ATTEMPTS && due()
        val ready = eligible && !launching && !confirming
        if (!ready || (!manual && !automaticAllowed)) return null
        launching = true
        recoveryUnconfirmed = true
        if (manual && nextAttempt == null) nextAttempt = deadline ?: (now() + settings.delayMillis)
        if (!manual) {
            attempts++
            nextAttempt = now() + settings.delayMillis
        }
        deadline = null
        return generation
    }

    fun launchResult(token: Long, dispatched: Boolean) {
        if (token != generation || !launching || !eligible) return
        launching = false
        if (dispatched) {
            confirming = true
            deadline = now() + CONFIRMATION_MILLIS
        } else {
            showAlert()
            scheduleRetry()
        }
    }

    fun due() = deadline?.let { now() >= it } == true

    /** Notification work also requires a fresh recording observation. */
    fun tick() {
        if (!eligible || !due() || launching) return
        if (confirming || recoveryUnconfirmed || settings.mode == SupervisionMode.NOTIFY) {
            confirming = false
            showAlert()
            scheduleRetry()
        }
    }

    private fun showAlert() {
        recoveryUnconfirmed = false
        if (!alerted) {
            alerted = true
            alert = !dismissed
        }
    }

    private fun scheduleRetry() {
        deadline =
            if (settings.mode == SupervisionMode.AUTO_START && attempts < MAX_ATTEMPTS) nextAttempt
            else null
    }

    @Suppress("ReturnCount")
    private fun schedule() {
        if (!eligible) return
        if (settings.mode == SupervisionMode.NOTIFY && alerted) return
        if (
            settings.mode == SupervisionMode.AUTO_START &&
                attempts >= MAX_ATTEMPTS &&
                !recoveryUnconfirmed
        )
            return
        deadline = now() + settings.delayMillis
        nextAttempt = deadline
    }

    private fun invalidateWork() {
        generation++
        deadline = null
        nextAttempt = null
        launching = false
        confirming = false
    }

    private fun resetOutage() {
        outageId++
        invalidateWork()
        attempts = 0
        recoveryUnconfirmed = false
        alert = false
        alerted = false
        dismissed = false
    }

    private fun clearExpectation() {
        resetOutage()
        sources = emptySet()
        recordingId = null
    }

    companion object {
        const val CONFIRMATION_MILLIS = 5000L
        const val MAX_ATTEMPTS = 3
    }
}
