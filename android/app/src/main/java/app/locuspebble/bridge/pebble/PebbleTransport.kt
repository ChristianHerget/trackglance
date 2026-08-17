package app.locuspebble.bridge.pebble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import androidx.core.os.bundleOf
import app.locuspebble.bridge.core.BoundedAbandonableCallExecutor
import app.locuspebble.bridge.core.BoundedTargetDelivery
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.common.model.fromBundle
import io.rebble.pebblekit2.common.model.toBundle
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

interface PebbleDictionarySender : AutoCloseable {
    suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
    ): Map<WatchIdentifier, TransmissionResult>?

    suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
        admission: TrustAdmission,
    ): Map<WatchIdentifier, TransmissionResult>? = send(dictionary, watches)
}

// Process-wide ceiling: sender recreation cannot accumulate abandoned Core Binder workers.
private val CORE_APP_CALL_EXECUTOR = BoundedAbandonableCallExecutor(
    maxWorkers = 2,
    threadNamePrefix = "core-app-call",
)

class DefaultPebbleDictionarySender internal constructor(
    private val delegate: PebbleDictionarySender,
    private val onTrustLost: () -> Unit = {},
    private val admissionGate: (suspend (
        TrustAdmission,
        suspend () -> Map<WatchIdentifier, TransmissionResult>?,
    ) -> TrustLeaseResult<Map<WatchIdentifier, TransmissionResult>?>)? = null,
    private val isTrusted: suspend () -> Boolean,
) : PebbleDictionarySender {
    constructor(context: Context) : this(
        delegate = CoreAppPebbleDictionarySender(
            context,
            onTrustLost = app.locuspebble.bridge.core.BridgeRuntime::resetForCompanionTrustLoss,
        ),
        onTrustLost = app.locuspebble.bridge.core.BridgeRuntime::resetForCompanionTrustLoss,
        admissionGate = { admission, block ->
            TrustedPebbleCompanionProvider.withOutboundAdmission(context, admission, block)
        },
        isTrusted = TrustedPebbleCompanionProvider.get(context).guard::isTrusted,
    )

    override suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
    ): Map<WatchIdentifier, TransmissionResult>? = send(
        dictionary,
        watches,
        TrustAdmission.INITIAL,
    )

    override suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
        admission: TrustAdmission,
    ): Map<WatchIdentifier, TransmissionResult>? {
        val gate = admissionGate
        if (gate != null) {
            return when (val result = gate(admission) { delegate.send(dictionary, watches, admission) }) {
                is TrustLeaseResult.Admitted -> result.value
                TrustLeaseResult.Stale,
                TrustLeaseResult.Untrusted,
                -> null
            }
        }
        if (isTrusted()) return delegate.send(dictionary, watches, admission)
        onTrustLost()
        return null
    }

    override fun close() = delegate.close()
}

