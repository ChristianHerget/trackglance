package app.locuspebble.bridge.protocol

import app.locuspebble.bridge.core.RefreshMode
import app.locuspebble.bridge.core.RefreshPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {
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
    }
}
