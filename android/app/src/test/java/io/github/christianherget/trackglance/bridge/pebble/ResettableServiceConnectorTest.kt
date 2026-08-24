package io.github.christianherget.trackglance.bridge.pebble

import io.github.christianherget.trackglance.bridge.core.BoundedAbandonableCallExecutor
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResettableServiceConnectorTest {
    @Test fun refusedFirstBindDoesNotPoisonTheNextAvailableConnection() = runBlocking {
        val attempts = AtomicInteger()
        val workers = BoundedAbandonableCallExecutor(1, "binding-retry-test")
        val connector = ResettableServiceConnector(
            bindingFactory = ServiceBindingFactory {
                val number = attempts.incrementAndGet()
                FakeBindingAttempt(
                    startBlock = { callbacks ->
                        if (number == 1) {
                            false
                        } else {
                            callbacks.connected("core-$number")
                            true
                        }
                    },
                )
            },
            isAlive = { true },
            callExecutor = workers,
            connectTimeoutMillis = 500,
        )
        try {
            assertNull(connector.getOrConnect())
            assertEquals("core-2", connector.getOrConnect())
            assertEquals(2, attempts.get())
        } finally {
            connector.close()
            workers.close()
        }
    }

    @Test fun timedOutAttemptIsClosedAndANewAttemptMayConnect() = runBlocking {
        val attempts = AtomicInteger()
        val closed = AtomicInteger()
        val workers = BoundedAbandonableCallExecutor(1, "binding-timeout-test")
        val connector = ResettableServiceConnector(
            bindingFactory = ServiceBindingFactory {
                val number = attempts.incrementAndGet()
                FakeBindingAttempt(
                    startBlock = { callbacks ->
                        if (number > 1) callbacks.connected("recovered")
                        true
                    },
                    onClose = closed::incrementAndGet,
                )
            },
            isAlive = { true },
            callExecutor = workers,
            connectTimeoutMillis = 25,
        )
        try {
            assertNull(connector.getOrConnect())
            assertEquals("recovered", connector.getOrConnect())
            assertEquals(2, attempts.get())
            assertEquals(1, closed.get())
        } finally {
            connector.close()
            workers.close()
        }
    }

    @Test fun disconnectInvalidatesTheCachedServiceAndRebinds() = runBlocking {
        val attempts = mutableListOf<FakeBindingAttempt>()
        val workers = BoundedAbandonableCallExecutor(1, "binding-death-test")
        val connector = ResettableServiceConnector(
            bindingFactory = ServiceBindingFactory {
                val number = attempts.size + 1
                FakeBindingAttempt(
                    startBlock = { callbacks ->
                        callbacks.connected("service-$number")
                        true
                    },
                ).also(attempts::add)
            },
            isAlive = { true },
            callExecutor = workers,
            connectTimeoutMillis = 500,
        )
        try {
            assertEquals("service-1", connector.getOrConnect())
            attempts.single().disconnect()
            assertEquals("service-2", connector.getOrConnect())
            assertEquals(2, attempts.size)
            assertEquals(1, attempts.first().closeCount)
        } finally {
            connector.close()
            workers.close()
        }
    }

    @Test fun cancelledConnectionAttemptIsResetBeforeTheNextSend() = runBlocking {
        val attempts = mutableListOf<FakeBindingAttempt>()
        val firstBindStarted = CompletableDeferred<Unit>()
        val firstBindReturned = CompletableDeferred<Unit>()
        val workers = BoundedAbandonableCallExecutor(1, "binding-cancel-test")
        val connector = ResettableServiceConnector(
            bindingFactory = ServiceBindingFactory {
                val number = attempts.size + 1
                FakeBindingAttempt(
                    startBlock = { callbacks ->
                        if (number > 1) {
                            callbacks.connected("recovered")
                        } else {
                            firstBindStarted.complete(Unit)
                        }
                        firstBindReturned.complete(Unit)
                        true
                    },
                ).also(attempts::add)
            },
            isAlive = { true },
            callExecutor = workers,
            connectTimeoutMillis = 5_000,
        )
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { connector.getOrConnect() }
            firstBindStarted.await()
            firstBindReturned.await()
            first.cancelAndJoin()
            assertEquals(1, attempts.first().closeCount)

            assertEquals("recovered", connector.getOrConnect())
            assertEquals(2, attempts.size)
        } finally {
            connector.close()
            workers.close()
        }
    }

    private class FakeBindingAttempt(
        private val startBlock: (ServiceBindingCallbacks<String>) -> Boolean,
        private val onClose: () -> Unit = {},
    ) : ServiceBindingAttempt<String> {
        private var callbacks: ServiceBindingCallbacks<String>? = null
        var closeCount = 0
            private set

        override fun start(callbacks: ServiceBindingCallbacks<String>): Boolean {
            this.callbacks = callbacks
            return startBlock(callbacks)
        }

        override fun close() {
            closeCount++
            onClose()
        }

        fun disconnect() = callbacks?.disconnected()
    }
}
