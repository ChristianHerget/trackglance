package app.locuspebble.bridge.pebble

import app.locuspebble.bridge.core.BridgeRuntime
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

class BridgePebbleListenerService : BasePebbleListenerService() {
    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != BridgeProtocol.APP_UUID) return ReceiveResult.Nack
        val version = PebbleMessages.integer(data, BridgeProtocol.Key.VERSION)?.toInt()
        val type = PebbleMessages.integer(data, BridgeProtocol.Key.MESSAGE_TYPE)?.toInt()
        if (version != BridgeProtocol.VERSION) return ReceiveResult.Nack
        if (type == BridgeProtocol.MessageType.REQUEST_SNAPSHOT.wire) {
            BridgeRuntime.get(this).refresh()
            return ReceiveResult.Ack
        }
        if (type != BridgeProtocol.MessageType.COMMAND.wire) return ReceiveResult.Nack
        val id = PebbleMessages.integer(data, BridgeProtocol.Key.COMMAND_ID) ?: return ReceiveResult.Nack
        val wireCommand = PebbleMessages.integer(data, BridgeProtocol.Key.COMMAND)?.toInt()
            ?: return ReceiveResult.Nack
        val command = BridgeProtocol.Command.entries.firstOrNull { it.wire == wireCommand }
            ?: return ReceiveResult.Nack
        BridgeRuntime.get(this).handleCommand(id, command)
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == BridgeProtocol.APP_UUID) BridgeRuntime.get(this).watchAppOpened()
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == BridgeProtocol.APP_UUID) BridgeRuntime.get(this).watchAppClosed()
    }
}

