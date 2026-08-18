package io.github.christianherget.locuspebble.bridge.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PebbleTransportTest {
    @Test fun retriesTheSingleActiveWatchWithinTheBound() = runBlocking {
        val watch = WatchIdentifier("watch")
        val sender = FakeSender { attempt, _ ->
            if (attempt == 1) TransmissionResult.Unknown("retry") else TransmissionResult.Success
        }
        val delays = mutableListOf<Int>()
        val transport = ReliablePebbleTransport(sender, maxAttempts = 3) { delays += it }

        assertTrue(transport.send(emptyMap(), watch, TEST_ADMISSION))
        assertEquals(listOf(watch, watch), sender.targets)
        assertEquals(listOf(1), delays)
    }

    @Test fun missingAndExceptionalResultsFailAfterTheConfiguredAttempts() = runBlocking {
        val watch = WatchIdentifier("watch")
        val sender = FakeSender { attempt, _ ->
            if (attempt == 1) throw IllegalStateException("transport unavailable")
            null
        }
        val transport = ReliablePebbleTransport(sender, maxAttempts = 3) {}

        assertFalse(transport.send(emptyMap(), watch, TEST_ADMISSION))
        assertEquals(3, sender.targets.size)
    }

    @Test fun outboundDeliveryStopsWhenCoreSelectionChanges() = runBlocking {
        val watch = WatchIdentifier("watch")
        val delegate = FakeSender { _, _ -> TransmissionResult.Success }
        var selected = true
        val sender = DefaultPebbleDictionarySender(delegate) { selected }

        assertEquals(TransmissionResult.Success, sender.send(emptyMap(), watch, TEST_ADMISSION))
        selected = false
        assertNull(sender.send(emptyMap(), watch, TEST_ADMISSION))
        assertEquals(listOf(watch), delegate.targets)
    }

    @Test fun staleSessionQuietlyDropsWithoutResettingTheCurrentSession() = runBlocking {
        val watch = WatchIdentifier("watch")
        val old = TrustAdmission(1)
        val current = TrustAdmission(2)
        val delegate = FakeSender { _, _ -> TransmissionResult.Success }
        var resets = 0
        val sender = DefaultPebbleDictionarySender(
            delegate = delegate,
            onTrustLost = { resets++ },
            admissionGate = { admission, block ->
                if (admission == current) TrustLeaseResult.Admitted(block()) else TrustLeaseResult.Stale
            },
            isTrusted = { true },
        )

        assertNull(sender.send(emptyMap(), watch, old))
        assertEquals(0, resets)
        assertTrue(delegate.targets.isEmpty())
        assertEquals(TransmissionResult.Success, sender.send(emptyMap(), watch, current))
    }

    @Test fun timedOutSenderDoesNotBlockALaterDelivery() = runBlocking {
        val watch = WatchIdentifier("watch")
        val sender = FakeSender { attempt, _ ->
            if (attempt == 1) awaitCancellation()
            TransmissionResult.Success
        }
        val transport = ReliablePebbleTransport(
            sender = sender,
            maxAttempts = 1,
            attemptTimeoutMillis = 25,
            retryDelay = {},
        )

        assertFalse(transport.send(emptyMap(), watch, TEST_ADMISSION))
        assertTrue(transport.send(emptyMap(), watch, TEST_ADMISSION))
        assertEquals(2, sender.targets.size)
    }

    private class FakeSender(
        private val response: suspend (attempt: Int, watch: WatchIdentifier) -> TransmissionResult?,
    ) : PebbleDictionarySender {
        val targets = mutableListOf<WatchIdentifier>()

        override suspend fun send(
            dictionary: PebbleDictionary,
            watch: WatchIdentifier,
            admission: TrustAdmission,
        ): TransmissionResult? {
            targets += watch
            return response(targets.size, watch)
        }

        override fun close() = Unit
    }

    private companion object {
        val TEST_ADMISSION = TrustAdmission(0)
    }
}
