package app.locuspebble.bridge.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps snapshot observation and delivery ordered across all concurrent producers. */
internal class SerializedSnapshotDelivery<Target, Snapshot>(
    private val read: suspend () -> Snapshot,
    private val updateStatus: (Snapshot) -> Unit,
    private val send: suspend (Snapshot, Collection<Target>) -> Boolean,
) {
    private val mutex = Mutex()

    suspend fun deliver(targets: Collection<Target>): Boolean = mutex.withLock {
        val snapshot = read()
        updateStatus(snapshot)
        send(snapshot, targets)
    }
}
