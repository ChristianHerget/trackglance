package io.github.christianherget.trackglance.bridge.protocol

import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.roundToLong

data class DisplayValue(val mantissa: Int, val format: BridgeProtocol.FormatCode)

data class DisplaySnapshot(
    val distance: DisplayValue,
    val movingDistance: DisplayValue,
    val currentSpeed: DisplayValue,
    val averageSpeed: DisplayValue,
    val maxSpeed: DisplayValue,
    val altitude: DisplayValue,
    val ascent: DisplayValue,
    val descent: DisplayValue,
    val verticalSpeed: DisplayValue,
    val slope: DisplayValue,
    val energy: DisplayValue,
    val currentPaceSeconds: Int,
    val averagePaceSeconds: Int,
    val paceFormat: BridgeProtocol.FormatCode,
)

object SnapshotFormatter {
    private const val METRES_PER_MILE = 1609.344
    private const val METRES_PER_NAUTICAL_MILE = 1852.0
    private const val FEET_PER_METRE = 3.2808
    private const val YARDS_PER_METRE = 1.0936
    private const val JOULES_PER_KILOCALORIE = 4185.0
    private const val MIN_AVAILABLE = Int.MIN_VALUE + 1

    fun format(snapshot: BridgeProtocol.Snapshot): DisplaySnapshot {
        val units = snapshot.unitPreferences
        val altitudeFormat = when (units.altitude) {
            BridgeProtocol.AltitudeFormat.METRES -> BridgeProtocol.FormatCode.M_0
            BridgeProtocol.AltitudeFormat.FEET -> BridgeProtocol.FormatCode.FT_0
        }
        val verticalFormat = when (units.altitude) {
            BridgeProtocol.AltitudeFormat.METRES -> BridgeProtocol.FormatCode.MPS_2
            BridgeProtocol.AltitudeFormat.FEET -> BridgeProtocol.FormatCode.FPS_2
        }
        val pace = paceDescriptor(units.length)
        return DisplaySnapshot(
            distance = distance(snapshot.distanceMetres, units.length),
            movingDistance = distance(snapshot.movingDistanceMetres, units.length),
            currentSpeed = speed(snapshot.currentSpeedMps, units.speed),
            averageSpeed = speed(snapshot.averageSpeedMps, units.speed),
            maxSpeed = speed(snapshot.maxSpeedMps, units.speed),
            altitude = fixed(
                snapshot.altitudeMetres?.times(if (units.altitude == BridgeProtocol.AltitudeFormat.FEET) FEET_PER_METRE else 1.0),
                altitudeFormat,
                allowNegative = true,
            ),
            ascent = fixed(
                snapshot.ascentMetres?.toDouble()?.times(if (units.altitude == BridgeProtocol.AltitudeFormat.FEET) FEET_PER_METRE else 1.0),
                altitudeFormat,
            ),
            descent = fixed(
                snapshot.descentMetres?.toDouble()?.times(if (units.altitude == BridgeProtocol.AltitudeFormat.FEET) FEET_PER_METRE else 1.0),
                altitudeFormat,
            ),
            verticalSpeed = fixed(
                snapshot.verticalSpeedMps?.toDouble()?.times(if (units.altitude == BridgeProtocol.AltitudeFormat.FEET) FEET_PER_METRE else 1.0),
                verticalFormat,
                allowNegative = true,
            ),
            slope = slope(snapshot.slopeRatio, units.slope),
            energy = energy(snapshot.energyJoules, units.energy),
            currentPaceSeconds = currentPace(snapshot.currentPaceMinutesPerKilometre, pace.metres),
            averagePaceSeconds = averagePace(snapshot.averageSpeedMps, pace.metres),
            paceFormat = pace.format,
        )
    }

    private fun distance(value: Float?, preference: BridgeProtocol.LengthFormat): DisplayValue {
        val metres = value?.toDouble()
        val descriptor = when (preference) {
            BridgeProtocol.LengthFormat.METRES -> Descriptor(metres, BridgeProtocol.FormatCode.M_0)
            BridgeProtocol.LengthFormat.METRES_KILOMETRES -> if (metres != null && metres >= 1000.0) {
                val kilometres = metres / 1000.0
                Descriptor(kilometres, if (kilometres >= 100.0) BridgeProtocol.FormatCode.KM_0 else BridgeProtocol.FormatCode.KM_1)
            } else Descriptor(metres, BridgeProtocol.FormatCode.M_0)
            BridgeProtocol.LengthFormat.FEET -> Descriptor(metres?.times(FEET_PER_METRE), BridgeProtocol.FormatCode.FT_0)
            BridgeProtocol.LengthFormat.FEET_MILES -> imperialDistance(metres, FEET_PER_METRE, BridgeProtocol.FormatCode.FT_0)
            BridgeProtocol.LengthFormat.YARDS -> Descriptor(metres?.times(YARDS_PER_METRE), BridgeProtocol.FormatCode.YD_0)
            BridgeProtocol.LengthFormat.YARDS_MILES -> imperialDistance(metres, YARDS_PER_METRE, BridgeProtocol.FormatCode.YD_0)
            BridgeProtocol.LengthFormat.METRES_NAUTICAL_MILES -> if (metres != null && metres > METRES_PER_NAUTICAL_MILE) {
                val nauticalMiles = metres / METRES_PER_NAUTICAL_MILE
                Descriptor(nauticalMiles, if (nauticalMiles >= 100.0) BridgeProtocol.FormatCode.NMI_0 else BridgeProtocol.FormatCode.NMI_1)
            } else Descriptor(metres, BridgeProtocol.FormatCode.M_0)
        }
        return fixed(descriptor.value, descriptor.format)
    }

