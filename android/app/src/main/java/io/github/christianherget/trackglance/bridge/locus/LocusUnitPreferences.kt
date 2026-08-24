package io.github.christianherget.trackglance.bridge.locus

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol

data class RawLocusUnitPreferences(
    val length: Int,
    val altitude: Int,
    val speed: Int,
    val slope: Int,
    val energy: Int,
)

object LocusUnitPreferenceMapper {
    fun map(raw: RawLocusUnitPreferences): BridgeProtocol.UnitPreferences = BridgeProtocol.UnitPreferences(
        length = BridgeProtocol.LengthFormat.entries.getOrNull(raw.length)
            ?: BridgeProtocol.UnitPreferences.METRIC.length,
        altitude = BridgeProtocol.AltitudeFormat.entries.getOrNull(raw.altitude)
            ?: BridgeProtocol.UnitPreferences.METRIC.altitude,
        speed = BridgeProtocol.SpeedFormat.entries.getOrNull(raw.speed)
            ?: BridgeProtocol.UnitPreferences.METRIC.speed,
        slope = BridgeProtocol.SlopeFormat.entries.getOrNull(raw.slope)
            ?: BridgeProtocol.UnitPreferences.METRIC.slope,
        energy = BridgeProtocol.EnergyFormat.entries.getOrNull(raw.energy)
            ?: BridgeProtocol.UnitPreferences.METRIC.energy,
    )
}

class LocusUnitPreferencesCache(
    private val read: () -> RawLocusUnitPreferences?,
    private val monotonicMillis: () -> Long,
    private val refreshMillis: Long = 60_000L,
) {
    private var preferences = BridgeProtocol.UnitPreferences.METRIC
    private var attempted = false
    private var lastAttemptMillis = 0L

    @Synchronized
    fun current(): BridgeProtocol.UnitPreferences {
        val now = monotonicMillis()
        if (!attempted || now - lastAttemptMillis >= refreshMillis || now < lastAttemptMillis) {
            attempted = true
            lastAttemptMillis = now
            try {
                read()?.let { preferences = LocusUnitPreferenceMapper.map(it) }
            } catch (_: Exception) {
                // Locus queries are external IPC. Keep the last complete usable preference set.
            }
        }
        return preferences
    }
}
