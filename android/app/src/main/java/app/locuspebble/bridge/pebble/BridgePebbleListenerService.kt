package app.locuspebble.bridge.pebble

import app.locuspebble.bridge.BuildConfig
import app.locuspebble.bridge.core.BridgeRuntime
import app.locuspebble.bridge.core.BridgeState
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class BridgePebbleListenerService : BasePebbleListenerService() {
    private val trustedCompanion by lazy {
        TrustedPebbleCompanionGuard {
            DefaultPebbleAndroidAppPicker.getInstance(applicationContext).getCurrentlySelectedApp()
        }
    }
    private val lifecycleCallbacks by lazy {
        SerializedTrustedLifecycleCallbacks(trustedCompanion)
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult = try {
        if (trustedCompanion.isTrusted()) {
            handleMessage(watchappUUID, data, watch)
        } else {
            ReceiveResult.Nack
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        BridgeState.update {
            it.copy(lastError = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName)
        }
        ReceiveResult.Nack
    }

    private suspend fun handleMessage(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != BridgeProtocol.APP_UUID || watch.value.isBlank()) return ReceiveResult.Nack
        val version = PebbleMessages.signed32(data, BridgeProtocol.Key.VERSION)
        val appVersion = PebbleMessages.string(data, BridgeProtocol.Key.APP_VERSION)
        val type = PebbleMessages.signed32(data, BridgeProtocol.Key.MESSAGE_TYPE)
        if (version != BridgeProtocol.VERSION) return ReceiveResult.Nack
        BridgeState.update {
            it.copy(
                watchVersion = appVersion,
                lastError = if (appVersion == BuildConfig.VERSION_NAME) {
                    it.lastError
                } else {
                    "Incompatible watchapp ${appVersion ?: "version not reported"}; " +
                        "expected ${BuildConfig.VERSION_NAME}"
                },
            )
        }
        if (appVersion != BuildConfig.VERSION_NAME) return ReceiveResult.Nack

        val acceptedType = type == BridgeProtocol.MessageType.REQUEST_SNAPSHOT.wire ||
            type == BridgeProtocol.MessageType.REQUEST_PROFILE_LIST.wire ||
            type == BridgeProtocol.MessageType.HEART_RATE_SAMPLE.wire ||
            type == BridgeProtocol.MessageType.COMMAND.wire
        if (!acceptedType) return ReceiveResult.Nack
        val runtime = BridgeRuntime.get(this)
        runtime.watchObserved(watch)
        return when (type) {
            BridgeProtocol.MessageType.REQUEST_SNAPSHOT.wire -> {
                if (runtime.refresh(listOf(watch))) ReceiveResult.Ack else ReceiveResult.Nack
            }
            BridgeProtocol.MessageType.REQUEST_PROFILE_LIST.wire -> {
                if (runtime.sendRecordingProfiles(watch)) ReceiveResult.Ack else ReceiveResult.Nack
            }
            BridgeProtocol.MessageType.HEART_RATE_SAMPLE.wire -> handleHeartRate(runtime, data, watch)
            BridgeProtocol.MessageType.COMMAND.wire -> handleCommand(runtime, data, watch)
            else -> ReceiveResult.Nack
        }
    }

    private fun handleHeartRate(
        runtime: BridgeRuntime,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        val sessionId = PebbleMessages.unsigned32(data, BridgeProtocol.Key.SESSION_ID)
            ?: return ReceiveResult.Nack
        val sequence = PebbleMessages.unsigned32(data, BridgeProtocol.Key.HEART_RATE_SEQUENCE)
            ?: return ReceiveResult.Nack
        val bpm = PebbleMessages.signed32(data, BridgeProtocol.Key.CURRENT_HEART_RATE)
            ?: return ReceiveResult.Nack
        val sampledAt = PebbleMessages.unsigned32(data, BridgeProtocol.Key.SAMPLE_EPOCH_SECONDS)
            ?: return ReceiveResult.Nack
        return if (runtime.handleHeartRate(watch, sessionId, sequence, bpm, sampledAt)) {
            ReceiveResult.Ack
        } else {
            ReceiveResult.Nack
        }
    }

    private suspend fun handleCommand(
        runtime: BridgeRuntime,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        val sessionId = PebbleMessages.unsigned32(data, BridgeProtocol.Key.SESSION_ID)
            ?: return ReceiveResult.Nack
        val commandId = PebbleMessages.unsigned32(data, BridgeProtocol.Key.COMMAND_ID)
            ?: return ReceiveResult.Nack
        val wireCommand = PebbleMessages.signed32(data, BridgeProtocol.Key.COMMAND)
            ?: return ReceiveResult.Nack
        val command = BridgeProtocol.Command.entries.firstOrNull { it.wire == wireCommand }
            ?: return ReceiveResult.Nack
        val delivered = runtime.handleCommand(
            watch = watch,
            sessionId = sessionId,
            commandId = commandId,
            command = command,
            profileName = PebbleMessages.string(data, BridgeProtocol.Key.LOCUS_PROFILE_NAME),
            waypointName = PebbleMessages.string(data, BridgeProtocol.Key.WAYPOINT_NAME),
        )
        return if (delivered) ReceiveResult.Ack else ReceiveResult.Nack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != BridgeProtocol.APP_UUID || watch.value.isBlank()) return
        coroutineScope.launch {
            lifecycleCallbacks.runIfTrusted {
                try {
                    BridgeRuntime.get(this@BridgePebbleListenerService).watchAppOpened(watch)
                } catch (error: Exception) {
                    BridgeState.update { it.copy(lastError = error.message ?: error.javaClass.simpleName) }
                }
            }
        }
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != BridgeProtocol.APP_UUID || watch.value.isBlank()) return
        coroutineScope.launch {
            lifecycleCallbacks.runIfTrusted {
                try {
                    BridgeRuntime.get(this@BridgePebbleListenerService).watchAppClosed(watch)
                } catch (error: Exception) {
                    BridgeState.update { it.copy(lastError = error.message ?: error.javaClass.simpleName) }
                }
            }
        }
    }
}
