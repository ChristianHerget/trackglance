package app.locuspebble.bridge.core

/** Stateful validation for independent unsigned watch/session sequence streams. */
class HeartRateSampleGate(
    private val maxAgeSeconds: Long = 30,
    private val maxStreams: Int = 64,
) {
    private data class Stream(val watchId: String, val sessionId: Long)

    private val lastSequences = object : LinkedHashMap<Stream, Long>(maxStreams, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stream, Long>): Boolean =
            size > maxStreams
    }

    init {
        require(maxAgeSeconds >= 0)
        require(maxStreams > 0)
    }

    @Synchronized
    fun accept(
        watchId: String,
        session: Long,
        sequence: Long,
        bpm: Int,
        sampledAt: Long,
        now: Long,
    ): Boolean {
        if (watchId.isBlank()) return false
        if (session !in 0..0xffff_ffffL || sequence !in 0..0xffff_ffffL || bpm !in 25..250) return false
        if (sampledAt > now + 5 || sampledAt < now - maxAgeSeconds) return false
        val stream = Stream(watchId, session)
        if (sequence <= (lastSequences[stream] ?: -1L)) return false
        lastSequences[stream] = sequence
        return true
    }
}
