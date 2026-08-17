package app.locuspebble.bridge.core

/** Stateful validation for the unsigned watch session/sequence stream. */
class HeartRateSampleGate(private val maxAgeSeconds: Long = 30) {
    private var sessionId: Long? = null
    private var lastSequence = -1L

    @Synchronized
    fun accept(session: Long, sequence: Long, bpm: Int, sampledAt: Long, now: Long): Boolean {
        if (session !in 0..0xffff_ffffL || sequence !in 0..0xffff_ffffL || bpm !in 25..250) return false
        if (sampledAt > now + 5 || sampledAt < now - maxAgeSeconds) return false
        if (sessionId != session) {
            sessionId = session
            lastSequence = -1
        }
        if (sequence <= lastSequence) return false
        lastSequence = sequence
        return true
    }
}
