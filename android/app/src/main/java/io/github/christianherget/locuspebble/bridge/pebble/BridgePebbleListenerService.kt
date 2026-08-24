package io.github.christianherget.locuspebble.bridge.pebble

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import io.github.christianherget.locuspebble.bridge.BuildConfig
import io.github.christianherget.locuspebble.bridge.core.BoundedAbandonableCallExecutor
import io.github.christianherget.locuspebble.bridge.core.BridgeRuntime
import io.github.christianherget.locuspebble.bridge.core.BridgeState
import io.github.christianherget.locuspebble.bridge.core.loadOffMain
import io.github.christianherget.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.PebbleKitBundleKeys
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.common.model.mapFromBundle
import io.rebble.pebblekit2.common.model.toBundle
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class BridgePebbleListenerService : BasePebbleListenerService() {
    private val companionPin by lazy {
        TrustedPebbleCompanionProvider.get(applicationContext)
    }
    private val trustedCompanion by lazy {
        companionPin.guard
    }
    private val lifecycleCallbacks by lazy {
        SerializedTrustedLifecycleCallbacks(
            guard = trustedCompanion,
            onTrustLost = BridgeRuntime::resetForCompanionTrustLoss,
        )
    }

    override fun onCreate() {
        super.onCreate()
        companionPin.disableAutoSelection()
        TrustedPebbleCompanionProvider.initializeAsync(this)
    }

    override fun onBind(intent: Intent?): IBinder {
        return object : UniversalRequestResponse.Stub() {
            override fun request(data: Bundle, callback: SendDataCallback) {
                val callingUid = Binder.getCallingUid()
                if (!TrustedPebbleCompanionProvider.isTrustedCallingUid(
                        this@BridgePebbleListenerService,
                        callingUid,
                    ) || !callback.asBinder().isBinderAlive
                ) {
                    // Never invoke a rejected caller's two-way callback; it could block a Binder thread.
                    return
                }

                val request = Bundle(data)
                coroutineScope.launch {
                    val ready = try {
                        companionPin.ensureTrustedBounded() && trustedCompanion.isTrusted()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                    if (!ready || !callback.asBinder().isBinderAlive ||
                        !TrustedPebbleCompanionProvider.isTrustedCallingUid(
                            this@BridgePebbleListenerService,
                            callingUid,
                        )
                    ) {
                        BridgeRuntime.resetForCompanionTrustLoss()
                        return@launch
                    }
                    val admission = TrustedPebbleCompanionProvider.captureTrustedAdmission(
                        this@BridgePebbleListenerService,
                    ) ?: return@launch
                    val result = handleAdmittedRequest(request, admission)
                    val delivered = AtomicBoolean(false)
                    CALLBACK_DELIVERY.deliver(
                        stillAuthorized = {
                            !delivered.get() &&
                                TrustedPebbleCompanionProvider.isAdmissionCurrent(admission) &&
                                TrustedPebbleCompanionProvider.isTrustedCallingUid(
                                    this@BridgePebbleListenerService,
                                    callingUid,
                                ) && callback.asBinder().isBinderAlive
                        },
                        callback = {
                            if (delivered.compareAndSet(false, true)) callback.onResult(result)
                        },
                    )
                }
            }
        }
    }

    private suspend fun handleAdmittedRequest(
        request: Bundle,
        admission: TrustAdmission,
    ): Bundle {
        val watchappUUID = request.getString(PebbleKitBundleKeys.KEY_WATCHAPP_UUID)
            ?.let { encoded -> runCatching { UUID.fromString(encoded) }.getOrNull() }
            ?: return Bundle()
        val watch = request.getString(PebbleKitBundleKeys.KEY_WATCH_ID)
            ?.takeIf(BridgeProtocol::validWatchId)
            ?.let(::WatchIdentifier)
            ?: return Bundle()
        val ingress = AuthenticatedWatchIngress(watch, admission)
        return when (request.getString(PebbleKitBundleKeys.KEY_ACTION)) {
            PebbleKitBundleKeys.ACTION_RECEIVE_DATA_FROM_WATCH -> {
                val dictionary = PebbleDictionaryItem.mapFromBundle(
                    request.getBundle(PebbleKitBundleKeys.KEY_DATA_DICTIONARY) ?: Bundle(),
                )
                Bundle().apply {
                    putBundle(
                        PebbleKitBundleKeys.KEY_TRANSMISSION_RESULTS,
                        onAdmittedMessageReceived(watchappUUID, dictionary, ingress).toBundle(),
                    )
                }
            }
            PebbleKitBundleKeys.ACTION_APP_OPENED -> {
                onAdmittedAppOpened(watchappUUID, ingress)
                Bundle()
            }
            PebbleKitBundleKeys.ACTION_APP_CLOSED -> {
                onAdmittedAppClosed(watchappUUID, ingress)
                Bundle()
            }
            else -> Bundle()
        }
    }

    private suspend fun onAdmittedMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        ingress: AuthenticatedWatchIngress,
    ): ReceiveResult = try {
        lifecycleCallbacks.serialize {
            // Keep runtime construction off PebbleKit's MainScope and outside the revocation lease.
            val runtime = loadOffMain {
                BridgeRuntime.get(this@BridgePebbleListenerService.applicationContext)
            }
            handleMessage(runtime, watchappUUID, data, ingress.watch, ingress.admission)
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
        runtime: BridgeRuntime,
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): ReceiveResult {
        if (watchappUUID != BridgeProtocol.APP_UUID || !BridgeProtocol.validWatchId(watch.value)) {
            return ReceiveResult.Nack
        }
        val version = PebbleMessages.signed32(data, BridgeProtocol.Key.VERSION)
        val appVersion = PebbleMessages.string(data, BridgeProtocol.Key.APP_VERSION)
        val type = PebbleMessages.signed32(data, BridgeProtocol.Key.MESSAGE_TYPE)
        if (version != BridgeProtocol.VERSION) return ReceiveResult.Nack
        val acceptedType = type == BridgeProtocol.MessageType.REQUEST_SNAPSHOT.wire ||
            type == BridgeProtocol.MessageType.REQUEST_PROFILE_LIST.wire ||
            type == BridgeProtocol.MessageType.HEART_RATE_SAMPLE.wire ||
            type == BridgeProtocol.MessageType.COMMAND.wire
        val admitted = runAdmittedInbound(admission, false) {
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
            if (appVersion == BuildConfig.VERSION_NAME && acceptedType) {
                return@runAdmittedInbound runtime.watchObserved(watch, admission)
            }
            true
        }
        if (!admitted || appVersion != BuildConfig.VERSION_NAME || !acceptedType) {
            return ReceiveResult.Nack
        }
        return when (type) {
            BridgeProtocol.MessageType.REQUEST_SNAPSHOT.wire -> {
                if (runtime.refresh(watch, admission)) ReceiveResult.Ack else ReceiveResult.Nack
            }
            BridgeProtocol.MessageType.REQUEST_PROFILE_LIST.wire -> {
                if (runtime.sendRecordingProfiles(watch, admission)) {
                    ReceiveResult.Ack
                } else {
                    ReceiveResult.Nack
                }
            }
            BridgeProtocol.MessageType.HEART_RATE_SAMPLE.wire ->
                handleHeartRate(runtime, data, watch, admission)
            BridgeProtocol.MessageType.COMMAND.wire ->
                handleCommand(runtime, data, watch, admission)
            else -> ReceiveResult.Nack
        }
    }

    private fun handleHeartRate(
        runtime: BridgeRuntime,
        data: PebbleDictionary,
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): ReceiveResult {
        val sessionId = PebbleMessages.unsigned32(data, BridgeProtocol.Key.SESSION_ID)
            ?: return ReceiveResult.Nack
        val sequence = PebbleMessages.unsigned32(data, BridgeProtocol.Key.HEART_RATE_SEQUENCE)
            ?: return ReceiveResult.Nack
        val bpm = PebbleMessages.signed32(data, BridgeProtocol.Key.CURRENT_HEART_RATE)
            ?: return ReceiveResult.Nack
        val sampledAt = PebbleMessages.unsigned32(data, BridgeProtocol.Key.SAMPLE_EPOCH_SECONDS)
            ?: return ReceiveResult.Nack
        return if (runtime.handleHeartRate(watch, sessionId, sequence, bpm, sampledAt, admission)) {
            ReceiveResult.Ack
        } else {
            ReceiveResult.Nack
        }
    }

    private suspend fun handleCommand(
        runtime: BridgeRuntime,
        data: PebbleDictionary,
        watch: WatchIdentifier,
        admission: TrustAdmission,
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
            admission = admission,
        )
        return if (delivered) ReceiveResult.Ack else ReceiveResult.Nack
    }

    private suspend fun onAdmittedAppOpened(
        watchappUUID: UUID,
        ingress: AuthenticatedWatchIngress,
    ) {
        if (watchappUUID != BridgeProtocol.APP_UUID ||
            !BridgeProtocol.validWatchId(ingress.watch.value)
        ) return
        try {
            lifecycleCallbacks.serialize {
                val runtime = loadOffMain {
                    BridgeRuntime.get(this@BridgePebbleListenerService.applicationContext)
                }
                runAdmittedInbound(ingress.admission, Unit) {
                    runtime.watchAppOpened(ingress.watch, ingress.admission)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            BridgeState.update { it.copy(lastError = error.message ?: error.javaClass.simpleName) }
        }
    }

    private suspend fun onAdmittedAppClosed(
        watchappUUID: UUID,
        ingress: AuthenticatedWatchIngress,
    ) {
        if (watchappUUID != BridgeProtocol.APP_UUID ||
            !BridgeProtocol.validWatchId(ingress.watch.value)
        ) return
        try {
            lifecycleCallbacks.serialize {
                val runtime = loadOffMain {
                    BridgeRuntime.get(this@BridgePebbleListenerService.applicationContext)
                }
                runAdmittedInbound(ingress.admission, Unit) {
                    runtime.watchAppClosed(ingress.watch, ingress.admission)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            BridgeState.update { it.copy(lastError = error.message ?: error.javaClass.simpleName) }
        }
    }

    private suspend fun <Result> runAdmittedInbound(
        admission: TrustAdmission,
        rejected: Result,
        block: suspend () -> Result,
    ): Result = when (
        val result = TrustedPebbleCompanionProvider.withInboundAdmission(
            this@BridgePebbleListenerService,
            admission,
        ) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                BridgeState.update { it.copy(lastError = error.message ?: error.javaClass.simpleName) }
                rejected
            }
        }
    ) {
        is TrustLeaseResult.Admitted -> result.value
        TrustLeaseResult.Stale,
        TrustLeaseResult.Untrusted,
        -> rejected
    }

    private companion object {
        val CALLBACK_DELIVERY = BoundedCallbackDelivery(
            BoundedAbandonableCallExecutor(
                maxWorkers = 2,
                threadNamePrefix = "core-callback",
            ),
        )
    }
}
