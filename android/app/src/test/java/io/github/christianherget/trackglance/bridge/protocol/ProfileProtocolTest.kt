package io.github.christianherget.trackglance.bridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileProtocolTest {
    @Test
    fun catalogUsesValidatedNumericIdentityAndCurrentName() {
        val profiles =
            listOf(
                BridgeProtocol.RecordingProfile(42, "Wandern Ä"),
                BridgeProtocol.RecordingProfile(9876543210, "Running"),
            )
        assertEquals(
            "42|Wandern Ä\n9876543210|Running",
            BridgeProtocol.profileListPayload(profiles),
        )
        assertEquals(
            "1|Hiking\n2|Hiking",
            BridgeProtocol.profileListPayload(
                listOf(
                    BridgeProtocol.RecordingProfile(1, "Hiking"),
                    BridgeProtocol.RecordingProfile(2, "Hiking"),
                )
            ),
        )
        assertNull(BridgeProtocol.profileListPayload(profiles + profiles.first()))
        assertEquals(
            "0|Hiking",
            BridgeProtocol.profileListPayload(listOf(BridgeProtocol.RecordingProfile(0, "Hiking"))),
        )
        assertNull(
            BridgeProtocol.profileListPayload(
                listOf(BridgeProtocol.RecordingProfile(1, "bad|name"))
            )
        )
    }

    @Test
    fun emptyCatalogIsNonAuthoritativeAndUtf8ChunksStayBounded() {
        val empty =
            requireNotNull(
                BridgeProtocol.profileTransfer(emptyList<BridgeProtocol.RecordingProfile>())
            )
        assertEquals(BridgeProtocol.Result.FAILED, empty.result)
        assertEquals(listOf(""), empty.chunks)
        val transfer =
            requireNotNull(
                BridgeProtocol.profileTransfer(
                    listOf(BridgeProtocol.RecordingProfile(7, "Wandern ÄÖÜ 🥾")),
                    chunkBytes = 12,
                )
            )
        assertEquals("7|Wandern ÄÖÜ 🥾", transfer.chunks.joinToString(""))
        assertTrue(transfer.chunks.all { it.toByteArray().size <= 12 })
    }

    @Test
    fun profileIdsAndNamesRejectMalformedWireValues() {
        assertTrue(BridgeProtocol.validLocusProfileId("1"))
        assertTrue(BridgeProtocol.validLocusProfileId(Long.MAX_VALUE.toString()))
        assertTrue(BridgeProtocol.validLocusProfileId("0"))
        assertFalse(BridgeProtocol.validLocusProfileId("01"))
        assertTrue(BridgeProtocol.validLocusProfileId("-1"))
        assertTrue(BridgeProtocol.validLocusProfileId(Long.MIN_VALUE.toString()))
        assertFalse(BridgeProtocol.validLocusProfileId("-0"))
        assertFalse(BridgeProtocol.validLocusProfileId("9223372036854775808"))
        assertTrue(BridgeProtocol.validLocusProfileName("A very long exact Locus name Ä"))
        assertFalse(BridgeProtocol.validLocusProfileName("bad\nname"))
    }
}
