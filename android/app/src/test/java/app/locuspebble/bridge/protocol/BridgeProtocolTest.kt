package app.locuspebble.bridge.protocol

import app.locuspebble.bridge.core.RefreshMode
import app.locuspebble.bridge.core.RefreshPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {
    @Test fun configResultExtensionKeepsCrossLanguageWireValuesStable() {
        assertEquals(9, BridgeProtocol.MessageType.CONFIG_RESULT.wire)
        assertEquals(7, BridgeProtocol.Result.CONFIG_QUEUED.wire)
        assertEquals(8, BridgeProtocol.Result.INVALID_CONFIG.wire)
        assertEquals(9, BridgeProtocol.Result.STORAGE_FAILED.wire)
    }

    @Test fun snapshotScalesValuesForAppMessage() {
        val snapshot = BridgeProtocol.Snapshot(
            state = BridgeProtocol.RecordingState.RECORDING,
            sampledAtEpochSeconds = 100,
            distanceMetres = 1234.6f,
            currentSpeedMps = 1.234f,
            averageSpeedMps = 1.567f,
            altitudeMetres = -12.34,
            ascentMetres = 87.65f,
            currentHeartRate = 123,
        )
        assertEquals(1235, snapshot.distanceWire())
        assertEquals(123, snapshot.currentSpeedWire())
        assertEquals(157, snapshot.averageSpeedWire())
        assertEquals(-123, snapshot.altitudeWire())
        assertEquals(877, snapshot.ascentWire())
        assertEquals(123, snapshot.integerWire(snapshot.currentHeartRate))
    }

    @Test fun refreshModesUseExpectedCadence() {
        assertEquals(2_000, RefreshPolicy(RefreshMode.ADAPTIVE).nextDelayMillis(true))
        assertEquals(10_000, RefreshPolicy(RefreshMode.ADAPTIVE).nextDelayMillis(false))
        assertEquals(5_000, RefreshPolicy(RefreshMode.FIVE_SECONDS).nextDelayMillis(false))
        assertEquals(10_000, RefreshPolicy(RefreshMode.TEN_SECONDS).nextDelayMillis(true))
    }

    @Test fun waypointNamesUseTheSingleMessageUtf8Limit() {
        assertTrue(BridgeProtocol.validWaypointName("Abzweig – links halten"))
        assertTrue(BridgeProtocol.validWaypointName("ä".repeat(60)))
        assertFalse(BridgeProtocol.validWaypointName("ä".repeat(61)))
        assertFalse(BridgeProtocol.validWaypointName(""))
        assertFalse(BridgeProtocol.validWaypointName("nur Leerraum\t"))
        assertFalse(BridgeProtocol.validWaypointName("erste Zeile\nzweite Zeile"))
        assertFalse(BridgeProtocol.validWaypointName("löschen\u007f"))
        assertTrue(BridgeProtocol.validWaypointName("steuern\u0085"))
        assertFalse(BridgeProtocol.validWaypointName("\u0085"))
        assertFalse(BridgeProtocol.validWaypointName("\ufeff\ufeff"))
        assertFalse(BridgeProtocol.validWaypointName("broken\ud800"))
        assertFalse(BridgeProtocol.validWaypointName("\udc00broken"))
    }

    @Test fun numericConversionsSaturateWithoutEmittingTheUnavailableSentinel() {
        val snapshot = BridgeProtocol.Snapshot(
            state = BridgeProtocol.RecordingState.RECORDING,
            sampledAtEpochSeconds = 1,
            movingSeconds = -1,
            distanceMetres = Float.MAX_VALUE,
            currentSpeedMps = -0.1f,
            altitudeMetres = -Double.MAX_VALUE,
            verticalSpeedMps = Float.POSITIVE_INFINITY,
            slopePercent = Float.MAX_VALUE,
        )

        assertEquals(Int.MAX_VALUE, snapshot.distanceWire())
        assertEquals(BridgeProtocol.UNAVAILABLE, snapshot.currentSpeedWire())
        assertEquals(Int.MIN_VALUE + 1, snapshot.altitudeWire())
        assertEquals(BridgeProtocol.UNAVAILABLE, snapshot.verticalSpeedWire())
        assertEquals(Int.MAX_VALUE, snapshot.slopeWire())
        assertEquals(BridgeProtocol.UNAVAILABLE, snapshot.movingSecondsWire())
        assertEquals(
            Int.MAX_VALUE,
            snapshot.copy(movingSeconds = Long.MAX_VALUE).movingSecondsWire(),
        )
    }

    @Test fun unsignedIdentifiersAreRejectedRatherThanClamped() {
        assertEquals(UInt.MAX_VALUE, BridgeProtocol.requireUnsigned32(UInt.MAX_VALUE.toLong()))
        assertThrows(IllegalArgumentException::class.java) { BridgeProtocol.requireUnsigned32(-1) }
        assertThrows(IllegalArgumentException::class.java) {
            BridgeProtocol.requireUnsigned32(UInt.MAX_VALUE.toLong() + 1)
        }
    }

    @Test fun watchIdentifiersAreBoundedAndRequireWellFormedUtf8Text() {
        assertTrue(BridgeProtocol.validWatchId("watch-a"))
        assertTrue(BridgeProtocol.validWatchId("Pebble 🪨"))
        assertFalse(BridgeProtocol.validWatchId(""))
        assertFalse(BridgeProtocol.validWatchId("\ufeff\u0085"))
        assertFalse(BridgeProtocol.validWatchId("broken\ud800"))
        assertFalse(BridgeProtocol.validWatchId("\udc00broken"))
        assertFalse(BridgeProtocol.validWatchId("x".repeat(BridgeProtocol.MAX_WATCH_ID_BYTES + 1)))
    }
}
