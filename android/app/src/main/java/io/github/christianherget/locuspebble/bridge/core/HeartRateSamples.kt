package io.github.christianherget.locuspebble.bridge.core

import io.github.christianherget.locuspebble.bridge.protocol.BridgeProtocol

/** Stateful validation for independent unsigned watch/session sequence streams. */
class HeartRateSampleGate(
    private val maxAgeSeconds: Long = 30,
) {
    private data class Stream(val watchId: String, val sessionId: Long, val trustGeneration: Long)

    private val lastSequences = object : LinkedHashMap<Stream, Long>(MAX_RECENT_STREAMS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stream, Long>): Boolean =
            size > MAX_RECENT_STREAMS
    }

    init {
        require(maxAgeSeconds >= 0)
    }

    @Synchronized
    fun accept(
        watchId: String,
        session: Long,
        sequence: Long,
        bpm: Int,
        sampledAt: Long,
        now: Long,
        trustGeneration: Long = 0,
    ): Boolean {
        if (!BridgeProtocol.validWatchId(watchId)) return false
        if (session !in 0..0xffff_ffffL || sequence !in 0..0xffff_ffffL || bpm !in 25..250) return false
        if (sampledAt > now + 5 || sampledAt < now - maxAgeSeconds) return false
        val stream = Stream(watchId, session, trustGeneration)
        if (sequence <= (lastSequences[stream] ?: -1L)) return false
        lastSequences[stream] = sequence
        return true
    }

    private companion object {
        const val MAX_RECENT_STREAMS = 8
    }
}
