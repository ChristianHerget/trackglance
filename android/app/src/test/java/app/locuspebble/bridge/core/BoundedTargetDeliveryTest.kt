package app.locuspebble.bridge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedTargetDeliveryTest {
    @Test fun retriesUntilEveryDistinctTargetSucceeds() = runBlocking {
        val attempts = mutableListOf<List<String>>()
        val delays = mutableListOf<Int>()
        val delivery = BoundedTargetDelivery<String>(maxAttempts = 3) { delays += it }

        val result = delivery.deliver(listOf("watch-a", "watch-b", "watch-a")) { targets ->
            attempts += targets
            if (attempts.size == 1) setOf("watch-a") else targets.toSet()
        }

        assertTrue(result)
        assertEquals(
            listOf(listOf("watch-a", "watch-b"), listOf("watch-b")),
            attempts,
        )
        assertEquals(listOf(1), delays)
    }

    @Test fun exceptionsAndMissingResultsStopAtTheAttemptBound() = runBlocking {
        var attempts = 0
        val delivery = BoundedTargetDelivery<String>(maxAttempts = 3) {}

        val result = delivery.deliver(listOf("watch")) {
            attempts++
            if (attempts == 1) error("transport unavailable")
            emptySet()
        }

        assertFalse(result)
        assertEquals(3, attempts)
    }

    @Test fun emptyTargetSetDoesNotInvokeTheTransport() = runBlocking {
        val delivery = BoundedTargetDelivery<String>(maxAttempts = 3) {}
        assertTrue(delivery.deliver(emptyList()) { error("must not send") })
    }

    @Test fun cancellationIsNeverConvertedIntoATransportFailure() {
        val delivery = BoundedTargetDelivery<String>(maxAttempts = 3) {}
        assertThrows(CancellationException::class.java) {
            runBlocking {
                delivery.deliver(listOf("watch")) { throw CancellationException("cancelled") }
            }
        }
    }

    @Test fun aNeverReturningAttemptTimesOutAndTheNextDeliveryCanProgress() = runBlocking {
        var hang = true
        val delivery = BoundedTargetDelivery<String>(
            maxAttempts = 1,
            attemptTimeoutMillis = 25,
            retryDelay = {},
        )

        assertFalse(
            delivery.deliver(listOf("watch")) {
                if (hang) awaitCancellation()
                it.toSet()
            },
        )

        hang = false
        assertTrue(delivery.deliver(listOf("watch")) { it.toSet() })
    }
}
