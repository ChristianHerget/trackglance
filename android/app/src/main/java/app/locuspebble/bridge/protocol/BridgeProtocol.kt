package app.locuspebble.bridge.protocol

import kotlin.math.roundToInt

object BridgeProtocol {
    const val VERSION = 3
    const val UNAVAILABLE = Int.MIN_VALUE
    const val MAX_PROFILE_NAME_LENGTH = 20
    const val MAX_PROFILE_NAME_BYTES = 80
    const val MAX_LOCUS_PROFILE_NAME_BYTES = 255
    const val MAX_WAYPOINT_NAME_BYTES = 120
    const val MAX_PROFILES = 8
    const val MAX_PROFILE_LIST_BYTES = 8191
    const val MAX_PROFILE_LIST_CHUNKS = 103
    const val MAX_CHUNK_BYTES = 80
    const val MAX_WATCH_ID_BYTES = 128
    val APP_UUID = java.util.UUID.fromString("51c8d7cf-4cb2-4ef8-98c9-641706feb250")

    object Key {
        const val VERSION = 0
        const val MESSAGE_TYPE = 1
        const val COMMAND_ID = 2
        const val COMMAND = 3
        const val RESULT = 4
        const val RECORDING_STATE = 5
        const val SAMPLE_EPOCH_SECONDS = 6
        const val SESSION_ID = 7
        const val PROFILE_NAME = 8
        const val LOCUS_PROFILE_NAME = 9
        const val ELAPSED_SECONDS = 10
        const val DISTANCE_METRES = 11
        const val CURRENT_SPEED_CMPS = 12
        const val AVERAGE_SPEED_CMPS = 13
        const val ALTITUDE_DECIMETRES = 14
        const val ASCENT_DECIMETRES = 15
        const val UNIT_SYSTEM = 16
        const val MOVING_SECONDS = 17
        const val MOVING_DISTANCE_METRES = 18
        const val MAX_SPEED_CMPS = 19
        const val DESCENT_DECIMETRES = 20
        const val VERTICAL_SPEED_CMPS = 21
        const val SLOPE_TENTHS_PERCENT = 22
        const val AVERAGE_HEART_RATE = 23
        const val MAX_HEART_RATE = 24
        const val AVERAGE_CADENCE = 25
        const val MAX_CADENCE = 26
        const val AVERAGE_POWER = 27
        const val MAX_POWER = 28
        const val ENERGY_KCAL = 29
        const val CHUNK_INDEX = 30
        const val CHUNK_COUNT = 31
        const val CHUNK_DATA = 32
        const val TRANSFER_ID = 33
        const val LOCUS_MODE = 34
        const val APP_VERSION = 35
        const val WAYPOINT_NAME = 36
        const val CURRENT_HEART_RATE = 37
        const val HEART_RATE_SEQUENCE = 38
    }

    enum class MessageType(val wire: Int) {
        SNAPSHOT(1), COMMAND(2), COMMAND_RESULT(3), REQUEST_SNAPSHOT(4),
        CONFIG_CHUNK(5), PROFILE_LIST_CHUNK(6), REQUEST_PROFILE_LIST(7), HEART_RATE_SAMPLE(8),
        CONFIG_RESULT(9),
    }
    enum class RecordingState(val wire: Int) { STOPPED(0), RECORDING(1), PAUSED(2), UNAVAILABLE(3) }
    enum class Command(val wire: Int) {
        START(1), PAUSE_RESUME(2), STOP_SAVE(3), ADD_WAYPOINT(4), ADD_WAYPOINT_WITH_NOTE(5),
    }
    enum class Result(val wire: Int) {
        OK(0), INVALID_STATE(1), LOCUS_UNAVAILABLE(2), FAILED(3), INVALID_PROFILE(4), PROFILE_NOT_FOUND(5),
        INVALID_WAYPOINT_NAME(6), CONFIG_QUEUED(7), INVALID_CONFIG(8), STORAGE_FAILED(9),
    }
    enum class UnitSystem(val wire: Int) { METRIC(0), IMPERIAL(1) }
    enum class Metric(val wire: Int) {
        ELAPSED_TIME(1), MOVING_TIME(2), TOTAL_DISTANCE(3), MOVING_DISTANCE(4), CURRENT_SPEED(5),
        AVERAGE_SPEED(6), MAX_SPEED(7), CURRENT_PACE(8), AVERAGE_PACE(9), ALTITUDE(10), ASCENT(11),
        DESCENT(12), VERTICAL_SPEED(13), SLOPE(14), AVERAGE_HEART_RATE(15), MAX_HEART_RATE(16),
        AVERAGE_CADENCE(17), MAX_CADENCE(18), AVERAGE_POWER(19), MAX_POWER(20), ENERGY(21),
        CURRENT_HEART_RATE(22),
    }

