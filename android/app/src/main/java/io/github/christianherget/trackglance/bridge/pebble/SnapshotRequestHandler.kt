package io.github.christianherget.trackglance.bridge.pebble

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.PebbleDictionary

/** Parses and authorizes a type-4 recovery request before any snapshot work begins. */
internal class SnapshotRequestHandler(
    private val establish: (sessionId: Long) -> Boolean,
    private val recover: suspend () -> Boolean,
) {
    @Suppress("ReturnCount")
    suspend fun handle(data: PebbleDictionary): Boolean {
        val sessionId =
            PebbleMessages.unsigned32(data, BridgeProtocol.Key.SESSION_ID) ?: return false
        if (!establish(sessionId)) return false
        return recover()
    }
}
