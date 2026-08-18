package app.locuspebble.bridge.core

import app.locuspebble.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** In-memory sender floor that advances exactly once per authorized profile transfer. */
internal class ProfileTransferSerialStore(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val currentSerial = AtomicLong(-1L)

    suspend fun reserve(): Int = withContext(ioDispatcher) {
        mutex.withLock {
            val previous = currentSerial.get()
            val next = if (previous == -1L) {
                0L
            } else {
                (previous + 1L) and BridgeProtocol.TRANSFER_SERIAL_MASK
            }
            currentSerial.set(next)
            next.toInt()
        }
    }
}
