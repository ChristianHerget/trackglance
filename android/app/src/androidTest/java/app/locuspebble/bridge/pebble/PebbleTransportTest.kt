package app.locuspebble.bridge.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PebbleTransportTest {
    @Test fun retriesOnlyWithinTheBoundAndRequiresEveryTargetToSucceed() = runBlocking {
        val watchA = WatchIdentifier("watch-a")
        val watchB = WatchIdentifier("watch-b")
        val sender = FakeSender { attempt, watches ->
            when (attempt) {
                1 -> mapOf(watchA to TransmissionResult.Success)
                else -> watches.associateWith { TransmissionResult.Success }
            }
        }
        val delays = mutableListOf<Int>()
        val transport = ReliablePebbleTransport(sender, maxAttempts = 3) { delays += it }

        assertTrue(transport.send(emptyMap(), listOf(watchA, watchB, watchA), TEST_ADMISSION))
        assertEquals(listOf(listOf(watchA, watchB), listOf(watchB)), sender.targets)
        assertEquals(listOf(1), delays)
    }

    @Test fun missingAndExceptionalResultsFailAfterTheConfiguredAttempts() = runBlocking {
        val watch = WatchIdentifier("watch")
        val sender = FakeSender { attempt, _ ->
            if (attempt == 1) throw IllegalStateException("transport unavailable")
            emptyMap()
        }
        val transport = ReliablePebbleTransport(sender, maxAttempts = 3) {}

        assertFalse(transport.send(emptyMap(), listOf(watch), TEST_ADMISSION))
        assertEquals(3, sender.targets.size)
    }

    @Test fun emptyTargetIsANoOp() = runBlocking {
        val sender = FakeSender { _, _ -> error("must not send") }
        val transport = ReliablePebbleTransport(sender)

        assertTrue(transport.send(emptyMap(), emptyList(), TEST_ADMISSION))
        assertTrue(sender.targets.isEmpty())
    }

    @Test fun outboundDeliveryStopsImmediatelyWhenThePinnedSelectionChanges() = runBlocking {
        val watch = WatchIdentifier("watch")
        val delegate = FakeSender { _, watches ->
            watches.associateWith { TransmissionResult.Success }
        }
        var trusted = true
        val sender = DefaultPebbleDictionarySender(delegate) { trusted }

        assertEquals(
            mapOf(watch to TransmissionResult.Success),
            sender.send(emptyMap(), listOf(watch), TEST_ADMISSION),
        )
        trusted = false
        assertNull(sender.send(emptyMap(), listOf(watch), TEST_ADMISSION))
        assertEquals(listOf(listOf(watch)), delegate.targets)
    }

    @Test fun staleAdmissionQuietlyDropsWithoutResettingTheNewSignerGeneration() = runBlocking {
        val watch = WatchIdentifier("watch")
        val old = TrustAdmission(1)
        val current = TrustAdmission(2)
        val delegate = FakeSender { _, watches ->
            watches.associateWith { TransmissionResult.Success }
        }
        var resets = 0
        val sender = DefaultPebbleDictionarySender(
            delegate = delegate,
            onTrustLost = { resets++ },
            admissionGate = { admission, block ->
                if (admission == current) {
                    TrustLeaseResult.Admitted(block())
                } else {
                    TrustLeaseResult.Stale
                }
            },
            isTrusted = { true },
        )

        assertNull(sender.send(emptyMap(), listOf(watch), old))
        assertEquals(0, resets)
        assertTrue(delegate.targets.isEmpty())
        assertEquals(
            mapOf(watch to TransmissionResult.Success),
            sender.send(emptyMap(), listOf(watch), current),
        )
    }

    @Test fun timedOutSenderDoesNotBlockALaterDelivery() = runBlocking {
        val watch = WatchIdentifier("watch")
        val sender = FakeSender { attempt, watches ->
            if (attempt == 1) awaitCancellation()
            watches.associateWith { TransmissionResult.Success }
        }
        val transport = ReliablePebbleTransport(
            sender = sender,
            maxAttempts = 1,
            attemptTimeoutMillis = 25,
            retryDelay = {},
        )

        assertFalse(transport.send(emptyMap(), listOf(watch), TEST_ADMISSION))
        assertTrue(transport.send(emptyMap(), listOf(watch), TEST_ADMISSION))
        assertEquals(2, sender.targets.size)
    }

    @Test fun activeRegistryKeepsPollingUntilTheLastWatchCloses() {
        val registry = ActiveWatchRegistry<String>()
        assertTrue(registry.opened("watch-a"))
        assertTrue(registry.opened("watch-b"))
        assertFalse(registry.opened("watch-a"))
        assertTrue(registry.closed("watch-a"))
        assertEquals(setOf("watch-b"), registry.snapshot())
        assertFalse(registry.isEmpty())
        assertTrue(registry.closed("watch-b"))
        assertTrue(registry.isEmpty())
    }

    @Test fun connectorCleanupIsIdempotentAndFailSafeBeforeBinding() {
        var closes = 0
        val close = IdempotentClose {
            closes++
            throw IllegalArgumentException("not bound")
        }

        close.close()
        close.close()

        assertTrue(close.isClosed)
        assertEquals(1, closes)
    }

    private class FakeSender(
        private val response: suspend (attempt: Int, watches: List<WatchIdentifier>) ->
            Map<WatchIdentifier, TransmissionResult>?,
    ) : PebbleDictionarySender {
        val targets = mutableListOf<List<WatchIdentifier>>()

        override suspend fun send(
            dictionary: PebbleDictionary,
            watches: List<WatchIdentifier>,
            admission: TrustAdmission,
        ): Map<WatchIdentifier, TransmissionResult>? {
            targets += watches
            return response(targets.size, watches)
        }

        override fun close() = Unit
    }

    private companion object {
        val TEST_ADMISSION = TrustAdmission(0)
    }
}
