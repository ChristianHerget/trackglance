package io.github.christianherget.locuspebble.bridge.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps snapshot observation and delivery ordered across all concurrent producers. */
internal class SerializedSnapshotDelivery<Target, Snapshot>(
    private val read: suspend (Collection<Target>) -> Snapshot?,
    private val updateStatus: suspend (Snapshot, Collection<Target>) -> Unit,
    private val send: suspend (Snapshot, Collection<Target>) -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun deliver(targets: Collection<Target>): Boolean = mutex.withLock {
        deliverLocked(targets)
    }

    private suspend fun deliverLocked(targets: Collection<Target>): Boolean {
        val snapshot = read(targets) ?: return false
        return deliverPreparedLocked(snapshot, targets)
    }

    /**
     * Orders a mutation against older snapshot requests, then confirms a newer snapshot before
     * [finish] may publish the mutation result. The receiver's snapshot floor makes any late remote
     * completion from an already timed-out request harmless.
     */
    suspend fun <Reservation : Any, Mutation, Result> reserveThenMutateAndDeliverSnapshot(
        targets: Collection<Target>,
        reserve: suspend () -> Reservation?,
        mutate: suspend () -> Mutation,
        readAfterMutation: suspend (Reservation, Mutation) -> Snapshot?,
        finish: suspend (Mutation, snapshotDelivered: Boolean) -> Result,
        reservationFailed: () -> Result,
    ): Result = mutex.withLock {
        val reservation = reserve() ?: return@withLock reservationFailed()
        val mutation = mutate()
        val snapshot = readAfterMutation(reservation, mutation)
        val delivered = if (snapshot == null) false else deliverPreparedLocked(snapshot, targets)
        finish(mutation, delivered)
    }

    private suspend fun deliverPreparedLocked(
        snapshot: Snapshot,
        targets: Collection<Target>,
    ): Boolean {
        updateStatus(snapshot, targets)
        return send(snapshot, targets)
    }
}
