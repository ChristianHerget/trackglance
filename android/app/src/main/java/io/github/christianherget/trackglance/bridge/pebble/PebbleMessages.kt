package io.github.christianherget.trackglance.bridge.pebble

import io.github.christianherget.trackglance.bridge.BuildConfig
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import io.github.christianherget.trackglance.bridge.protocol.SnapshotFormatter
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

object PebbleMessages {
    private fun i(value: Int) = PebbleDictionaryItem.Int32(value)
    private fun b(value: Int) = PebbleDictionaryItem.Int8(value.toByte())
    private fun u(value: Long) = PebbleDictionaryItem.UInt32(value.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt())
    private fun id(value: Long) = PebbleDictionaryItem.UInt32(BridgeProtocol.requireUnsigned32(value))
    private fun text(value: String) = PebbleDictionaryItem.Text(value)

    fun snapshot(value: BridgeProtocol.Snapshot): PebbleDictionary {
        val sample = if (
            value.state == BridgeProtocol.RecordingState.RECORDING ||
            value.state == BridgeProtocol.RecordingState.PAUSED
        ) value else BridgeProtocol.Snapshot(value.state, value.sampledAtEpochSeconds)
        val display = SnapshotFormatter.format(sample)
        val base = mapOf(
        BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
        BridgeProtocol.Key.APP_VERSION.toUInt() to text(BuildConfig.VERSION_NAME),
        BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to i(BridgeProtocol.MessageType.SNAPSHOT.wire),
        BridgeProtocol.Key.RECORDING_STATE.toUInt() to i(sample.state.wire),
        BridgeProtocol.Key.SAMPLE_EPOCH_SECONDS.toUInt() to u(sample.sampledAtEpochSeconds),
        BridgeProtocol.Key.ELAPSED_SECONDS.toUInt() to u(sample.elapsedSeconds),
        BridgeProtocol.Key.MOVING_SECONDS.toUInt() to i(nonNegativeLong(sample.movingSeconds)),
        BridgeProtocol.Key.DISTANCE_VALUE.toUInt() to i(display.distance.mantissa),
        BridgeProtocol.Key.CURRENT_SPEED_VALUE.toUInt() to i(display.currentSpeed.mantissa),
        BridgeProtocol.Key.AVERAGE_SPEED_VALUE.toUInt() to i(display.averageSpeed.mantissa),
        BridgeProtocol.Key.ALTITUDE_VALUE.toUInt() to i(display.altitude.mantissa),
        BridgeProtocol.Key.ASCENT_VALUE.toUInt() to i(display.ascent.mantissa),
        BridgeProtocol.Key.ALTITUDE_FORMAT.toUInt() to b(display.altitude.format.wire),
        BridgeProtocol.Key.MOVING_DISTANCE_VALUE.toUInt() to i(display.movingDistance.mantissa),
        BridgeProtocol.Key.MAX_SPEED_VALUE.toUInt() to i(display.maxSpeed.mantissa),
        BridgeProtocol.Key.DESCENT_VALUE.toUInt() to i(display.descent.mantissa),
        BridgeProtocol.Key.VERTICAL_SPEED_VALUE.toUInt() to i(display.verticalSpeed.mantissa),
        BridgeProtocol.Key.SLOPE_VALUE.toUInt() to i(display.slope.mantissa),
        BridgeProtocol.Key.AVERAGE_HEART_RATE.toUInt() to i(nonNegativeInt(sample.averageHeartRate)),
        BridgeProtocol.Key.MAX_HEART_RATE.toUInt() to i(nonNegativeInt(sample.maxHeartRate)),
        BridgeProtocol.Key.CURRENT_HEART_RATE.toUInt() to i(nonNegativeInt(sample.currentHeartRate)),
        BridgeProtocol.Key.AVERAGE_CADENCE.toUInt() to i(nonNegativeInt(sample.averageCadence)),
        BridgeProtocol.Key.MAX_CADENCE.toUInt() to i(nonNegativeInt(sample.maxCadence)),
        BridgeProtocol.Key.AVERAGE_POWER.toUInt() to i(nonNegativeInt(sample.averagePower)),
        BridgeProtocol.Key.MAX_POWER.toUInt() to i(nonNegativeInt(sample.maxPower)),
        BridgeProtocol.Key.ENERGY_VALUE.toUInt() to i(display.energy.mantissa),
        BridgeProtocol.Key.DISTANCE_FORMAT.toUInt() to b(display.distance.format.wire),
        BridgeProtocol.Key.MOVING_DISTANCE_FORMAT.toUInt() to b(display.movingDistance.format.wire),
        BridgeProtocol.Key.CURRENT_SPEED_FORMAT.toUInt() to b(display.currentSpeed.format.wire),
        BridgeProtocol.Key.AVERAGE_SPEED_FORMAT.toUInt() to b(display.averageSpeed.format.wire),
        BridgeProtocol.Key.MAX_SPEED_FORMAT.toUInt() to b(display.maxSpeed.format.wire),
        BridgeProtocol.Key.VERTICAL_SPEED_FORMAT.toUInt() to b(display.verticalSpeed.format.wire),
        BridgeProtocol.Key.SLOPE_FORMAT.toUInt() to b(display.slope.format.wire),
        BridgeProtocol.Key.ENERGY_FORMAT.toUInt() to b(display.energy.format.wire),
        BridgeProtocol.Key.CURRENT_PACE_SECONDS.toUInt() to i(display.currentPaceSeconds),
        BridgeProtocol.Key.AVERAGE_PACE_SECONDS.toUInt() to i(display.averagePaceSeconds),
        BridgeProtocol.Key.PACE_FORMAT.toUInt() to b(display.paceFormat.wire),
        )
        return base
    }