/** Cancellable, explicitly packaged equivalent of PebbleKit's default data sender. */
private class CoreAppPebbleDictionarySender(
    context: Context,
    private val onTrustLost: () -> Unit,
) : PebbleDictionarySender {
    private val isTrusted = TrustedPebbleCompanionProvider.get(context).guard::isTrusted
    private val connector = ResettableServiceConnector(
        bindingFactory = AndroidCoreServiceBindingFactory(
            context.applicationContext,
            Intent(SEND_DATA_ACTION).setPackage(TRUSTED_CORE_APP_PACKAGE),
        ),
        isAlive = { it.asBinder().isBinderAlive },
        callExecutor = CORE_APP_CALL_EXECUTOR,
        connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
    )
    private val closeGuard = IdempotentClose { connector.close() }

    override suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
    ): Map<WatchIdentifier, TransmissionResult>? = send(
        dictionary,
        watches,
        TrustAdmission.INITIAL,
    )

    override suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
        admission: TrustAdmission,
    ): Map<WatchIdentifier, TransmissionResult>? {
        if (closeGuard.isClosed) return null
        if (!isTrusted()) {
            connector.reset()
            onTrustLost()
            return null
        }
        val payload = bundleOf(
            KEY_ACTION to REQUEST_SEND_DATA,
            KEY_WATCHAPP_UUID to BridgeProtocol.APP_UUID.toString(),
            KEY_DATA_DICTIONARY to dictionary.toBundle(),
            KEY_WATCHES_ID to watches.map { it.value }.toTypedArray(),
        )
        val response = request(payload) ?: return null
        val results = response.getBundle(KEY_TRANSMISSION_RESULTS) ?: Bundle()
        return results.keySet().mapNotNull { watchId ->
            if (!BridgeProtocol.validWatchId(watchId)) return@mapNotNull null
            val encoded = results.getBundle(watchId)
            val result = encoded?.let { TransmissionResult.fromBundle(it) }
                ?: TransmissionResult.Unknown("Missing TransmissionResult in PebbleSender result bundle")
            WatchIdentifier(watchId) to result
        }.toMap()
    }

    private suspend fun request(request: Bundle): Bundle? {
        val service = connector.getOrConnect() ?: return null
        if (!isTrusted()) {
            connector.reset()
            onTrustLost()
            return null
        }
        val response = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
            cancellablePebbleRequest(
                BinderPebbleRequestEndpoint(service),
                request,
                CORE_APP_CALL_EXECUTOR,
            )
        } ?: run {
            connector.reset()
            return null
        }
        if (!isTrusted()) {
            connector.reset()
            onTrustLost()
            return null
        }
        return response
    }

    override fun close() = closeGuard.close()

    private companion object {
        const val SEND_DATA_ACTION = "io.rebble.pebblekit2.SEND_DATA_TO_WATCH"
        const val KEY_ACTION = "ACTION"
        const val REQUEST_SEND_DATA = "SEND_DATA_TO_WATCH"
        const val KEY_WATCHAPP_UUID = "WATCHAPP_UUID"
        const val KEY_DATA_DICTIONARY = "DATA_DICTIONARY"
        const val KEY_WATCHES_ID = "WATCHES_ID"
        const val KEY_TRANSMISSION_RESULTS = "TRANSMISSION_RESULTS"
        const val CONNECT_TIMEOUT_MILLIS = 5_000L
        const val REQUEST_TIMEOUT_MILLIS = 10_000L
    }
}

private class AndroidCoreServiceBindingFactory(
    private val context: Context,
    private val intent: Intent,
) : ServiceBindingFactory<UniversalRequestResponse> {
    override fun create(): ServiceBindingAttempt<UniversalRequestResponse> =
        AndroidCoreServiceBindingAttempt(context, intent)
}

/** Keeps unbind correct even when a ServiceConnection callback runs before bindService returns. */
private class AndroidCoreServiceBindingAttempt(
    private val context: Context,
    private val intent: Intent,
) : ServiceBindingAttempt<UniversalRequestResponse> {
    private val lock = Any()
    private var callbacks: ServiceBindingCallbacks<UniversalRequestResponse>? = null
    private var startReturned = false
    private var bound = false
    private var closeRequested = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = binder?.let(UniversalRequestResponse.Stub::asInterface)
            if (service == null) callbacksSnapshot()?.disconnected()
            else callbacksSnapshot()?.connected(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            callbacksSnapshot()?.disconnected()
        }

        override fun onBindingDied(name: ComponentName?) {
            callbacksSnapshot()?.disconnected()
        }

        override fun onNullBinding(name: ComponentName?) {
            callbacksSnapshot()?.disconnected()
        }
    }

    override fun start(callbacks: ServiceBindingCallbacks<UniversalRequestResponse>): Boolean {
        synchronized(lock) {
            check(this.callbacks == null) { "A binding attempt may only be started once" }
            this.callbacks = callbacks
        }
        val didBind = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        val unbindAfterReturn = synchronized(lock) {
            startReturned = true
            bound = didBind
            if (closeRequested && bound) {
                bound = false
                true
            } else {
                false
            }
        }
        if (unbindAfterReturn) runCatching { context.unbindService(connection) }
        return didBind
    }

    override fun close() {
        val shouldUnbind = synchronized(lock) {
            closeRequested = true
            callbacks = null
            if (startReturned && bound) {
                bound = false
                true
            } else {
                false
            }
        }
        if (shouldUnbind) runCatching { context.unbindService(connection) }
    }

    private fun callbacksSnapshot(): ServiceBindingCallbacks<UniversalRequestResponse>? =
        synchronized(lock) { callbacks.takeUnless { closeRequested } }
}

internal class IdempotentClose(
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching(closeAction)
    }
}

internal interface PebbleRequestEndpoint {
    /** Returns null when the endpoint is already dead. */
    fun registerDeathRecipient(onDeath: () -> Unit): AutoCloseable?
    fun request(payload: Bundle, callback: (Bundle) -> Unit): Boolean
}

