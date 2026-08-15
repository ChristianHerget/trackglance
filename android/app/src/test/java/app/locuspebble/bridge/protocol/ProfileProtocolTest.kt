package app.locuspebble.bridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileProtocolTest {
    @Test fun profileTransferDistinguishesEmptyLocusResultFromProfiles() {
        assertEquals(BridgeProtocol.Result.FAILED, BridgeProtocol.profileListResult(""))
        assertEquals(BridgeProtocol.Result.OK, BridgeProtocol.profileListResult("Wandern"))
    }

    @Test fun profileNamesAndCaseInsensitiveMatchingAreValidated() {
        assertTrue(BridgeProtocol.validProfileName("Trail run"))
        assertFalse(BridgeProtocol.validProfileName(""))
        assertFalse(BridgeProtocol.validProfileName("123456789012345678901"))
        assertFalse(BridgeProtocol.validProfileName("bad|name"))
        assertEquals("HIKING", BridgeProtocol.autoMatchProfile("hiking", listOf("HIKING", "Bike")))
        assertNull(BridgeProtocol.autoMatchProfile("Run", listOf("Hiking")))
        assertTrue(BridgeProtocol.validLocusProfileName("A very long exact Locus profile name Ä"))
    }

    @Test fun profileChunksPreserveExactUnicodeNamesAndByteBoundaries() {
        val names = listOf("A very long exact Locus profile name", "Wandern ÄÖÜ 🥾", "Running")
        val chunks = BridgeProtocol.utf8Chunks(names.joinToString("\n"), 12)
        val payload = chunks.joinToString("")
        assertEquals(names.joinToString("\n"), payload)
        chunks.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 12) }
    }

    @Test fun chunksOnlyPublishACompleteConsistentTransfer() {
        val assembler = ChunkAssembler()
        assertNull(assembler.accept(4, 0, 2, "hel"))
        assertEquals("hello", assembler.accept(4, 1, 2, "lo"))
        assertNull(assembler.accept(5, 1, 2, "ignored"))
        assertNull(assembler.accept(5, 0, 2, "new"))
        assertEquals("new value", assembler.accept(5, 1, 2, " value"))
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