    private fun nonNegativeLong(value: Long?): Int = value?.takeIf { it >= 0 }
        ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: BridgeProtocol.UNAVAILABLE

    private fun nonNegativeInt(value: Int?): Int = value?.takeIf { it >= 0 } ?: BridgeProtocol.UNAVAILABLE

    internal fun encodedSize(dictionary: PebbleDictionary): Int = 1 + dictionary.values.sumOf {
        7 + it.size
    }

    fun result(sessionId: Long, commandId: Long, result: BridgeProtocol.Result): PebbleDictionary = mapOf(
        BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
        BridgeProtocol.Key.APP_VERSION.toUInt() to text(BuildConfig.VERSION_NAME),
        BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to i(BridgeProtocol.MessageType.COMMAND_RESULT.wire),
        BridgeProtocol.Key.COMMAND_ID.toUInt() to id(commandId),
        BridgeProtocol.Key.SESSION_ID.toUInt() to id(sessionId),
        BridgeProtocol.Key.RESULT.toUInt() to i(result.wire),
    )

    fun signed32(dictionary: PebbleDictionary, key: Int): Int? =
        (dictionary[key.toUInt()] as? PebbleDictionaryItem.Int32)?.value

    fun unsigned32(dictionary: PebbleDictionary, key: Int): Long? =
        (dictionary[key.toUInt()] as? PebbleDictionaryItem.UInt32)?.value?.toLong()

    fun string(dictionary: PebbleDictionary, key: Int): String? =
        (dictionary[key.toUInt()] as? PebbleDictionaryItem.Text)?.value

    fun profileListChunks(
        profiles: Collection<BridgeProtocol.RecordingProfile>,
        transferId: Int,
        chunkBytes: Int = BridgeProtocol.MAX_CHUNK_BYTES,
    ): List<PebbleDictionary>? {
        require(chunkBytes in 1..BridgeProtocol.MAX_CHUNK_BYTES)
        require(transferId >= 0)
        val transfer = BridgeProtocol.profileTransfer(profiles, chunkBytes) ?: return null
        return profileListChunks(transfer, transferId)
    }

    fun recordingContext(
        state: BridgeProtocol.RecordingState,
        profile: BridgeProtocol.RecordingProfile?,
    ): PebbleDictionary {
        val base = mapOf(
            BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
            BridgeProtocol.Key.APP_VERSION.toUInt() to text(BuildConfig.VERSION_NAME),
            BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to
                i(BridgeProtocol.MessageType.RECORDING_CONTEXT.wire),
            BridgeProtocol.Key.RECORDING_STATE.toUInt() to i(state.wire),
        )
        if (profile == null || !BridgeProtocol.validLocusProfileId(profile.id) ||
            !BridgeProtocol.validLocusProfileName(profile.name)
        ) return base
        return base + mapOf(
            BridgeProtocol.Key.LOCUS_PROFILE_ID.toUInt() to text(profile.id.toString()),
            BridgeProtocol.Key.LOCUS_PROFILE_NAME.toUInt() to text(profile.name),
        )
    }

    internal fun profileListChunks(
        transfer: BridgeProtocol.ProfileTransfer,
        transferId: Int,
    ): List<PebbleDictionary> {
        require(transferId >= 0)
        return transfer.chunks.mapIndexed { index, chunk -> mapOf(
            BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
            BridgeProtocol.Key.APP_VERSION.toUInt() to text(BuildConfig.VERSION_NAME),
            BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to i(BridgeProtocol.MessageType.PROFILE_LIST_CHUNK.wire),
            BridgeProtocol.Key.RESULT.toUInt() to i(transfer.result.wire),
            BridgeProtocol.Key.TRANSFER_ID.toUInt() to i(transferId),
            BridgeProtocol.Key.TRANSFER_GENERATION.toUInt() to
                i(BridgeProtocol.DURABLE_TRANSFER_GENERATION),
            BridgeProtocol.Key.CHUNK_INDEX.toUInt() to i(index),
            BridgeProtocol.Key.CHUNK_COUNT.toUInt() to i(transfer.chunks.size),
            BridgeProtocol.Key.CHUNK_DATA.toUInt() to text(chunk),
        ) }
    }

}
