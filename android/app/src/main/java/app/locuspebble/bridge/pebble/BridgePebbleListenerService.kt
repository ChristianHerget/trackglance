package app.locuspebble.bridge.pebble

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import app.locuspebble.bridge.BuildConfig
import app.locuspebble.bridge.core.BoundedAbandonableCallExecutor
import app.locuspebble.bridge.core.BridgeRuntime
import app.locuspebble.bridge.core.BridgeState
import app.locuspebble.bridge.core.loadOffMain
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
        val delegate = UniversalRequestResponse.Stub.asInterface(super.onBind(intent))
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

                // This bounded wait runs only on an already authenticated Binder-pool thread. It
                // preserves Binder.getCallingUid() for PebbleKit's nested caller-package check and
                // is deliberately not used from Application/Service main-thread startup.
                val ready = runCatching {
                    runBlocking {
                        companionPin.ensureTrustedBounded() && trustedCompanion.isTrusted()
                    }
                }.getOrDefault(false)
                if (!ready || !callback.asBinder().isBinderAlive ||
                    !TrustedPebbleCompanionProvider.isTrustedCallingUid(
                        this@BridgePebbleListenerService,
                        callingUid,
                    )
                ) {
                    BridgeRuntime.resetForCompanionTrustLoss()
                    // Trusted Core will time out/retry; a now-revoked callback is no longer safe to call.
                    return
                }
                val admission = runCatching {
                    runBlocking {
                        TrustedPebbleCompanionProvider.captureTrustedAdmission(
                            this@BridgePebbleListenerService,
                        )
                    }
                }.getOrNull() ?: return
                val originalWatchId = data.getString(KEY_WATCH_ID) ?: return
                val authenticatedData = Bundle(data).apply {
                    putString(
                        KEY_WATCH_ID,
                        AuthenticatedIngressEnvelope.encode(originalWatchId, admission),
                    )
                }
                val delivered = AtomicBoolean(false)
                val boundedCallback = object : SendDataCallback.Stub() {
                    override fun onResult(result: Bundle) {
                        if (!delivered.compareAndSet(false, true)) return
                        val callbackResult = Bundle(result)
                        CALLBACK_DELIVERY.deliver(
                            stillAuthorized = {
                                TrustedPebbleCompanionProvider.isAdmissionCurrent(admission) &&
                                    TrustedPebbleCompanionProvider.isTrustedCallingUid(
                                        this@BridgePebbleListenerService,
                                        callingUid,
                                    ) &&
                                    callback.asBinder().isBinderAlive
                            },
                            callback = { callback.onResult(callbackResult) },
                        )
                    }
                }
                delegate.request(authenticatedData, boundedCallback)
            }
        }
    }

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult = try {
        val ingress = AuthenticatedIngressEnvelope.decode(watch) ?: return ReceiveResult.Nack
        lifecycleCallbacks.serialize {
            // BridgeRuntime's first construction loads the command journal and epoch floor. Keep
            // that storage off PebbleKit's MainScope and outside the revocation lease.
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
                runtime.watchObserved(watch, admission)
            }
            true
        }
        if (!admitted || appVersion != BuildConfig.VERSION_NAME || !acceptedType) {
            return ReceiveResult.Nack
        }
        return when (type) {
            BridgeProtocol.MessageType.REQUEST_SNAPSHOT.wire -> {
                if (runtime.refresh(listOf(watch), admission)) ReceiveResult.Ack else ReceiveResult.Nack
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

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        val ingress = AuthenticatedIngressEnvelope.decode(watch) ?: return
        if (watchappUUID != BridgeProtocol.APP_UUID ||
            !BridgeProtocol.validWatchId(ingress.watch.value)
        ) return
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
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
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        val ingress = AuthenticatedIngressEnvelope.decode(watch) ?: return
        if (watchappUUID != BridgeProtocol.APP_UUID ||
            !BridgeProtocol.validWatchId(ingress.watch.value)
        ) return
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
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
        const val KEY_WATCH_ID = "WATCH_ID"
        val CALLBACK_DELIVERY = BoundedCallbackDelivery(
            BoundedAbandonableCallExecutor(
                maxWorkers = 2,
                threadNamePrefix = "core-callback",
            ),
        )
    }
}
