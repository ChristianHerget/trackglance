package app.locuspebble.bridge.pebble

import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PebbleMessagesTest {
    @Test fun unsignedProtocolFieldsRequireTheExactDictionaryType() {
        val key = BridgeProtocol.Key.SESSION_ID.toUInt()
        assertEquals(
            UInt.MAX_VALUE.toLong(),
            PebbleMessages.unsigned32(mapOf(key to PebbleDictionaryItem.UInt32(UInt.MAX_VALUE)), key.toInt()),
        )
        assertNull(
            PebbleMessages.unsigned32(mapOf(key to PebbleDictionaryItem.Int32(7)), key.toInt()),
        )
        assertEquals(
            7,
            PebbleMessages.signed32(mapOf(key to PebbleDictionaryItem.Int32(7)), key.toInt()),
        )
        assertNull(
            PebbleMessages.signed32(mapOf(key to PebbleDictionaryItem.UInt32(7u)), key.toInt()),
        )
    }

    @Test fun commandResultNeverSilentlyClampsAnInvalidIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            PebbleMessages.result(-1, 1, BridgeProtocol.Result.OK)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PebbleMessages.result(1, UInt.MAX_VALUE.toLong() + 1, BridgeProtocol.Result.OK)
        }
    }

    @Test fun snapshotUsesValidatedMovingTimeAndProfileName() {
        val dictionary = PebbleMessages.snapshot(
            BridgeProtocol.Snapshot(
                state = BridgeProtocol.RecordingState.RECORDING,
                sampledAtEpochSeconds = 1,
                movingSeconds = -1,
                locusProfileName = "bad\nname",
            ),
        )
        assertEquals(
            BridgeProtocol.UNAVAILABLE,
            PebbleMessages.signed32(dictionary, BridgeProtocol.Key.MOVING_SECONDS),
        )
        assertFalse(dictionary.containsKey(BridgeProtocol.Key.LOCUS_PROFILE_NAME.toUInt()))
    }

    @Test fun invalidProfileTransferCannotConstructAuthoritativeEmptyFrames() {
        val oversized = List(40) { index -> "$index-${"x".repeat(250)}" }
        assertNull(PebbleMessages.profileListChunks(oversized, transferId = 1))
        assertNull(PebbleMessages.profileListChunks(listOf("Hiking", "Hiking"), transferId = 2))
        assertNull(
            PebbleMessages.profileListChunks(
                listOf("x".repeat(200)),
                transferId = 3,
                chunkBytes = 1,
            ),
        )
    }

    @Test fun validProfileTransferIsByteBoundedAndComplete() {
        val messages = requireNotNull(
            PebbleMessages.profileListChunks(
                listOf("Wandern ÄÖÜ 🥾", "Running"),
                transferId = 7,
                chunkBytes = 12,
            ),
        )
        assertTrue(messages.size > 1)
        assertTrue(messages.size <= BridgeProtocol.MAX_PROFILE_LIST_CHUNKS)
        val payload = messages.joinToString("") {
            PebbleMessages.string(it, BridgeProtocol.Key.CHUNK_DATA).orEmpty()
        }
        assertEquals("Wandern ÄÖÜ 🥾\nRunning", payload)
        messages.forEach { message ->
            assertTrue(
                PebbleMessages.string(message, BridgeProtocol.Key.CHUNK_DATA)
                    .orEmpty().toByteArray(Charsets.UTF_8).size <= 12,
            )
        }
    }
}
