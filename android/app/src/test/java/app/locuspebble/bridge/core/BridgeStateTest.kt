package app.locuspebble.bridge.core

import app.locuspebble.bridge.protocol.BridgeProtocol
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun losingTheTrustedPebbleSelectionClearsCachedWatchDiagnostics() {
        val untrusted = BridgeStatus(
            watchAppOpen = true,
            pebbleAppPackage = "coredevices.coreapp",
            watchConnected = true,
            watchVersion = "0.1.7",
        ).withPebbleSelection(null)

        assertNull(untrusted.pebbleAppPackage)
        assertFalse(untrusted.watchConnected)
        assertFalse(untrusted.watchAppOpen)
        assertNull(untrusted.watchVersion)
    }

    @Test fun watchInfoFailureClearsACachedConnection() {
        val failed = BridgeStatus(
            watchAppOpen = true,
            pebbleAppPackage = "coredevices.coreapp",
            watchConnected = true,
            watchVersion = "0.1.7",
        ).withPebbleConnectionFailure("Core info unavailable")

        assertFalse(failed.watchConnected)
        assertEquals("Core info unavailable", failed.diagnosticsError)
    }

    @Test fun healthyDiagnosticsDoNotEraseAnUnrelatedRuntimeFailure() {
        val refreshed = BridgeStatus(lastError = "Command result delivery failed").withDiagnosticsSnapshot(
            recordingState = BridgeProtocol.RecordingState.RECORDING,
            sampledAtMillis = 1_000L,
            currentHeartRate = 120,
            error = null,
        )

        assertEquals("Command result delivery failed", refreshed.lastError)
        assertNull(refreshed.diagnosticsError)
    }
}
