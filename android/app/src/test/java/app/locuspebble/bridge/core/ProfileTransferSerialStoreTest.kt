package app.locuspebble.bridge.core

import app.locuspebble.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileTransferSerialStoreTest {
    @Test fun freshStoreStartsAtZeroAndReservationsAdvanceExactlyOneAcrossRestart() = runBlocking {
        val storage = MemoryStorage()
        val firstProcess = ProfileTransferSerialStore(storage, Dispatchers.Unconfined)

        assertEquals(0, firstProcess.reserve())
        val restartedProcess = ProfileTransferSerialStore(storage, Dispatchers.Unconfined)
        assertEquals(1, restartedProcess.reserve())
        assertEquals(2, restartedProcess.reserve())
    }

    @Test fun failedAmbiguousCommitAuthorizesNothingAndLeavesASafeGap() = runBlocking {
        val storage = MemoryStorage().apply { failNextSaveAfterUpdatingMemory = true }
        val store = ProfileTransferSerialStore(storage, Dispatchers.Unconfined)

        assertNull(store.reserve())
        assertEquals(1, store.reserve())
    }

    @Test fun serialWrapsFromTheMaximumToZero() = runBlocking {
        val storage = MemoryStorage(BridgeProtocol.TRANSFER_SERIAL_MASK)
        val store = ProfileTransferSerialStore(storage, Dispatchers.Unconfined)

        assertEquals(0, store.reserve())
    }

    private class MemoryStorage(
        private var value: Long? = null,
    ) : ProfileTransferSerialStore.Storage {
        var failNextSaveAfterUpdatingMemory = false

        override fun load(): Long? = value

        override fun save(serial: Long): Boolean {
            value = serial
            if (!failNextSaveAfterUpdatingMemory) return true
            failNextSaveAfterUpdatingMemory = false
            return false
        }
    }
}
