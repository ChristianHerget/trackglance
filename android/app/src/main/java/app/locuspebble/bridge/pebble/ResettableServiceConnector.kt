package app.locuspebble.bridge.pebble

import app.locuspebble.bridge.core.BoundedAbandonableCallExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal interface ServiceBindingCallbacks<Service> {
    fun connected(service: Service)
    fun disconnected()
}

/** One independently resettable bindService attempt. */
internal interface ServiceBindingAttempt<Service> : AutoCloseable {
    /** Returns false when bindService refused the request. */
    fun start(callbacks: ServiceBindingCallbacks<Service>): Boolean
}

internal fun interface ServiceBindingFactory<Service> {
    fun create(): ServiceBindingAttempt<Service>
}

/**
 * A cancellable connection cache whose failed or timed-out attempts never poison later sends.
 * Every retry creates a new explicit binding attempt; stale callbacks are rejected by generation.
 */
internal class ResettableServiceConnector<Service>(
    private val bindingFactory: ServiceBindingFactory<Service>,
    private val isAlive: (Service) -> Boolean,
    private val callExecutor: BoundedAbandonableCallExecutor,
    private val connectTimeoutMillis: Long,
) : AutoCloseable {
    private val connectMutex = Mutex()
    private val stateLock = Any()

    private var generation = 0L
    private var closed = false
    private var activeAttempt: ServiceBindingAttempt<Service>? = null
    private var pendingConnection: CompletableDeferred<Service?>? = null
    private var cachedService: Service? = null

    suspend fun getOrConnect(): Service? = connectMutex.withLock {
        detachDeadCachedService()?.closeQuietly()
        synchronized(stateLock) {
            if (closed) return@withLock null
            cachedService?.takeIf(isAlive)?.let { return@withLock it }
        }

        val attempt = try {
            bindingFactory.create()
        } catch (error: Exception) {
            return@withLock null
        }
        val result = CompletableDeferred<Service?>()
        val token = synchronized(stateLock) {
            if (closed) {
                attempt.closeQuietly()
                return@withLock null
            }
            generation += 1
            activeAttempt = attempt
            pendingConnection = result
            generation
        }
        val callbacks = object : ServiceBindingCallbacks<Service> {
            override fun connected(service: Service) {
                val accepted = synchronized(stateLock) {
                    !closed && generation == token && activeAttempt === attempt
                }
                if (accepted && isAlive(service)) result.complete(service) else result.complete(null)
            }

            override fun disconnected() {
                result.complete(null)
                invalidate(token, attempt)
            }
        }

        try {
            val service = withTimeoutOrNull(connectTimeoutMillis) {
                val started = callExecutor.run { attempt.start(callbacks) }
                if (!started) return@withTimeoutOrNull null
                result.await()
            }
            if (service == null || !isAlive(service)) {
                invalidate(token, attempt)
                return@withLock null
            }
            val installed = synchronized(stateLock) {
                if (!closed && generation == token && activeAttempt === attempt) {
                    cachedService = service
                    pendingConnection = null
                    true
                } else {
                    false
                }
            }
            if (installed) service else null
        } catch (error: CancellationException) {
            invalidate(token, attempt)
            throw error
        } catch (_: Exception) {
            invalidate(token, attempt)
            null
        }
    }

    /** Drops a cached Binder without permanently closing the connector. */
    fun reset() {
        val detached = synchronized(stateLock) {
            generation += 1
            cachedService = null
            pendingConnection?.complete(null)
            pendingConnection = null
            activeAttempt.also { activeAttempt = null }
        }
        detached?.closeQuietly()
    }

    override fun close() {
        val detached = synchronized(stateLock) {
            if (closed) return
            closed = true
            generation += 1
            cachedService = null
            pendingConnection?.complete(null)
            pendingConnection = null
            activeAttempt.also { activeAttempt = null }
        }
        detached?.closeQuietly()
    }

    private fun detachDeadCachedService(): ServiceBindingAttempt<Service>? = synchronized(stateLock) {
        val service = cachedService ?: return@synchronized null
        if (isAlive(service)) return@synchronized null
        generation += 1
        cachedService = null
        pendingConnection?.complete(null)
        pendingConnection = null
        activeAttempt.also { activeAttempt = null }
    }

    private fun invalidate(token: Long, attempt: ServiceBindingAttempt<Service>) {
        val detached = synchronized(stateLock) {
            if (generation != token || activeAttempt !== attempt) return
            generation += 1
            cachedService = null
            pendingConnection?.complete(null)
            pendingConnection = null
            activeAttempt.also { activeAttempt = null }
        }
        detached?.closeQuietly()
    }
}

private fun AutoCloseable.closeQuietly() {
    runCatching(::close)
}
