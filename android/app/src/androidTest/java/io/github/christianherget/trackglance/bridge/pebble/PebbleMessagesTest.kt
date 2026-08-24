package io.github.christianherget.trackglance.bridge.pebble

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
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

    @Test fun v4SnapshotCarriesEveryFormatAndNeverExceedsTheWatchAllocation() {
        val dictionary = PebbleMessages.snapshot(
            BridgeProtocol.Snapshot(
                state = BridgeProtocol.RecordingState.RECORDING,
                sampledAtEpochSeconds = 1,
                distanceMetres = 1000f,
                movingDistanceMetres = 10f,
                currentSpeedMps = 2f,
                averageSpeedMps = 3f,
                maxSpeedMps = 4f,
                locusProfileName = "x".repeat(BridgeProtocol.MAX_LOCUS_PROFILE_NAME_BYTES),
            ),
        )
        for (key in listOf(
            BridgeProtocol.Key.ALTITUDE_FORMAT,
            BridgeProtocol.Key.DISTANCE_FORMAT,
            BridgeProtocol.Key.MOVING_DISTANCE_FORMAT,
            BridgeProtocol.Key.CURRENT_SPEED_FORMAT,
            BridgeProtocol.Key.AVERAGE_SPEED_FORMAT,
            BridgeProtocol.Key.MAX_SPEED_FORMAT,
            BridgeProtocol.Key.VERTICAL_SPEED_FORMAT,
            BridgeProtocol.Key.SLOPE_FORMAT,
            BridgeProtocol.Key.ENERGY_FORMAT,
            BridgeProtocol.Key.PACE_FORMAT,
        )) assertTrue(dictionary[key.toUInt()] is PebbleDictionaryItem.Int8)
        assertTrue(PebbleMessages.encodedSize(dictionary) <= BridgeProtocol.MAX_APP_MESSAGE_BYTES)
        assertFalse(dictionary.containsKey(BridgeProtocol.Key.LOCUS_PROFILE_NAME.toUInt()))
    }

    @Test fun invalidProfileTransferCannotConstructAuthoritativeEmptyFrames() {
        val oversized = List(40) { index ->
            BridgeProtocol.RecordingProfile((index + 1).toLong(), "$index-${"x".repeat(250)}")
        }
        assertNull(PebbleMessages.profileListChunks(oversized, transferId = 1))
        assertNull(PebbleMessages.profileListChunks(
            listOf(
                BridgeProtocol.RecordingProfile(1, "Hiking"),
                BridgeProtocol.RecordingProfile(1, "Renamed"),
            ),
            transferId = 2,
        ))
        assertNull(
            PebbleMessages.profileListChunks(
                listOf(BridgeProtocol.RecordingProfile(1, "x".repeat(200))),
                transferId = 3,
                chunkBytes = 1,
            ),
        )
    }

    @Test fun validProfileTransferIsByteBoundedAndComplete() {
        val messages = requireNotNull(
            PebbleMessages.profileListChunks(
                listOf(
                    BridgeProtocol.RecordingProfile(7, "Wandern ÄÖÜ 🥾"),
                    BridgeProtocol.RecordingProfile(9, "Running"),
                ),
                transferId = 7,
                chunkBytes = 12,
            ),
        )
        assertTrue(messages.size > 1)
        assertTrue(messages.size <= BridgeProtocol.MAX_PROFILE_LIST_CHUNKS)
        val payload = messages.joinToString("") {
            PebbleMessages.string(it, BridgeProtocol.Key.CHUNK_DATA).orEmpty()
        }
        assertEquals("7|Wandern ÄÖÜ 🥾\n9|Running", payload)
        messages.forEach { message ->
            assertEquals(
                BridgeProtocol.DURABLE_TRANSFER_GENERATION,
                PebbleMessages.signed32(message, BridgeProtocol.Key.TRANSFER_GENERATION),
            )
            assertTrue(
                PebbleMessages.string(message, BridgeProtocol.Key.CHUNK_DATA)
                    .orEmpty().toByteArray(Charsets.UTF_8).size <= 12,
            )
        }
    }

    @Test fun recordingContextIsSeparateAndBoundedAtMaximumNameLength() {
        val context = PebbleMessages.recordingContext(
            BridgeProtocol.RecordingState.RECORDING,
            BridgeProtocol.RecordingProfile(42, "x".repeat(BridgeProtocol.MAX_LOCUS_PROFILE_NAME_BYTES)),
        )
        assertEquals("42", PebbleMessages.string(context, BridgeProtocol.Key.LOCUS_PROFILE_ID))
        assertEquals(
            BridgeProtocol.MAX_LOCUS_PROFILE_NAME_BYTES,
            PebbleMessages.string(context, BridgeProtocol.Key.LOCUS_PROFILE_NAME)?.length,
        )
        assertTrue(PebbleMessages.encodedSize(context) <= BridgeProtocol.MAX_APP_MESSAGE_BYTES)
    }

    @Test fun stoppedSnapshotSuppressesGenericLocationAndSensorValues() {
        val stopped = PebbleMessages.snapshot(
            BridgeProtocol.Snapshot(
                BridgeProtocol.RecordingState.STOPPED,
                sampledAtEpochSeconds = 3,
                currentSpeedMps = 12f,
                altitudeMetres = 100.0,
                currentHeartRate = 150,
            ),
        )
        assertEquals(
            BridgeProtocol.UNAVAILABLE,
            PebbleMessages.signed32(stopped, BridgeProtocol.Key.CURRENT_SPEED_VALUE),
        )
        assertEquals(
            BridgeProtocol.UNAVAILABLE,
            PebbleMessages.signed32(stopped, BridgeProtocol.Key.CURRENT_HEART_RATE),
        )
    }
}
