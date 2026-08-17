package app.locuspebble.bridge.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotDeliveryEpochStoreTest {
    @Test fun processRestartAdvancesPastSameSecondAndSyntheticClockDrift() = runBlocking {
        val storage = MemoryStorage()
        val firstProcess = store(storage)

        assertEquals(1_000L, firstProcess.reserve(1_000L))
        assertEquals(1_001L, firstProcess.reserve(1_000L))
        assertEquals(5_000L, firstProcess.reserve(5_000L))

        val restartedProcess = store(storage)
        assertEquals(5_001L, restartedProcess.reserve(1_000L))
        assertEquals(5_002L, restartedProcess.reserve(1_000L))
    }

    @Test fun ambiguousFailedCommitAuthorizesNothingAndNextSuccessAdvancesAgain() = runBlocking {
        val storage = MemoryStorage().apply { nextSaveSucceeds = false }
        val store = store(storage)

        assertNull(store.reserve(100L))
        // Model SharedPreferences' process-visible value changing even though commit returned false.
        assertEquals(100L, storage.epoch)

        storage.nextSaveSucceeds = true
        assertEquals(101L, store.reserve(100L))
    }

    @Test fun corruptOrExhaustedStateFailsClosed() = runBlocking {
        assertNull(store(MemoryStorage(epoch = -1L)).reserve(1_000L))
        assertNull(store(MemoryStorage(epoch = UInt.MAX_VALUE.toLong())).reserve(1_000L))
    }

    private fun store(storage: SnapshotDeliveryEpochStore.Storage) = SnapshotDeliveryEpochStore(
        storage,
        Dispatchers.Unconfined,
    )

    private class MemoryStorage(
        var epoch: Long? = null,
    ) : SnapshotDeliveryEpochStore.Storage {
        var nextSaveSucceeds = true

        override fun load(): Long? = epoch

        override fun save(epoch: Long): Boolean {
            // Deliberately expose the candidate even on false to exercise commit ambiguity.
            this.epoch = epoch
            return nextSaveSucceeds
        }
    }
}
