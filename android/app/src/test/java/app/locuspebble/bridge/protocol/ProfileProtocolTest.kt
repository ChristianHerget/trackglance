package app.locuspebble.bridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProfileProtocolTest {
    @Test fun profileTransferDistinguishesEmptyLocusResultFromProfiles() {
        assertEquals(BridgeProtocol.Result.FAILED, BridgeProtocol.profileListResult(""))
        assertEquals(BridgeProtocol.Result.OK, BridgeProtocol.profileListResult("Wandern"))
        val empty = requireNotNull(BridgeProtocol.profileTransfer(emptyList()))
        assertEquals(BridgeProtocol.Result.FAILED, empty.result)
        assertEquals(listOf(""), empty.chunks)
    }

    @Test fun profileNamesAndCaseInsensitiveMatchingAreValidated() {
        assertTrue(BridgeProtocol.validProfileName("Trail run"))
        assertFalse(BridgeProtocol.validProfileName(""))
        assertFalse(BridgeProtocol.validProfileName("123456789012345678901"))
        assertFalse(BridgeProtocol.validProfileName("bad|name"))
        assertTrue(BridgeProtocol.validProfileName("😀".repeat(20)))
        assertFalse(BridgeProtocol.validProfileName("😀".repeat(21)))
        assertFalse(BridgeProtocol.validProfileName("\ufeff\ufeff"))
        assertFalse(BridgeProtocol.validProfileName("broken\ud800"))
        assertTrue(BridgeProtocol.validLocusProfileName("bad\u0085name"))
        assertFalse(BridgeProtocol.validLocusProfileName("\u0085"))
        assertFalse(BridgeProtocol.validLocusProfileName("\ufeff\ufeff"))
        assertFalse(BridgeProtocol.validLocusProfileName("broken\udc00"))
        assertEquals("HIKING", BridgeProtocol.autoMatchProfile("hiking", listOf("HIKING", "Bike")))
        assertEquals("Run", BridgeProtocol.autoMatchProfile("Run", listOf("Run", "RUN")))
        assertNull(BridgeProtocol.autoMatchProfile("run", listOf("Run", "RUN")))
        assertNull(BridgeProtocol.autoMatchProfile(" Run ", listOf("Run")))
        assertNull(BridgeProtocol.autoMatchProfile("Run", listOf("Hiking")))
        assertTrue(BridgeProtocol.validLocusProfileName("A very long exact Locus profile name Ä"))
    }

    @Test fun profileChunksPreserveExactUnicodeNamesAndByteBoundaries() {
        val names = listOf("A very long exact Locus profile name", "Wandern ÄÖÜ 🥾", "Running")
        val chunks = BridgeProtocol.utf8Chunks(names.joinToString("\n"), 12)
        val payload = chunks.joinToString("")
        assertEquals(names.joinToString("\n"), payload)
        chunks.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 12) }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeProtocol.utf8Chunks("🥾", 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeProtocol.utf8Chunks("broken\ud800", 80)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeProtocol.utf8Chunks("\udc00broken", 80)
        }
    }

    @Test fun profilePayloadCannotExceedTheWatchReassemblyBuffer() {
        val oversized = List(40) { index -> "$index-${"x".repeat(250)}" }
        assertNull(BridgeProtocol.profileListPayload(oversized))
        assertEquals("Hiking\nCycling", BridgeProtocol.profileListPayload(listOf("Hiking", "Cycling")))
        assertNull(BridgeProtocol.profileListPayload(listOf("Hiking", "bad\nname")))
        assertNull(BridgeProtocol.profileListPayload(listOf("Hiking", "Hiking")))

        assertNull(BridgeProtocol.profileTransfer(oversized))
        assertNull(BridgeProtocol.profileTransfer(listOf("Hiking", "Hiking")))
        assertNull(BridgeProtocol.profileTransfer(listOf("x".repeat(200)), chunkBytes = 1))
        assertNull(BridgeProtocol.profileTransfer(listOf("🥾"), chunkBytes = 3))
    }

    @Test fun validProfileTransferRespectsTheProtocolChunkCountAndByteLimits() {
        assertEquals(103, BridgeProtocol.MAX_PROFILE_LIST_CHUNKS)
        assertEquals(80, BridgeProtocol.MAX_CHUNK_BYTES)
        assertThrows(IllegalArgumentException::class.java) {
            BridgeProtocol.profileTransfer(listOf("Hiking"), chunkBytes = 81)
        }
        val transfer = requireNotNull(
            BridgeProtocol.profileTransfer(listOf("Wandern ÄÖÜ 🥾", "Running"), chunkBytes = 12),
        )
        assertEquals(BridgeProtocol.Result.OK, transfer.result)
        assertEquals("Wandern ÄÖÜ 🥾\nRunning", transfer.chunks.joinToString(""))
        assertTrue(transfer.chunks.size <= BridgeProtocol.MAX_PROFILE_LIST_CHUNKS)
        transfer.chunks.forEach { chunk ->
            assertTrue(chunk.toByteArray(Charsets.UTF_8).size <= 12)
        }
    }

    @Test fun unavailableAndExpandedMetricsUseDocumentedScaling() {
        val value = BridgeProtocol.Snapshot(
            BridgeProtocol.RecordingState.RECORDING, 1,
            movingDistanceMetres = 12.6f, maxSpeedMps = 2.345f, descentMetres = 5.55f,
            verticalSpeedMps = -1.234f, slopePercent = -4.56f,
        )
        assertEquals(13, value.movingDistanceWire())
        assertEquals(235, value.maxSpeedWire())
        assertEquals(56, value.descentWire())
        assertEquals(-123, value.verticalSpeedWire())
        assertEquals(-46, value.slopeWire())
        assertEquals(BridgeProtocol.UNAVAILABLE, value.currentSpeedWire())
    }
}
