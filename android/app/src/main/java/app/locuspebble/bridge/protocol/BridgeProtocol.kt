package app.locuspebble.bridge.protocol

import kotlin.math.roundToInt

object BridgeProtocol {
    const val VERSION = 1
    val APP_UUID = java.util.UUID.fromString("51c8d7cf-4cb2-4ef8-98c9-641706feb250")

    object Key {
        const val VERSION = 0
        const val MESSAGE_TYPE = 1
        const val COMMAND_ID = 2
        const val COMMAND = 3
        const val RESULT = 4
        const val RECORDING_STATE = 5
        const val SAMPLE_EPOCH_SECONDS = 6
        const val ELAPSED_SECONDS = 10
        const val DISTANCE_METRES = 11
        const val CURRENT_SPEED_CMPS = 12
        const val AVERAGE_SPEED_CMPS = 13
        const val ALTITUDE_DECIMETRES = 14
        const val ASCENT_DECIMETRES = 15
        const val UNIT_SYSTEM = 16
    }

    enum class MessageType(val wire: Int) { SNAPSHOT(1), COMMAND(2), COMMAND_RESULT(3), REQUEST_SNAPSHOT(4) }
    enum class RecordingState(val wire: Int) { STOPPED(0), RECORDING(1), PAUSED(2), UNAVAILABLE(3) }
    enum class Command(val wire: Int) { START(1), PAUSE_RESUME(2), STOP_SAVE(3), ADD_WAYPOINT(4) }
    enum class Result(val wire: Int) { OK(0), INVALID_STATE(1), LOCUS_UNAVAILABLE(2), FAILED(3) }
    enum class UnitSystem(val wire: Int) { METRIC(0), IMPERIAL(1) }

    data class Snapshot(
        val state: RecordingState,
        val sampledAtEpochSeconds: Long,
        val elapsedSeconds: Long = 0,
        val distanceMetres: Float = 0f,
        val currentSpeedMps: Float = 0f,
        val averageSpeedMps: Float = 0f,
        val altitudeMetres: Double = 0.0,
        val ascentMetres: Float = 0f,
        val unitSystem: UnitSystem = UnitSystem.METRIC,
    ) {
        fun distanceWire() = distanceMetres.coerceAtLeast(0f).roundToInt()
        fun currentSpeedWire() = (currentSpeedMps.coerceAtLeast(0f) * 100).roundToInt()
        fun averageSpeedWire() = (averageSpeedMps.coerceAtLeast(0f) * 100).roundToInt()
        fun altitudeWire() = (altitudeMetres * 10).roundToInt()
        fun ascentWire() = (ascentMetres.coerceAtLeast(0f) * 10).roundToInt()
    }
}

