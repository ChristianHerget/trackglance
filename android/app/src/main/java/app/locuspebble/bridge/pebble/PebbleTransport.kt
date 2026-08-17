package app.locuspebble.bridge.pebble

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import androidx.core.os.bundleOf
import app.locuspebble.bridge.core.BoundedTargetDelivery
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.SendDataCallback
import io.rebble.pebblekit2.common.UniversalRequestResponse
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.common.model.fromBundle
import io.rebble.pebblekit2.common.model.toBundle
import io.rebble.pebblekit2.common.util.SuspendingBindingConnection
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

interface PebbleDictionarySender : AutoCloseable {
    suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
    ): Map<WatchIdentifier, TransmissionResult>?
}

class DefaultPebbleDictionarySender internal constructor(
    private val delegate: PebbleDictionarySender,
    private val isTrusted: suspend () -> Boolean,
) : PebbleDictionarySender {
    constructor(context: Context) : this(
        delegate = CoreAppPebbleDictionarySender(context),
        isTrusted = TrustedPebbleCompanionProvider.get(context).guard::isTrusted,
    )

    override suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
    ): Map<WatchIdentifier, TransmissionResult>? = if (isTrusted()) {
        delegate.send(dictionary, watches)
    } else {
        null
    }

    override fun close() = delegate.close()
}

/** Cancellable, explicitly packaged equivalent of PebbleKit's default data sender. */
private class CoreAppPebbleDictionarySender(context: Context) : PebbleDictionarySender {
    private val connector = SuspendingBindingConnection(
        context,
        intentFactory = {
            Intent(SEND_DATA_ACTION).setPackage(TRUSTED_CORE_APP_PACKAGE)
        },
        bind = UniversalRequestResponse.Stub::asInterface,
    )
    private val closeGuard = IdempotentClose { connector.close() }

    override suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
    ): Map<WatchIdentifier, TransmissionResult>? {
        if (closeGuard.isClosed) return null
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
        return cancellablePebbleRequest(BinderPebbleRequestEndpoint(service), request)
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
    }
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
    try {
        val newRegistration = endpoint.registerDeathRecipient { complete(null) }
        if (newRegistration == null) {
            complete(null)
            return@suspendCancellableCoroutine
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
        if (!requestAuthorized) return@suspendCancellableCoroutine
        val requestAccepted = endpoint.request(payload, ::complete)
        if (requestAccepted == false) complete(null)
    } catch (error: Exception) {
        fail(error)
    }
}

/** Performs a small bounded retry and verifies every requested watch result. */
class ReliablePebbleTransport(
    private val sender: PebbleDictionarySender,
    private val maxAttempts: Int = 3,
    private val attemptTimeoutMillis: Long = 10_000L,
    private val retryDelay: suspend (attempt: Int) -> Unit = { attempt -> delay(100L * attempt) },
) : AutoCloseable {
    private val delivery = BoundedTargetDelivery<WatchIdentifier>(
        maxAttempts = maxAttempts,
        attemptTimeoutMillis = attemptTimeoutMillis,
        retryDelay = retryDelay,
    )

    suspend fun send(dictionary: PebbleDictionary, watches: Collection<WatchIdentifier>): Boolean {
        return delivery.deliver(watches) { targets ->
            sender.send(dictionary, targets).orEmpty()
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
}
