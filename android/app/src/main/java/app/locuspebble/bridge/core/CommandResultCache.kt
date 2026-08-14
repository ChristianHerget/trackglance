package app.locuspebble.bridge.core

import app.locuspebble.bridge.protocol.BridgeProtocol

/** Deduplicates retransmissions without confusing IDs from different watchapp sessions. */
class CommandResultCache(private val capacity: Int = 32) {
    private data class Key(val sessionId: Long, val commandId: Long)

    private val values = object : LinkedHashMap<Key, BridgeProtocol.Result>(capacity, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, BridgeProtocol.Result>,
        ): Boolean = size > capacity
    }

    fun get(sessionId: Long, commandId: Long): BridgeProtocol.Result? =
        values[Key(sessionId, commandId)]

    fun put(sessionId: Long, commandId: Long, result: BridgeProtocol.Result) {
        values[Key(sessionId, commandId)] = result
    }
}