    private fun imperialDistance(
        metres: Double?,
        smallUnitFactor: Double,
        smallFormat: BridgeProtocol.FormatCode,
    ): Descriptor {
        val small = metres?.times(smallUnitFactor)
        if (metres == null || small == null || small < 1000.0) return Descriptor(small, smallFormat)
        val miles = metres / METRES_PER_MILE
        val format = when {
            miles >= 100.0 -> BridgeProtocol.FormatCode.MI_0
            miles >= 1.0 -> BridgeProtocol.FormatCode.MI_1
            else -> BridgeProtocol.FormatCode.MI_2
        }
        return Descriptor(miles, format)
    }

    private fun speed(value: Float?, preference: BridgeProtocol.SpeedFormat): DisplayValue {
        val converted = value?.toDouble()?.times(when (preference) {
            BridgeProtocol.SpeedFormat.KILOMETRES_PER_HOUR -> 3.6
            BridgeProtocol.SpeedFormat.MILES_PER_HOUR -> 2.237
            BridgeProtocol.SpeedFormat.NAUTICAL_MILES_PER_HOUR,
            BridgeProtocol.SpeedFormat.KNOTS,
            -> 1.9438444924406046
        })
        val oneDecimal = converted == null || converted <= 100.0
        val format = when (preference) {
            BridgeProtocol.SpeedFormat.KILOMETRES_PER_HOUR -> if (oneDecimal) BridgeProtocol.FormatCode.KPH_1 else BridgeProtocol.FormatCode.KPH_0
            BridgeProtocol.SpeedFormat.MILES_PER_HOUR -> if (oneDecimal) BridgeProtocol.FormatCode.MPH_1 else BridgeProtocol.FormatCode.MPH_0
            BridgeProtocol.SpeedFormat.NAUTICAL_MILES_PER_HOUR -> if (oneDecimal) BridgeProtocol.FormatCode.NMIH_1 else BridgeProtocol.FormatCode.NMIH_0
            BridgeProtocol.SpeedFormat.KNOTS -> if (oneDecimal) BridgeProtocol.FormatCode.KNOT_1 else BridgeProtocol.FormatCode.KNOT_0
        }
        return fixed(converted, format)
    }

    private fun slope(value: Float?, preference: BridgeProtocol.SlopeFormat): DisplayValue = when (preference) {
        BridgeProtocol.SlopeFormat.PERCENT -> fixed(value?.toDouble()?.times(100.0), BridgeProtocol.FormatCode.PERCENT_0, allowNegative = true)
        BridgeProtocol.SlopeFormat.DEGREES -> fixed(value?.let { Math.toDegrees(atan(it.toDouble())) }, BridgeProtocol.FormatCode.DEGREE_0, allowNegative = true)
    }

    private fun energy(value: Int?, preference: BridgeProtocol.EnergyFormat): DisplayValue = when (preference) {
        BridgeProtocol.EnergyFormat.KILOJOULES -> fixed(value?.div(1000.0), BridgeProtocol.FormatCode.KJ_0)
        BridgeProtocol.EnergyFormat.KILOCALORIES -> fixed(value?.div(JOULES_PER_KILOCALORIE), BridgeProtocol.FormatCode.KCAL_0)
    }

    private fun currentPace(minutesPerKilometre: Float?, metresPerUnit: Double): Int {
        val seconds = minutesPerKilometre?.toDouble()?.times(60.0)?.times(metresPerUnit / 1000.0)
        return wholeSeconds(seconds)
    }

    private fun averagePace(speedMetresPerSecond: Float?, metresPerUnit: Double): Int {
        val speed = speedMetresPerSecond?.toDouble()
        return wholeSeconds(if (speed != null && speed > 0.0) metresPerUnit / speed else null)
    }

    private fun wholeSeconds(value: Double?): Int = scaled(value, 0, allowNegative = false)
        .takeIf { it > 0 } ?: BridgeProtocol.UNAVAILABLE

    private fun fixed(value: Double?, format: BridgeProtocol.FormatCode, allowNegative: Boolean = false) =
        DisplayValue(scaled(value, format.decimals, allowNegative), format)

    private fun scaled(value: Double?, decimals: Int, allowNegative: Boolean): Int {
        if (value == null || !value.isFinite() || (!allowNegative && value < 0.0)) return BridgeProtocol.UNAVAILABLE
        val scaled = value * 10.0.pow(decimals)
        return when {
            scaled >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
            scaled <= MIN_AVAILABLE.toDouble() -> MIN_AVAILABLE
            else -> scaled.roundToLong().toInt().coerceAtLeast(MIN_AVAILABLE)
        }
    }

    private fun paceDescriptor(length: BridgeProtocol.LengthFormat): PaceDescriptor = when (length) {
        BridgeProtocol.LengthFormat.METRES,
        BridgeProtocol.LengthFormat.METRES_KILOMETRES,
        -> PaceDescriptor(1000.0, BridgeProtocol.FormatCode.PER_KM)
        BridgeProtocol.LengthFormat.FEET,
        BridgeProtocol.LengthFormat.FEET_MILES,
        BridgeProtocol.LengthFormat.YARDS,
        BridgeProtocol.LengthFormat.YARDS_MILES,
        -> PaceDescriptor(METRES_PER_MILE, BridgeProtocol.FormatCode.PER_MI)
        BridgeProtocol.LengthFormat.METRES_NAUTICAL_MILES -> PaceDescriptor(METRES_PER_NAUTICAL_MILE, BridgeProtocol.FormatCode.PER_NMI)
    }

    private data class Descriptor(val value: Double?, val format: BridgeProtocol.FormatCode)
    private data class PaceDescriptor(val metres: Double, val format: BridgeProtocol.FormatCode)
}
