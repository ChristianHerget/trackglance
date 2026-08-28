package io.github.christianherget.trackglance.bridge.pebble

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRequestHandlerTest {
    @Test
    fun realDictionaryEstablishesSessionBeforeRecovery() = runBlocking {
        val events = mutableListOf<String>()
        val handler =
            SnapshotRequestHandler(
                establish = { session ->
                    events += "establish:$session"
                    true
                },
                recover = {
                    events += "recover"
                    true
                },
            )
        val dictionary =
            mapOf(
                BridgeProtocol.Key.VERSION.toUInt() to
                    PebbleDictionaryItem.Int32(BridgeProtocol.VERSION),
                BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to
                    PebbleDictionaryItem.Int32(BridgeProtocol.MessageType.REQUEST_SNAPSHOT.wire),
                BridgeProtocol.Key.SESSION_ID.toUInt() to PebbleDictionaryItem.UInt32(27u),
            )

        assertTrue(handler.handle(dictionary))
        assertEquals(listOf("establish:27", "recover"), events)
    }

    @Test
    fun missingOrWronglyTypedSessionIsNackedWithoutRecovery() = runBlocking {
        var called = false
        val handler =
            SnapshotRequestHandler(
                establish = {
                    called = true
                    true
                },
                recover = {
                    called = true
                    true
                },
            )
        assertFalse(handler.handle(emptyMap()))
        assertFalse(
            handler.handle(
                mapOf(BridgeProtocol.Key.SESSION_ID.toUInt() to PebbleDictionaryItem.Int32(-1))
            )
        )
        assertFalse(called)
    }

    @Test
    fun rejectedAuthorityNeverStartsRecovery() = runBlocking {
        var recovered = false
        val handler =
            SnapshotRequestHandler(
                establish = { false },
                recover = {
                    recovered = true
                    true
                },
            )
        assertFalse(
            handler.handle(
                mapOf(BridgeProtocol.Key.SESSION_ID.toUInt() to PebbleDictionaryItem.UInt32(1u))
            )
        )
        assertFalse(recovered)
    }
}
