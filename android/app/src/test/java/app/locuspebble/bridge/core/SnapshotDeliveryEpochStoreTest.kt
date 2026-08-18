package app.locuspebble.bridge.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotDeliveryEpochStoreTest {
    @Test fun reserveReturnsObservedEpochWhenHigherThanPrevious() = runBlocking {
        val store = SnapshotDeliveryEpochStore()
        assertEquals(100L, store.reserve(100L))
        assertEquals(200L, store.reserve(200L))
    }

    @Test fun reserveIncrementsWhenObservedIsLower() = runBlocking {
        val store = SnapshotDeliveryEpochStore()
        assertEquals(100L, store.reserve(100L))
        assertEquals(101L, store.reserve(50L))
    }
}
