package io.github.christianherget.trackglance.bridge.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeOperationCoordinatorTest {
    @Test fun concurrentRefreshCannotDeliverAnOlderSnapshotAfterANewerOne() = runBlocking {
        val reads = AtomicInteger()
        val firstSendStarted = CompletableDeferred<Unit>()
        val releaseFirstSend = CompletableDeferred<Unit>()
        val statuses = mutableListOf<Int>()
        val delivered = mutableListOf<Int>()
        val operations = coordinator(
            read = { reads.incrementAndGet() },
            status = { statuses += it },
            send = { snapshot ->
                if (snapshot == 1) {
                    firstSendStarted.complete(Unit)
                    releaseFirstSend.await()
                }
                delivered += snapshot
                true
            },
        )

        val older = async(start = CoroutineStart.UNDISPATCHED) { operations.deliver(listOf("watch")) }
        firstSendStarted.await()
        val newer = async(start = CoroutineStart.UNDISPATCHED) { operations.deliver(listOf("watch")) }

        assertEquals(1, reads.get())
        assertEquals(listOf(1), statuses)
        assertEquals(emptyList<Int>(), delivered)
        releaseFirstSend.complete(Unit)
        awaitAll(older, newer)
        assertEquals(listOf(1, 2), statuses)
        assertEquals(listOf(1, 2), delivered)
    }

    @Test fun mutationPublishesANewerSnapshotBeforeItsResult() = runBlocking {
        var value = 1
        val events = mutableListOf<String>()
        val operations = coordinator(
            read = { value },
            send = { snapshot -> events += "snapshot:$snapshot"; true },
        )

        operations.deliver(listOf("watch"))
        val result = operations.mutateAndDeliver(
            targets = listOf("watch"),
            observedEpochSeconds = 10,
            mutate = { value = 2; events += "mutation"; "ok" },
            readAfterMutation = { epoch, _ -> assertEquals(10L, epoch); value },
            finish = { mutation, delivered ->
                events += "result:$mutation:$delivered"
                delivered
            },
        )

        assertEquals(true, result)
        assertEquals(listOf("snapshot:1", "mutation", "snapshot:2", "result:ok:true"), events)
    }

    @Test fun transientCountersRemainMonotonicAndIndependent() = runBlocking {
        val operations = coordinator(read = { 1 })
        operations.serialized {
            assertEquals(100L, reserveSnapshotEpoch(100))
            assertEquals(101L, reserveSnapshotEpoch(50))
            assertEquals(0, reserveProfileTransferId())
            assertEquals(1, reserveProfileTransferId())
            assertEquals(200L, reserveSnapshotEpoch(200))
        }
    }

    @Test fun exceptionAndCancellationReleaseTheCoordinatorForLaterRequests() = runBlocking {
        var sends = 0
        val sendStarted = CompletableDeferred<Unit>()
        val releaseSend = CompletableDeferred<Unit>()
        val operations = coordinator(
            read = { sends + 1 },
            send = { snapshot ->
                sends++
                when (snapshot) {
                    1 -> error("first send failed")
                    2 -> {
                        sendStarted.complete(Unit)
                        releaseSend.await()
                        true
                    }
                    else -> true
                }
            },
        )

        try {
            operations.deliver(listOf("watch"))
            error("expected first send failure")
        } catch (_: IllegalStateException) {
            // Expected; Mutex.withLock must still release its lock.
        }

        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            operations.deliver(listOf("watch"))
        }
        sendStarted.await()
        cancelled.cancelAndJoin()
        releaseSend.complete(Unit)

        assertEquals(true, withTimeout(1_000) { operations.deliver(listOf("watch")) })
        assertEquals(3, sends)
    }

    private fun coordinator(
        read: suspend (Collection<String>) -> Int?,
        status: suspend (Int) -> Unit = {},
        send: suspend (Int) -> Boolean = { true },
    ) = BridgeOperationCoordinator<String, Int>(
        read = read,
        updateStatus = { snapshot, _ -> status(snapshot) },
        send = { snapshot, _ -> send(snapshot) },
    )
}
