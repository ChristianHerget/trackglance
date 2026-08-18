package io.github.christianherget.locuspebble.bridge.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Reserves strictly increasing snapshot epochs before an outbound request may be issued. */
internal class SnapshotDeliveryEpochStore(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val currentEpoch = AtomicLong(-1L)

    suspend fun reserve(observedEpochSeconds: Long): Long = withContext(ioDispatcher) {
        mutex.withLock {
            val observed = observedEpochSeconds.coerceIn(0, MAX_EPOCH)
            val previous = currentEpoch.get()
            val next = if (previous == -1L) {
                observed
            } else {
                check(previous < MAX_EPOCH) { "Snapshot epoch space is exhausted" }
                maxOf(observed, previous + 1L)
            }
            currentEpoch.set(next)
            next
        }
    }

    companion object {
        private const val MAX_EPOCH = 0xffff_ffffL
    }
}
