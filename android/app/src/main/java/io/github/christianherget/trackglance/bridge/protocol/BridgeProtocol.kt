package io.github.christianherget.trackglance.bridge.protocol

object BridgeProtocol {
    const val VERSION = 4
    const val UNAVAILABLE = Int.MIN_VALUE
    const val MAX_PROFILE_NAME_LENGTH = 20
    const val MAX_PROFILE_NAME_BYTES = 80
    const val MAX_LOCUS_PROFILE_NAME_BYTES = 255
    const val MAX_WAYPOINT_NAME_BYTES = 120
    const val MAX_ACTIVITY_PAGES = 4
    const val MAX_PROFILE_LIST_BYTES = 8191
    const val MAX_PROFILE_LIST_CHUNKS = 103
    const val MAX_CHUNK_BYTES = 80
    const val MAX_WATCH_ID_BYTES = 128
    const val MAX_APP_MESSAGE_BYTES = 512
    const val DELIVERY_MAX_ATTEMPTS = 3
    const val DELIVERY_ATTEMPT_TIMEOUT_MILLIS = 10_000L
    const val DELIVERY_RETRY_BASE_MILLIS = 100L
    const val COMMAND_CONFIRMATION_MILLIS = 1_500L
    const val RECEIVER_TRANSFER_TIMEOUT_SECONDS = 45
    const val RECEIVER_COMMAND_RESULT_TIMEOUT_SECONDS = 120
    const val TRANSFER_SERIAL_MASK = 0x7fff_ffffL
    const val TRANSFER_SERIAL_HALF_RANGE = 0x4000_0000L
    const val DURABLE_TRANSFER_GENERATION = 1
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
        const val DISTANCE_VALUE = 11
        const val CURRENT_SPEED_VALUE = 12
        const val AVERAGE_SPEED_VALUE = 13
        const val ALTITUDE_VALUE = 14
        const val ASCENT_VALUE = 15
        const val ALTITUDE_FORMAT = 16
        const val MOVING_SECONDS = 17
        const val MOVING_DISTANCE_VALUE = 18
        const val MAX_SPEED_VALUE = 19
        const val DESCENT_VALUE = 20
        const val VERTICAL_SPEED_VALUE = 21
        const val SLOPE_VALUE = 22
        const val AVERAGE_HEART_RATE = 23
        const val MAX_HEART_RATE = 24
        const val AVERAGE_CADENCE = 25
        const val MAX_CADENCE = 26
        const val AVERAGE_POWER = 27
        const val MAX_POWER = 28
        const val ENERGY_VALUE = 29
        const val CHUNK_INDEX = 30
        const val CHUNK_COUNT = 31
        const val CHUNK_DATA = 32
        const val TRANSFER_ID = 33
        const val LOCUS_MODE = 34
        const val APP_VERSION = 35
        const val WAYPOINT_NAME = 36
        const val CURRENT_HEART_RATE = 37
        const val HEART_RATE_SEQUENCE = 38
        const val TRANSFER_GENERATION = 39
        const val DISTANCE_FORMAT = 40
        const val MOVING_DISTANCE_FORMAT = 41
        const val CURRENT_SPEED_FORMAT = 42
        const val AVERAGE_SPEED_FORMAT = 43
        const val MAX_SPEED_FORMAT = 44
        const val VERTICAL_SPEED_FORMAT = 45
        const val SLOPE_FORMAT = 46
        const val ENERGY_FORMAT = 47
        const val CURRENT_PACE_SECONDS = 48
        const val AVERAGE_PACE_SECONDS = 49
        const val PACE_FORMAT = 50
        const val LOCUS_PROFILE_ID = 51
        const val CONFIG_FINGERPRINT_A = 52
        const val CONFIG_FINGERPRINT_B = 53
    }

    enum class MessageType(val wire: Int) {
        SNAPSHOT(1), COMMAND(2), COMMAND_RESULT(3), REQUEST_SNAPSHOT(4),
        CONFIG_CHUNK(5), PROFILE_LIST_CHUNK(6), REQUEST_PROFILE_LIST(7), HEART_RATE_SAMPLE(8),
        CONFIG_RESULT(9), RECORDING_CONTEXT(10), REQUEST_RUNTIME_CONFIG(11),
    }
    enum class RecordingState(val wire: Int) { STOPPED(0), RECORDING(1), PAUSED(2), UNAVAILABLE(3) }
    enum class Command(val wire: Int) {
        START(1), PAUSE_RESUME(2), STOP_SAVE(3), ADD_WAYPOINT(4), ADD_WAYPOINT_WITH_NOTE(5),
    }

    data class RecordingProfile(val id: Long, val name: String)
    enum class Result(val wire: Int) {
        OK(0), INVALID_STATE(1), LOCUS_UNAVAILABLE(2), FAILED(3), INVALID_PROFILE(4), PROFILE_NOT_FOUND(5),
        INVALID_WAYPOINT_NAME(6), CONFIG_QUEUED(7), INVALID_CONFIG(8), STORAGE_FAILED(9),
    }
    enum class FormatCode(val wire: Int, val decimals: Int, val suffix: String) {
        M_0(0, 0, " m"),
        KM_1(1, 1, " km"),
        KM_0(2, 0, " km"),
        FT_0(3, 0, " ft"),
        YD_0(4, 0, " yd"),
        MI_2(5, 2, " mi"),
        MI_1(6, 1, " mi"),
        MI_0(7, 0, " mi"),
        NMI_1(8, 1, " nmi"),
        NMI_0(9, 0, " nmi"),
        KPH_1(10, 1, " km/h"),
        KPH_0(11, 0, " km/h"),
        MPH_1(12, 1, " mi/h"),
        MPH_0(13, 0, " mi/h"),
        NMIH_1(14, 1, " nmi/h"),
        NMIH_0(15, 0, " nmi/h"),
        KNOT_1(16, 1, " kn"),
        KNOT_0(17, 0, " kn"),
        MPS_2(18, 2, " m/s"),
        FPS_2(19, 2, " ft/s"),
        PERCENT_0(20, 0, "%"),
        DEGREE_0(21, 0, "°"),
        KJ_0(22, 0, " kJ"),
        KCAL_0(23, 0, " kcal"),
        PER_KM(24, 0, " /km"),
        PER_MI(25, 0, " /mi"),
        PER_NMI(26, 0, " /nmi"),
        ;

        companion object {
            fun fromWire(wire: Int): FormatCode? = entries.firstOrNull { it.wire == wire }
        }
    }
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

    fun validLocusProfileId(id: Long): Boolean = true

    fun validLocusProfileId(id: String?): Boolean = id != null &&
        (id == "0" || id.matches(Regex("-?[1-9][0-9]{0,18}"))) && id.toLongOrNull() != null

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

    fun profileListPayload(profiles: Collection<RecordingProfile>): String? {
        if (profiles.any { !validLocusProfileId(it.id) || !validLocusProfileName(it.name) } ||
            profiles.map { it.id }.distinct().size != profiles.size
        ) {
            return null
        }
        val payload = profiles.joinToString("\n") { "${it.id}|${it.name}" }
        return payload.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_LIST_BYTES }
    }

    data class ProfileTransfer internal constructor(val result: Result, val chunks: List<String>)

    fun profileTransfer(
        profiles: Collection<RecordingProfile>,
        chunkBytes: Int = MAX_CHUNK_BYTES,
    ): ProfileTransfer? {
        require(chunkBytes in 1..MAX_CHUNK_BYTES)
        val payload = profileListPayload(profiles)
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
        val currentPaceMinutesPerKilometre: Float? = null,
        val altitudeMetres: Double? = null,
        val ascentMetres: Float? = null,
        val descentMetres: Float? = null,
        val verticalSpeedMps: Float? = null,
        val slopeRatio: Float? = null,
        val averageHeartRate: Int? = null,
        val currentHeartRate: Int? = null,
        val maxHeartRate: Int? = null,
        val averageCadence: Int? = null,
        val maxCadence: Int? = null,
        val averagePower: Int? = null,
        val maxPower: Int? = null,
        val energyJoules: Int? = null,
        val locusProfileName: String? = null,
        val unitPreferences: UnitPreferences = UnitPreferences.METRIC,
    )

    enum class LengthFormat { METRES, METRES_KILOMETRES, FEET, FEET_MILES, YARDS, YARDS_MILES, METRES_NAUTICAL_MILES }
    enum class AltitudeFormat { METRES, FEET }
    enum class SpeedFormat { KILOMETRES_PER_HOUR, MILES_PER_HOUR, NAUTICAL_MILES_PER_HOUR, KNOTS }
    enum class SlopeFormat { PERCENT, DEGREES }
    enum class EnergyFormat { KILOJOULES, KILOCALORIES }

    data class UnitPreferences(
        val length: LengthFormat,
        val altitude: AltitudeFormat,
        val speed: SpeedFormat,
        val slope: SlopeFormat,
        val energy: EnergyFormat,
    ) {
        companion object {
            val METRIC = UnitPreferences(
                LengthFormat.METRES_KILOMETRES,
                AltitudeFormat.METRES,
                SpeedFormat.KILOMETRES_PER_HOUR,
                SlopeFormat.PERCENT,
                EnergyFormat.KILOJOULES,
            )
        }
    }
}
