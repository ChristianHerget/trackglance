package app.locuspebble.bridge.pebble

import app.locuspebble.bridge.BuildConfig
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

object PebbleMessages {
    private fun i(value: Int) = PebbleDictionaryItem.Int32(value)
    private fun u(value: Long) = PebbleDictionaryItem.UInt32(value.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt())
    private fun text(value: String) = PebbleDictionaryItem.Text(value)

    fun snapshot(value: BridgeProtocol.Snapshot): PebbleDictionary = mapOf(
        BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
        BridgeProtocol.Key.APP_VERSION.toUInt() to text(BuildConfig.VERSION_NAME),
        BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to i(BridgeProtocol.MessageType.SNAPSHOT.wire),
        BridgeProtocol.Key.RECORDING_STATE.toUInt() to i(value.state.wire),
        BridgeProtocol.Key.SAMPLE_EPOCH_SECONDS.toUInt() to u(value.sampledAtEpochSeconds),
        BridgeProtocol.Key.ELAPSED_SECONDS.toUInt() to u(value.elapsedSeconds),
        BridgeProtocol.Key.MOVING_SECONDS.toUInt() to i(value.movingSeconds?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: BridgeProtocol.UNAVAILABLE),
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
    ) + listOfNotNull(value.locusProfileName?.let { BridgeProtocol.Key.LOCUS_PROFILE_NAME.toUInt() to text(it) }).toMap()

    fun result(sessionId: Long, commandId: Long, result: BridgeProtocol.Result): PebbleDictionary = mapOf(
        BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
        BridgeProtocol.Key.APP_VERSION.toUInt() to text(BuildConfig.VERSION_NAME),
        BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to i(BridgeProtocol.MessageType.COMMAND_RESULT.wire),
        BridgeProtocol.Key.COMMAND_ID.toUInt() to u(commandId),
        BridgeProtocol.Key.SESSION_ID.toUInt() to u(sessionId),
        BridgeProtocol.Key.RESULT.toUInt() to i(result.wire),
    )

    fun integer(dictionary: PebbleDictionary, key: Int): Long? = when (
        val item = dictionary[key.toUInt()]
    ) {
        is PebbleDictionaryItem.Int8 -> item.value.toLong()
        is PebbleDictionaryItem.UInt8 -> item.value.toLong()
        is PebbleDictionaryItem.Int16 -> item.value.toLong()
        is PebbleDictionaryItem.UInt16 -> item.value.toLong()
        is PebbleDictionaryItem.Int32 -> item.value.toLong()
        is PebbleDictionaryItem.UInt32 -> item.value.toLong()
        else -> null
    }

    fun string(dictionary: PebbleDictionary, key: Int): String? =
        (dictionary[key.toUInt()] as? PebbleDictionaryItem.Text)?.value

    fun profileListChunks(names: List<String>, transferId: Int, chunkBytes: Int = 80): List<PebbleDictionary> {
        require(chunkBytes > 0)
        val payload = names.filter { BridgeProtocol.validLocusProfileName(it) }.distinct().joinToString("\n")
        val chunks = BridgeProtocol.utf8Chunks(payload, chunkBytes)
        return chunks.mapIndexed { index, chunk -> mapOf(
            BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
            BridgeProtocol.Key.APP_VERSION.toUInt() to text(BuildConfig.VERSION_NAME),
            BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to i(BridgeProtocol.MessageType.PROFILE_LIST_CHUNK.wire),
            BridgeProtocol.Key.RESULT.toUInt() to i(
                BridgeProtocol.profileListResult(payload).wire,
            ),
            BridgeProtocol.Key.TRANSFER_ID.toUInt() to i(transferId),
            BridgeProtocol.Key.CHUNK_INDEX.toUInt() to i(index),
            BridgeProtocol.Key.CHUNK_COUNT.toUInt() to i(chunks.size),
            BridgeProtocol.Key.CHUNK_DATA.toUInt() to text(chunk),
        ) }
    }

}