    fun validProfileName(name: String?): Boolean = validText(
        name,
        maxBytes = MAX_PROFILE_NAME_BYTES,
        maxCodePoints = MAX_PROFILE_NAME_LENGTH,
        rejectPipe = true,
    )

    fun validLocusProfileName(name: String?): Boolean = validText(
        name,
        maxBytes = MAX_LOCUS_PROFILE_NAME_BYTES,
        rejectPipe = true,
    )

    fun validWaypointName(name: String?): Boolean = validText(
        name,
        maxBytes = MAX_WAYPOINT_NAME_BYTES,
        rejectPipe = false,
    )

    fun validWatchId(value: String?): Boolean = validText(
        value,
        maxBytes = MAX_WATCH_ID_BYTES,
        rejectPipe = false,
    )

    private fun validText(
        value: String?,
        maxBytes: Int,
        maxCodePoints: Int? = null,
        rejectPipe: Boolean,
    ): Boolean {
        if (value.isNullOrEmpty()) return false
        var index = 0
        var count = 0
        var nonWhitespace = false
        while (index < value.length) {
            val first = value[index]
            val codePoint = when {
                first.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                    Character.toCodePoint(first, value[index + 1]).also { index++ }
                }
                first.isLowSurrogate() -> return false
                else -> first.code
            }
            if (codePoint < 0x20 || codePoint == 0x7f || (rejectPipe && codePoint == '|'.code)) return false
            if (!unicodeWhitespace(codePoint)) nonWhitespace = true
            count++
            if (maxCodePoints != null && count > maxCodePoints) return false
            index++
        }
        return nonWhitespace && value.toByteArray(Charsets.UTF_8).size <= maxBytes
    }

    private fun unicodeWhitespace(codePoint: Int): Boolean = codePoint == 0x20 ||
        codePoint == 0x85 || codePoint == 0xa0 || codePoint == 0x1680 ||
        codePoint in 0x2000..0x200a || codePoint == 0x2028 || codePoint == 0x2029 ||
        codePoint == 0x202f || codePoint == 0x205f || codePoint == 0x3000 || codePoint == 0xfeff

    fun autoMatchProfile(wanted: String, installed: List<String>): String? {
        installed.firstOrNull { it == wanted }?.let { return it }
        return installed.filter { it.equals(wanted, ignoreCase = true) }.singleOrNull()
    }

    fun profileListPayload(names: List<String>): String? {
        if (names.any { !validLocusProfileName(it) } || names.distinct().size != names.size) {
            return null
        }
        val payload = names.joinToString("\n")
        return payload.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_LIST_BYTES }
    }

    data class ProfileTransfer internal constructor(val result: Result, val chunks: List<String>)

    fun profileTransfer(names: List<String>, chunkBytes: Int = MAX_CHUNK_BYTES): ProfileTransfer? {
        require(chunkBytes in 1..MAX_CHUNK_BYTES)
        val payload = profileListPayload(names)
        val candidateChunks = payload?.let {
            try {
                utf8Chunks(it, chunkBytes)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        if (candidateChunks == null || candidateChunks.size > MAX_PROFILE_LIST_CHUNKS) {
            return null
        }
        return ProfileTransfer(profileListResult(payload), candidateChunks)
    }

    fun requireUnsigned32(value: Long): UInt {
        require(value in 0..UInt.MAX_VALUE.toLong())
        return value.toUInt()
    }

    fun profileListResult(payload: String): Result =
        if (payload.isEmpty()) Result.FAILED else Result.OK

    fun utf8Chunks(value: String, maxBytes: Int): List<String> {
        require(maxBytes > 0)
        if (value.isEmpty()) return listOf("")
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var bytes = 0
        value.codePoints().forEach { codePoint ->
            require(codePoint !in 0xd800..0xdfff) { "Invalid UTF-16 surrogate" }
            val character = String(Character.toChars(codePoint))
            val size = character.toByteArray(Charsets.UTF_8).size
            require(size <= maxBytes) { "A UTF-8 code point exceeds the chunk byte limit" }
            if (bytes + size > maxBytes && current.isNotEmpty()) {
                result += current.toString()
                current.clear()
                bytes = 0
            }
            current.append(character)
            bytes += size
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    data class Snapshot(
        val state: RecordingState,
        val sampledAtEpochSeconds: Long,
        val elapsedSeconds: Long = 0,
        val movingSeconds: Long? = null,
        val distanceMetres: Float? = null,
        val movingDistanceMetres: Float? = null,
        val currentSpeedMps: Float? = null,
        val averageSpeedMps: Float? = null,
        val maxSpeedMps: Float? = null,
        val altitudeMetres: Double? = null,
        val ascentMetres: Float? = null,
        val descentMetres: Float? = null,
        val verticalSpeedMps: Float? = null,
        val slopePercent: Float? = null,
        val averageHeartRate: Int? = null,
        val currentHeartRate: Int? = null,
        val maxHeartRate: Int? = null,
        val averageCadence: Int? = null,
        val maxCadence: Int? = null,
        val averagePower: Int? = null,
        val maxPower: Int? = null,
        val energyKcal: Int? = null,
        val locusProfileName: String? = null,
        val unitSystem: UnitSystem = UnitSystem.METRIC,
    ) {
        private fun scaled(value: Double?, scale: Int, allowNegative: Boolean): Int {
            if (value == null || !value.isFinite() || (!allowNegative && value < 0.0)) return UNAVAILABLE
            val scaled = value * scale
            return when {
                scaled >= Int.MAX_VALUE.toDouble() -> Int.MAX_VALUE
                scaled <= MIN_AVAILABLE.toDouble() -> MIN_AVAILABLE
                else -> scaled.roundToInt().coerceAtLeast(MIN_AVAILABLE)
            }
        }

        private fun nonNegative(value: Float?, scale: Int = 1) =
            scaled(value?.toDouble(), scale, allowNegative = false)

        fun distanceWire() = nonNegative(distanceMetres)
        fun movingDistanceWire() = nonNegative(movingDistanceMetres)
        fun currentSpeedWire() = nonNegative(currentSpeedMps, 100)
        fun averageSpeedWire() = nonNegative(averageSpeedMps, 100)
        fun maxSpeedWire() = nonNegative(maxSpeedMps, 100)
        fun altitudeWire() = scaled(altitudeMetres, 10, allowNegative = true)
        fun ascentWire() = nonNegative(ascentMetres, 10)
        fun descentWire() = nonNegative(descentMetres, 10)
        fun verticalSpeedWire() = scaled(verticalSpeedMps?.toDouble(), 100, allowNegative = true)
        fun slopeWire() = scaled(slopePercent?.toDouble(), 10, allowNegative = true)
        fun movingSecondsWire() = movingSeconds?.takeIf { it >= 0 }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: UNAVAILABLE
        fun integerWire(value: Int?) = value?.takeIf { it >= 0 } ?: UNAVAILABLE

        private companion object {
            const val MIN_AVAILABLE = Int.MIN_VALUE + 1
        }
    }
}
