package io.github.christianherget.trackglance.bridge.core

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns transient ordering for every operation that can publish watch-visible bridge state. */
internal class BridgeOperationCoordinator<Target, Snapshot>(
    private val read: suspend (Collection<Target>) -> Snapshot?,
    private val updateStatus: suspend (Snapshot, Collection<Target>) -> Unit,
    private val send: suspend (Snapshot, Collection<Target>) -> Boolean,
) {
    private val mutex = Mutex()
    private var snapshotEpoch = -1L
    private var profileTransferSerial = -1L

    suspend fun deliver(targets: Collection<Target>): Boolean = mutex.withLock {
        val snapshot = read(targets) ?: return@withLock false
        deliverPrepared(snapshot, targets)
    }

    suspend fun <Mutation, Result> mutateAndDeliver(
        targets: Collection<Target>,
        observedEpochSeconds: Long,
        mutate: suspend () -> Mutation,
        readAfterMutation: suspend (reservedEpoch: Long, Mutation) -> Snapshot?,
        finish: suspend (Mutation, snapshotDelivered: Boolean) -> Result,
    ): Result = mutex.withLock {
        val reservedEpoch = reserveSnapshotEpoch(observedEpochSeconds)
        val mutation = mutate()
        val snapshot = readAfterMutation(reservedEpoch, mutation)
        val delivered = snapshot?.let { deliverPrepared(it, targets) } ?: false
        finish(mutation, delivered)
    }

    suspend fun <Result> serialized(
        block: suspend BridgeOperationCoordinator<Target, Snapshot>.() -> Result
    ): Result = mutex.withLock { block() }

    fun reserveSnapshotEpoch(observedEpochSeconds: Long): Long {
        val observed = observedEpochSeconds.coerceIn(0, MAX_EPOCH)
        val next =
            if (snapshotEpoch == -1L) {
                observed
            } else {
                check(snapshotEpoch < MAX_EPOCH) { "Snapshot epoch space is exhausted" }
                maxOf(observed, snapshotEpoch + 1L)
            }
        snapshotEpoch = next
        return next
    }

    fun reserveProfileTransferId(): Int {
        profileTransferSerial =
            if (profileTransferSerial == -1L) {
                0L
            } else {
                (profileTransferSerial + 1L) and BridgeProtocol.TRANSFER_SERIAL_MASK
            }
        return profileTransferSerial.toInt()
    }

    private suspend fun deliverPrepared(
        snapshot: Snapshot,
        targets: Collection<Target>,
    ): Boolean {
        updateStatus(snapshot, targets)
        return send(snapshot, targets)
    }

    private companion object {
        const val MAX_EPOCH = 0xffff_ffffL
    }
}
