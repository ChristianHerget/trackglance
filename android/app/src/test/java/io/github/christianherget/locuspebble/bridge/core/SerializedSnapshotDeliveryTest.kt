package io.github.christianherget.locuspebble.bridge.core

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
            read = { reads.incrementAndGet() },
            updateStatus = { snapshot, _ -> statuses.add(snapshot) },
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

    @Test fun mutationPublishesANewerSnapshotBeforeItsResult() = runBlocking {
        var value = 1
        val events = mutableListOf<String>()
        val delivery = SerializedSnapshotDelivery<String, Int>(
            read = { value },
            updateStatus = { _, _ -> },
            send = { snapshot, _ ->
                events += "snapshot:$snapshot"
                true
            },
        )

        delivery.deliver(listOf("watch"))
        val result = delivery.reserveThenMutateAndDeliverSnapshot(
            targets = listOf("watch"),
            reserve = { Unit },
            mutate = {
                value = 2
                events += "mutation"
                "ok"
            },
            readAfterMutation = { _, _ -> value },
            finish = { mutation, snapshotDelivered ->
                events += "result:$mutation:$snapshotDelivered"
                snapshotDelivered
            },
            reservationFailed = { false },
        )

        assertEquals(true, result)
        assertEquals(
            listOf("snapshot:1", "mutation", "snapshot:2", "result:ok:true"),
            events,
        )
    }
}
