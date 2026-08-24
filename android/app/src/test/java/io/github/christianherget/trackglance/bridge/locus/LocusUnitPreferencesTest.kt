package io.github.christianherget.trackglance.bridge.locus

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class LocusUnitPreferencesTest {
    @Test fun everySupportedCodeMapsAndInvalidFieldsFallBackIndividually() {
        BridgeProtocol.LengthFormat.entries.forEachIndexed { code, expected ->
            assertEquals(expected, mapped(length = code).length)
        }
        BridgeProtocol.AltitudeFormat.entries.forEachIndexed { code, expected ->
            assertEquals(expected, mapped(altitude = code).altitude)
        }
        BridgeProtocol.SpeedFormat.entries.forEachIndexed { code, expected ->
            assertEquals(expected, mapped(speed = code).speed)
        }
        BridgeProtocol.SlopeFormat.entries.forEachIndexed { code, expected ->
            assertEquals(expected, mapped(slope = code).slope)
        }
        BridgeProtocol.EnergyFormat.entries.forEachIndexed { code, expected ->
            assertEquals(expected, mapped(energy = code).energy)
        }
        assertEquals(BridgeProtocol.UnitPreferences.METRIC, mapped(-1, 99, -2, 4, 8))
    }

    @Test fun cacheUsesColdMetricFallbackRateLimitsAndRetainsLastValidRead() {
        var now = 10L
        var calls = 0
        var next: RawLocusUnitPreferences? = null
        val cache = LocusUnitPreferencesCache(
            read = { calls++; next },
            monotonicMillis = { now },
        )
        assertEquals(BridgeProtocol.UnitPreferences.METRIC, cache.current())
        assertEquals(1, calls)
        next = RawLocusUnitPreferences(3, 1, 1, 1, 1)
        now += 59_999
        assertEquals(BridgeProtocol.UnitPreferences.METRIC, cache.current())
        assertEquals(1, calls)
        now++
        val imperial = cache.current()
        assertEquals(BridgeProtocol.LengthFormat.FEET_MILES, imperial.length)
        assertEquals(2, calls)
        next = null
        now += 60_000
        assertEquals(imperial, cache.current())
        assertEquals(3, calls)
    }

    private fun mapped(
        length: Int = 1,
        altitude: Int = 0,
        speed: Int = 0,
        slope: Int = 0,
        energy: Int = 0,
    ) = LocusUnitPreferenceMapper.map(RawLocusUnitPreferences(length, altitude, speed, slope, energy))
}
