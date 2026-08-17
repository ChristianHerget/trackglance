package app.locuspebble.bridge.core

import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeStateTest {
    @Test fun concurrentUpdatesDoNotLoseReadModifyWriteChanges() {
        BridgeState.update { it.copy(lastWatchHeartRate = 0) }
        val workers = List(8) {
            thread(start = true) {
                repeat(1_000) {
                    BridgeState.update { status ->
                        status.copy(lastWatchHeartRate = status.lastWatchHeartRate!! + 1)
                    }
                }
            }
        }
        workers.forEach(Thread::join)

        assertEquals(8_000, BridgeState.status.value.lastWatchHeartRate)
    }
}
