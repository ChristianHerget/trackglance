package io.github.christianherget.trackglance.bridge.core

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun concurrentFailureReportsHaveAnExactOccurrenceCount() {
        BridgeState.update { it.copy(lastError = null, diagnosticsError = null) }
        RecentDiagnostics.clear()
        val workers = List(8) {
            thread(start = true) {
                repeat(100) {
                    BridgeState.update { status ->
                        status.copy(lastError = BridgeFailure.technical("Concurrent failure"))
                    }
                }
            }
        }
        workers.forEach(Thread::join)

        val entry = RecentDiagnostics.entries.value.single()
        assertEquals(800, entry.count)
        assertEquals("Concurrent failure", entry.failure.technicalDetail)
    }

    @Test fun retainingTheSameFailureObjectDoesNotAddAnOccurrence() {
        BridgeState.update { it.copy(lastError = null, diagnosticsError = null) }
        RecentDiagnostics.clear()
        val failure = BridgeFailure.technical("Retained failure")

        BridgeState.update { it.copy(lastError = failure) }
        BridgeState.update { it.copy(watchConnected = true) }

        assertEquals(1, RecentDiagnostics.entries.value.single().count)
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
        ).withPebbleConnectionFailure(BridgeFailure.technical("Core info unavailable"))

        assertFalse(failed.watchConnected)
        assertEquals("Core info unavailable", failed.diagnosticsError?.technicalDetail)
    }

    @Test fun healthyDiagnosticsDoNotEraseAnUnrelatedRuntimeFailure() {
        val failure = BridgeFailure(BridgeFailureKind.COMMAND_RESULT_DELIVERY_FAILED)
        val refreshed = BridgeStatus(lastError = failure).withDiagnosticsSnapshot(
            recordingState = BridgeProtocol.RecordingState.RECORDING,
            activeLocusProfile = "Walking",
            sampledAtMillis = 1_000L,
            currentHeartRate = 120,
            error = null,
        )

        assertEquals(failure, refreshed.lastError)
        assertNull(refreshed.diagnosticsError)
    }
}
