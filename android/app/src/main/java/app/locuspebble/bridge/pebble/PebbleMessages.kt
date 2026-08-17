package app.locuspebble.bridge.pebble

import app.locuspebble.bridge.BuildConfig
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

object PebbleMessages {
    private fun i(value: Int) = PebbleDictionaryItem.Int32(value)
    private fun u(value: Long) = PebbleDictionaryItem.UInt32(value.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt())
    private fun id(value: Long) = PebbleDictionaryItem.UInt32(BridgeProtocol.requireUnsigned32(value))
    private fun text(value: String) = PebbleDictionaryItem.Text(value)

    fun snapshot(value: BridgeProtocol.Snapshot): PebbleDictionary = mapOf(
        BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
        BridgeProtocol.Key.APP_VERSION.toUInt() to text(BuildConfig.VERSION_NAME),
        BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to i(BridgeProtocol.MessageType.SNAPSHOT.wire),
        BridgeProtocol.Key.RECORDING_STATE.toUInt() to i(value.state.wire),
        BridgeProtocol.Key.SAMPLE_EPOCH_SECONDS.toUInt() to u(value.sampledAtEpochSeconds),
        BridgeProtocol.Key.ELAPSED_SECONDS.toUInt() to u(value.elapsedSeconds),
        BridgeProtocol.Key.MOVING_SECONDS.toUInt() to i(value.movingSecondsWire()),
        BridgeProtocol.Key.DISTANCE_METRES.toUInt() to i(value.distanceWire()),
        BridgeProtocol.Key.CURRENT_SPEED_CMPS.toUInt() to i(value.currentSpeedWire()),
        BridgeProtocol.Key.AVERAGE_SPEED_CMPS.toUInt() to i(value.averageSpeedWire()),
        BridgeProtocol.Key.ALTITUDE_DECIMETRES.toUInt() to i(value.altitudeWire()),
        BridgeProtocol.Key.ASCENT_DECIMETRES.toUInt() to i(value.ascentWire()),
        BridgeProtocol.Key.MOVING_DISTANCE_METRES.toUInt() to i(value.movingDistanceWire()),
        BridgeProtocol.Key.MAX_SPEED_CMPS.toUInt() to i(value.maxSpeedWire()),
        BridgeProtocol.Key.DESCENT_DECIMETRES.toUInt() to i(value.descentWire()),
        BridgeProtocol.Key.VERTICAL_SPEED_CMPS.toUInt() to i(value.verticalSpeedWire()),
        BridgeProtocol.Key.SLOPE_TENTHS_PERCENT.toUInt() to i(value.slopeWire()),
        BridgeProtocol.Key.AVERAGE_HEART_RATE.toUInt() to i(value.integerWire(value.averageHeartRate)),
        BridgeProtocol.Key.MAX_HEART_RATE.toUInt() to i(value.integerWire(value.maxHeartRate)),
        BridgeProtocol.Key.CURRENT_HEART_RATE.toUInt() to i(value.integerWire(value.currentHeartRate)),
        BridgeProtocol.Key.AVERAGE_CADENCE.toUInt() to i(value.integerWire(value.averageCadence)),
        BridgeProtocol.Key.MAX_CADENCE.toUInt() to i(value.integerWire(value.maxCadence)),
        BridgeProtocol.Key.AVERAGE_POWER.toUInt() to i(value.integerWire(value.averagePower)),
        BridgeProtocol.Key.MAX_POWER.toUInt() to i(value.integerWire(value.maxPower)),
        BridgeProtocol.Key.ENERGY_KCAL.toUInt() to i(value.integerWire(value.energyKcal)),
        BridgeProtocol.Key.UNIT_SYSTEM.toUInt() to i(value.unitSystem.wire),
    ) + listOfNotNull(
        value.locusProfileName?.takeIf(BridgeProtocol::validLocusProfileName)
            ?.let { BridgeProtocol.Key.LOCUS_PROFILE_NAME.toUInt() to text(it) },
    ).toMap()

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
        names: List<String>,
        transferId: Int,
        chunkBytes: Int = BridgeProtocol.MAX_CHUNK_BYTES,
    ): List<PebbleDictionary>? {
        require(chunkBytes in 1..BridgeProtocol.MAX_CHUNK_BYTES)
        require(transferId >= 0)
        val transfer = BridgeProtocol.profileTransfer(names, chunkBytes) ?: return null
        return profileListChunks(transfer, transferId)
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
