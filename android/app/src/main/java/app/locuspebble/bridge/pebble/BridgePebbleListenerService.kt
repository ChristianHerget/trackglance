package app.locuspebble.bridge.pebble

import app.locuspebble.bridge.BuildConfig
import app.locuspebble.bridge.core.BridgeState
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
        val appVersion = PebbleMessages.string(data, BridgeProtocol.Key.APP_VERSION)
        val type = PebbleMessages.integer(data, BridgeProtocol.Key.MESSAGE_TYPE)?.toInt()
        if (version != BridgeProtocol.VERSION) return ReceiveResult.Nack
        BridgeState.update {
            it.copy(
                watchVersion = appVersion,
                lastError = if (appVersion == BuildConfig.VERSION_NAME) it.lastError
                    else "Incompatible watchapp ${appVersion ?: "version not reported"}; expected ${BuildConfig.VERSION_NAME}",
            )
        }
        if (appVersion != BuildConfig.VERSION_NAME) return ReceiveResult.Nack
        if (type == BridgeProtocol.MessageType.REQUEST_SNAPSHOT.wire) {
            BridgeRuntime.get(this).refresh()
            return ReceiveResult.Ack
        }
        if (type == BridgeProtocol.MessageType.REQUEST_PROFILE_LIST.wire) {
            BridgeRuntime.get(this).sendRecordingProfiles()
            return ReceiveResult.Ack
        }
        if (type == BridgeProtocol.MessageType.HEART_RATE_SAMPLE.wire) {
            val sessionId = PebbleMessages.integer(data, BridgeProtocol.Key.SESSION_ID)
                ?: return ReceiveResult.Nack
            val sequence = PebbleMessages.integer(data, BridgeProtocol.Key.HEART_RATE_SEQUENCE)
                ?: return ReceiveResult.Nack
            val bpm = PebbleMessages.integer(data, BridgeProtocol.Key.CURRENT_HEART_RATE)?.toInt()
                ?: return ReceiveResult.Nack
            val sampledAt = PebbleMessages.integer(data, BridgeProtocol.Key.SAMPLE_EPOCH_SECONDS)
                ?: return ReceiveResult.Nack
            return if (BridgeRuntime.get(this).handleHeartRate(sessionId, sequence, bpm, sampledAt)) {
                ReceiveResult.Ack
            } else ReceiveResult.Nack
        }
        if (type != BridgeProtocol.MessageType.COMMAND.wire) return ReceiveResult.Nack
        val sessionId = PebbleMessages.integer(data, BridgeProtocol.Key.SESSION_ID) ?: return ReceiveResult.Nack
        val id = PebbleMessages.integer(data, BridgeProtocol.Key.COMMAND_ID) ?: return ReceiveResult.Nack
        val wireCommand = PebbleMessages.integer(data, BridgeProtocol.Key.COMMAND)?.toInt()
            ?: return ReceiveResult.Nack
        val command = BridgeProtocol.Command.entries.firstOrNull { it.wire == wireCommand }
            ?: return ReceiveResult.Nack
        val profileName = PebbleMessages.string(data, BridgeProtocol.Key.LOCUS_PROFILE_NAME)
        val waypointName = PebbleMessages.string(data, BridgeProtocol.Key.WAYPOINT_NAME)
        BridgeRuntime.get(this).handleCommand(sessionId, id, command, profileName, waypointName)
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == BridgeProtocol.APP_UUID) BridgeRuntime.get(this).watchAppOpened()
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID == BridgeProtocol.APP_UUID) BridgeRuntime.get(this).watchAppClosed()
    }
}