private class BinderPebbleRequestEndpoint(
    private val service: UniversalRequestResponse,
) : PebbleRequestEndpoint {
    override fun registerDeathRecipient(onDeath: () -> Unit): AutoCloseable? {
        val binder = service.asBinder()
        val recipient = IBinder.DeathRecipient(onDeath)
        return try {
            binder.linkToDeath(recipient, 0)
            AutoCloseable { runCatching { binder.unlinkToDeath(recipient, 0) } }
        } catch (_: RemoteException) {
            null
        }
    }

    override fun request(payload: Bundle, callback: (Bundle) -> Unit): Boolean = try {
        service.request(
            payload,
            object : SendDataCallback.Stub() {
                override fun onResult(result: Bundle) = callback(result)
            },
        )
        true
    } catch (_: RemoteException) {
        false
    }
}

@OptIn(InternalCoroutinesApi::class)
internal suspend fun cancellablePebbleRequest(
    endpoint: PebbleRequestEndpoint,
    payload: Bundle,
    callExecutor: BoundedAbandonableCallExecutor = CORE_APP_CALL_EXECUTOR,
): Bundle? = suspendCancellableCoroutine { continuation ->
    val cleanupLock = Any()
    var registration: AutoCloseable? = null
    var cleaned = false

    fun cleanup() {
        synchronized(cleanupLock) {
            cleaned = true
            runCatching { registration?.close() }
            registration = null
        }
    }

    fun complete(result: Bundle?) {
        val token = continuation.tryResume(result) ?: return
        cleanup()
        continuation.completeResume(token)
    }

    fun fail(error: Throwable) {
        val token = continuation.tryResumeWithException(error) ?: return
        cleanup()
        continuation.completeResume(token)
    }

    continuation.invokeOnCancellation {
        cleanup()
    }
    val submitted = callExecutor.execute {
        if (!continuation.isActive) return@execute
        try {
            val newRegistration = endpoint.registerDeathRecipient { complete(null) }
            if (newRegistration == null) {
                complete(null)
                return@execute
            }
            val requestAuthorized = synchronized(cleanupLock) {
                if (cleaned || !continuation.isActive) {
                    runCatching { newRegistration.close() }
                    false
                } else {
                    registration = newRegistration
                    true
                }
            }
            if (!requestAuthorized) return@execute
            val requestAccepted = endpoint.request(payload, ::complete)
            if (!requestAccepted) complete(null)
        } catch (error: Exception) {
            fail(error)
        }
    }
    if (!submitted) complete(null)
}

/** Performs a small bounded retry and verifies every requested watch result. */
class ReliablePebbleTransport(
    private val sender: PebbleDictionarySender,
    private val maxAttempts: Int = BridgeProtocol.DELIVERY_MAX_ATTEMPTS,
    private val attemptTimeoutMillis: Long = BridgeProtocol.DELIVERY_ATTEMPT_TIMEOUT_MILLIS,
    private val retryDelay: suspend (attempt: Int) -> Unit = { attempt ->
        delay(BridgeProtocol.DELIVERY_RETRY_BASE_MILLIS * attempt)
    },
) : AutoCloseable {
    private val delivery = BoundedTargetDelivery<WatchIdentifier>(
        maxAttempts = maxAttempts,
        attemptTimeoutMillis = attemptTimeoutMillis,
        retryDelay = retryDelay,
    )

    suspend fun send(
        dictionary: PebbleDictionary,
        watches: Collection<WatchIdentifier>,
        admission: TrustAdmission = TrustAdmission.INITIAL,
    ): Boolean {
        return delivery.deliver(watches) { targets ->
            sender.send(dictionary, targets, admission).orEmpty()
                .filterValues { it == TransmissionResult.Success }
                .keys
        }
    }

    override fun close() = sender.close()
}

/** Thread-safe tracking for the watches whose copy of this watchapp is currently open. */
class ActiveWatchRegistry<T> {
    private val values = LinkedHashSet<T>()

    @Synchronized fun opened(value: T): Boolean = values.add(value)
    @Synchronized fun closed(value: T): Boolean = values.remove(value)
    @Synchronized fun snapshot(): Set<T> = values.toSet()
    @Synchronized fun isEmpty(): Boolean = values.isEmpty()
    @Synchronized fun clear(): Boolean = values.isNotEmpty().also { values.clear() }
}
