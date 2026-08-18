package io.github.christianherget.locuspebble.bridge.core

import io.github.christianherget.locuspebble.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTransferSerialStoreTest {
    @Test fun reserveStartsAtZeroAndIncrements() = runBlocking {
        val store = ProfileTransferSerialStore()
        assertEquals(0, store.reserve())
        assertEquals(1, store.reserve())
        assertEquals(2, store.reserve())
    }
}
