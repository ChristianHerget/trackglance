package io.github.christianherget.trackglance.bridge.protocol

import io.github.christianherget.trackglance.bridge.core.RefreshMode
import io.github.christianherget.trackglance.bridge.core.RefreshPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {
    @Test fun resultExtensionsKeepCrossLanguageWireValuesStable() {
        assertEquals(9, BridgeProtocol.MessageType.CONFIG_RESULT.wire)
        assertEquals(10, BridgeProtocol.MessageType.RECORDING_CONTEXT.wire)
        assertEquals(11, BridgeProtocol.MessageType.REQUEST_RUNTIME_CONFIG.wire)
        assertEquals(7, BridgeProtocol.Result.CONFIG_QUEUED.wire)
        assertEquals(8, BridgeProtocol.Result.INVALID_CONFIG.wire)
        assertEquals(9, BridgeProtocol.Result.STORAGE_FAILED.wire)
    }

    @Test fun obsoleteStartCommandRemainsReservedButCannotRoute() {
        assertEquals(1, BridgeProtocol.Command.START.wire)
        BridgeProtocol.RecordingState.entries.forEach { state ->
            assertEquals(
                io.github.christianherget.trackglance.bridge.locus.LocusRecordingAction.INVALID,
                io.github.christianherget.trackglance.bridge.locus.LocusCommandRouting.actionFor(
                    BridgeProtocol.Command.START,
                    state,
                ),
            )
        }
    }

    @Test fun snapshotFormattingUsesLocusMediumPrecision() {
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
        val display = SnapshotFormatter.format(snapshot)
        assertEquals(DisplayValue(12, BridgeProtocol.FormatCode.KM_1), display.distance)
        assertEquals(DisplayValue(44, BridgeProtocol.FormatCode.KPH_1), display.currentSpeed)
        assertEquals(DisplayValue(56, BridgeProtocol.FormatCode.KPH_1), display.averageSpeed)
        assertEquals(DisplayValue(-12, BridgeProtocol.FormatCode.M_0), display.altitude)
        assertEquals(DisplayValue(88, BridgeProtocol.FormatCode.M_0), display.ascent)
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
            slopeRatio = Float.MAX_VALUE,
        )
        val display = SnapshotFormatter.format(snapshot)
        assertEquals(Int.MAX_VALUE, display.distance.mantissa)
        assertEquals(BridgeProtocol.UNAVAILABLE, display.currentSpeed.mantissa)
        assertEquals(Int.MIN_VALUE + 1, display.altitude.mantissa)
        assertEquals(BridgeProtocol.UNAVAILABLE, display.verticalSpeed.mantissa)
        assertEquals(Int.MAX_VALUE, display.slope.mantissa)
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
