package app.locuspebble.bridge.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializedSnapshotDeliveryTest {
    @Test fun concurrentRefreshCannotDeliverAnOlderSnapshotAfterANewerOne() = runBlocking {
        val reads = AtomicInteger()
        val firstSendStarted = CompletableDeferred<Unit>()
        val releaseFirstSend = CompletableDeferred<Unit>()
        val statuses = mutableListOf<Int>()
        val delivered = mutableListOf<Int>()
        val delivery = SerializedSnapshotDelivery<String, Int>(
            read = reads::incrementAndGet,
            updateStatus = statuses::add,
            send = { snapshot, _ ->
                if (snapshot == 1) {
                    firstSendStarted.complete(Unit)
                    releaseFirstSend.await()
                }
                delivered += snapshot
                true
            },
        )

        val older = async(start = CoroutineStart.UNDISPATCHED) {
            delivery.deliver(listOf("watch"))
        }
        firstSendStarted.await()
        val newer = async(start = CoroutineStart.UNDISPATCHED) {
            delivery.deliver(listOf("watch"))
        }

        assertEquals(1, reads.get())
        assertEquals(listOf(1), statuses)
        assertEquals(emptyList<Int>(), delivered)
        releaseFirstSend.complete(Unit)
        awaitAll(older, newer)

        assertEquals(listOf(1, 2), statuses)
        assertEquals(listOf(1, 2), delivered)
    }
}
