package app.locuspebble.bridge.pebble

import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem

object PebbleMessages {
    private fun i(value: Int) = PebbleDictionaryItem.Int32(value)
    private fun u(value: Long) = PebbleDictionaryItem.UInt32(value.coerceIn(0, UInt.MAX_VALUE.toLong()).toUInt())

    fun snapshot(value: BridgeProtocol.Snapshot): PebbleDictionary = mapOf(
        BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
        BridgeProtocol.Key.MESSAGE_TYPE.toUInt() to i(BridgeProtocol.MessageType.SNAPSHOT.wire),
        BridgeProtocol.Key.RECORDING_STATE.toUInt() to i(value.state.wire),
        BridgeProtocol.Key.SAMPLE_EPOCH_SECONDS.toUInt() to u(value.sampledAtEpochSeconds),
        BridgeProtocol.Key.ELAPSED_SECONDS.toUInt() to u(value.elapsedSeconds),
        BridgeProtocol.Key.DISTANCE_METRES.toUInt() to i(value.distanceWire()),
        BridgeProtocol.Key.CURRENT_SPEED_CMPS.toUInt() to i(value.currentSpeedWire()),
        BridgeProtocol.Key.AVERAGE_SPEED_CMPS.toUInt() to i(value.averageSpeedWire()),
        BridgeProtocol.Key.ALTITUDE_DECIMETRES.toUInt() to i(value.altitudeWire()),
        BridgeProtocol.Key.ASCENT_DECIMETRES.toUInt() to i(value.ascentWire()),
        BridgeProtocol.Key.UNIT_SYSTEM.toUInt() to i(value.unitSystem.wire),
    )

    fun result(sessionId: Long, commandId: Long, result: BridgeProtocol.Result): PebbleDictionary = mapOf(
        BridgeProtocol.Key.VERSION.toUInt() to i(BridgeProtocol.VERSION),
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
}
