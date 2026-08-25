package io.github.christianherget.trackglance.bridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotFormatterTest {
    @Test
    fun distanceThresholdsAndPrecisionMatchLocusMediumFormatting() {
        assertDistance(
            999.9f,
            BridgeProtocol.LengthFormat.METRES_KILOMETRES,
            1000,
            BridgeProtocol.FormatCode.M_0,
        )
        assertDistance(
            1000f,
            BridgeProtocol.LengthFormat.METRES_KILOMETRES,
            10,
            BridgeProtocol.FormatCode.KM_1,
        )
        assertDistance(
            99_999f,
            BridgeProtocol.LengthFormat.METRES_KILOMETRES,
            1000,
            BridgeProtocol.FormatCode.KM_1,
        )
        assertDistance(
            100_000f,
            BridgeProtocol.LengthFormat.METRES_KILOMETRES,
            100,
            BridgeProtocol.FormatCode.KM_0,
        )
        assertDistance(
            304.7f,
            BridgeProtocol.LengthFormat.FEET_MILES,
            1000,
            BridgeProtocol.FormatCode.FT_0,
        )
        assertDistance(
            305f,
            BridgeProtocol.LengthFormat.FEET_MILES,
            19,
            BridgeProtocol.FormatCode.MI_2,
        )
        assertDistance(
            914.3f,
            BridgeProtocol.LengthFormat.YARDS_MILES,
            1000,
            BridgeProtocol.FormatCode.YD_0,
        )
        assertDistance(
            915f,
            BridgeProtocol.LengthFormat.YARDS_MILES,
            57,
            BridgeProtocol.FormatCode.MI_2,
        )
        assertDistance(
            1852f,
            BridgeProtocol.LengthFormat.METRES_NAUTICAL_MILES,
            1852,
            BridgeProtocol.FormatCode.M_0,
        )
        assertDistance(
            1852.1f,
            BridgeProtocol.LengthFormat.METRES_NAUTICAL_MILES,
            10,
            BridgeProtocol.FormatCode.NMI_1,
        )
    }

    @Test
    fun totalAndMovingFormatsAreIndependent() {
        val display = format(distanceMetres = 999f, movingDistanceMetres = 1000f)
        assertEquals(BridgeProtocol.FormatCode.M_0, display.distance.format)
        assertEquals(BridgeProtocol.FormatCode.KM_1, display.movingDistance.format)
    }

    @Test
    fun speedAltitudeVerticalSlopeEnergyAndPaceUseConfiguredFamilies() {
        val units =
            BridgeProtocol.UnitPreferences(
                BridgeProtocol.LengthFormat.FEET_MILES,
                BridgeProtocol.AltitudeFormat.FEET,
                BridgeProtocol.SpeedFormat.MILES_PER_HOUR,
                BridgeProtocol.SlopeFormat.DEGREES,
                BridgeProtocol.EnergyFormat.KILOCALORIES,
            )
        val display =
            format(
                currentSpeedMps = 10f,
                averageSpeedMps = 5f,
                maxSpeedMps = 50f,
                currentPaceMinutesPerKilometre = 5f,
                altitudeMetres = -10.0,
                ascentMetres = 100f,
                verticalSpeedMps = -1.25f,
                slopeRatio = 1f,
                energyJoules = 4_185,
                unitPreferences = units,
            )
        assertEquals(DisplayValue(224, BridgeProtocol.FormatCode.MPH_1), display.currentSpeed)
        assertEquals(DisplayValue(112, BridgeProtocol.FormatCode.MPH_1), display.averageSpeed)
        assertEquals(DisplayValue(112, BridgeProtocol.FormatCode.MPH_0), display.maxSpeed)
        assertEquals(DisplayValue(-33, BridgeProtocol.FormatCode.FT_0), display.altitude)
        assertEquals(DisplayValue(328, BridgeProtocol.FormatCode.FT_0), display.ascent)
        assertEquals(DisplayValue(-410, BridgeProtocol.FormatCode.FPS_2), display.verticalSpeed)
        assertEquals(DisplayValue(45, BridgeProtocol.FormatCode.DEGREE_0), display.slope)
        assertEquals(DisplayValue(1, BridgeProtocol.FormatCode.KCAL_0), display.energy)
        assertEquals(483, display.currentPaceSeconds)
        assertEquals(322, display.averagePaceSeconds)
        assertEquals(BridgeProtocol.FormatCode.PER_MI, display.paceFormat)
    }

    @Test
    fun joulesRatioZeroSpeedNegativeAndUnavailableValuesAreHandled() {
        val metric =
            format(
                averageSpeedMps = 0f,
                slopeRatio = -0.123f,
                energyJoules = 12_600,
            )
        assertEquals(DisplayValue(-12, BridgeProtocol.FormatCode.PERCENT_0), metric.slope)
        assertEquals(DisplayValue(13, BridgeProtocol.FormatCode.KJ_0), metric.energy)
        assertEquals(BridgeProtocol.UNAVAILABLE, metric.averagePaceSeconds)
        val nautical =
            format(
                currentPaceMinutesPerKilometre = 5f,
                unitPreferences =
                    BridgeProtocol.UnitPreferences.METRIC.copy(
                        length = BridgeProtocol.LengthFormat.METRES_NAUTICAL_MILES
                    ),
            )
        assertEquals(556, nautical.currentPaceSeconds)
        assertEquals(BridgeProtocol.FormatCode.PER_NMI, nautical.paceFormat)
    }

    @Test
    fun speedPrecisionChangesOnlyAboveOneHundred() {
        val belowBoundary = format(currentSpeedMps = 25f).currentSpeed
        assertEquals(BridgeProtocol.FormatCode.KPH_1, belowBoundary.format)
        val above = format(currentSpeedMps = 28f).currentSpeed
        assertEquals(DisplayValue(101, BridgeProtocol.FormatCode.KPH_0), above)
    }

    private fun assertDistance(
        metres: Float,
        preference: BridgeProtocol.LengthFormat,
        mantissa: Int,
        code: BridgeProtocol.FormatCode,
    ) =
        assertEquals(
            DisplayValue(mantissa, code),
            format(
                    distanceMetres = metres,
                    unitPreferences =
                        BridgeProtocol.UnitPreferences.METRIC.copy(length = preference),
                )
                .distance,
        )

    private fun format(
        distanceMetres: Float? = null,
        movingDistanceMetres: Float? = null,
        currentSpeedMps: Float? = null,
        averageSpeedMps: Float? = null,
        maxSpeedMps: Float? = null,
        currentPaceMinutesPerKilometre: Float? = null,
        altitudeMetres: Double? = null,
        ascentMetres: Float? = null,
        verticalSpeedMps: Float? = null,
        slopeRatio: Float? = null,
        energyJoules: Int? = null,
        unitPreferences: BridgeProtocol.UnitPreferences = BridgeProtocol.UnitPreferences.METRIC,
    ) =
        SnapshotFormatter.format(
            BridgeProtocol.Snapshot(
                BridgeProtocol.RecordingState.RECORDING,
                1,
                distanceMetres = distanceMetres,
                movingDistanceMetres = movingDistanceMetres,
                currentSpeedMps = currentSpeedMps,
                averageSpeedMps = averageSpeedMps,
                maxSpeedMps = maxSpeedMps,
                currentPaceMinutesPerKilometre = currentPaceMinutesPerKilometre,
                altitudeMetres = altitudeMetres,
                ascentMetres = ascentMetres,
                verticalSpeedMps = verticalSpeedMps,
                slopeRatio = slopeRatio,
                energyJoules = energyJoules,
                unitPreferences = unitPreferences,
            )
        )
}
