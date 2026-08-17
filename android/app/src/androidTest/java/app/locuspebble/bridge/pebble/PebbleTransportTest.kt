package app.locuspebble.bridge.pebble

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.runBlocking
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

        assertTrue(transport.send(emptyMap(), listOf(watchA, watchB, watchA)))
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

        assertFalse(transport.send(emptyMap(), listOf(watch)))
        assertEquals(3, sender.targets.size)
    }

    @Test fun emptyTargetIsANoOp() = runBlocking {
        val sender = FakeSender { _, _ -> error("must not send") }
        val transport = ReliablePebbleTransport(sender)

        assertTrue(transport.send(emptyMap(), emptyList()))
        assertTrue(sender.targets.isEmpty())
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

    private class FakeSender(
        private val response: suspend (attempt: Int, watches: List<WatchIdentifier>) ->
            Map<WatchIdentifier, TransmissionResult>?,
    ) : PebbleDictionarySender {
        val targets = mutableListOf<List<WatchIdentifier>>()

        override suspend fun send(
            dictionary: PebbleDictionary,
            watches: List<WatchIdentifier>,
        ): Map<WatchIdentifier, TransmissionResult>? {
            targets += watches
            return response(targets.size, watches)
        }

        override fun close() = Unit
    }
}
