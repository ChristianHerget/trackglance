package io.github.christianherget.trackglance.bridge.core

import io.github.christianherget.trackglance.bridge.SupervisionEvents
import io.github.christianherget.trackglance.bridge.locus.LocusBridgeGateway
import io.github.christianherget.trackglance.bridge.locus.RecordingProfilesResult
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRuntimeSupervisionTest {
    private val watch = WatchIdentifier("supervision-watch")
    private val events = Events()
    private val locus = SensorLocus()

    @Test
    fun onlyExplicitTrustedCallbacksChangeClosure() {
        var trusted = true
        val runtime =
            runtime(RecordingSender(), locus, supervision = events, admissionCurrent = { trusted })
        try {
            runtime.watchObserved(watch, TEST_ADMISSION)
            assertEquals(0, events.opens)
            runtime.watchAppOpened(watch, TEST_ADMISSION)
            runtime.watchAppClosed(WatchIdentifier("unrelated-watch"), TEST_ADMISSION)
            assertEquals(0, events.closes)
            runtime.watchAppClosed(watch, TEST_ADMISSION)
            assertEquals(1, events.opens)
            assertEquals(1, events.closes)
            runtime.watchObserved(watch, TEST_ADMISSION)
            runtime.companionTrustLost()
            assertEquals(1, events.opens)
            assertEquals(1, events.closes)
            trusted = false
            runtime.watchAppOpened(watch, TEST_ADMISSION)
            runtime.watchAppClosed(watch, TEST_ADMISSION)
            assertEquals(1, events.opens)
            assertEquals(1, events.closes)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun zeroStepsLearnButDuplicateUnavailableInvalidAndWrongRecordingDoNot() = runBlocking {
        val runtime = runtime(RecordingSender(), locus, supervision = events)
        try {
            runtime.watchAppOpened(watch, TEST_ADMISSION)
            runtime.establishWatchSession(watch, 7, TEST_ADMISSION)
            assertTrue(runtime.handleStepDelta(watch, 7, 0, 0, 123, TEST_ADMISSION))
            assertFalse(runtime.handleStepDelta(watch, 7, 0, 1, 123, TEST_ADMISSION))
            assertTrue(
                runtime.handleStepDelta(
                    watch,
                    7,
                    1,
                    BridgeProtocol.UNAVAILABLE,
                    123,
                    TEST_ADMISSION,
                )
            )
            assertFalse(runtime.handleStepDelta(watch, 7, 2, -2, 123, TEST_ADMISSION))
            assertFalse(runtime.handleStepDelta(watch, 7, 3, 1, 456, TEST_ADMISSION))
            assertEquals(listOf(SupervisionSource.STEPS), events.sources)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun heartRateLearnsBeforeFailedForwardingButRejectsDuplicateInvalidAndStale() {
        val runtime = runtime(RecordingSender(), locus, supervision = events)
        try {
            assertTrue(runtime.handleHeartRate(watch, 9, 1, 120, 1000, TEST_ADMISSION))
            assertFalse(runtime.handleHeartRate(watch, 9, 1, 120, 1000, TEST_ADMISSION))
            assertFalse(runtime.handleHeartRate(watch, 9, 2, -1, 1000, TEST_ADMISSION))
            assertFalse(runtime.handleHeartRate(watch, 9, 3, 120, 1, TEST_ADMISSION))
            assertEquals(listOf(SupervisionSource.HEART_RATE), events.sources)
        } finally {
            runtime.close()
        }
    }

    private class Events : SupervisionEvents {
        var opens = 0
        var closes = 0
        val sources = mutableListOf<SupervisionSource>()

        override fun observed(
            snapshot: BridgeProtocol.Snapshot,
            source: SupervisionSource?,
            token: Long?,
            observationToken: Long?,
        ) {
            source?.let(sources::add)
        }

        override fun opened() {
            opens++
        }

        override fun closed() {
            closes++
        }
    }

    private class SensorLocus : LocusBridgeGateway {
        override fun readSnapshot(nowMillis: Long) =
            BridgeProtocol.Snapshot(
                state = BridgeProtocol.RecordingState.RECORDING,
                recordingStartMillis = 123,
                sampledAtEpochSeconds = nowMillis / 1000,
            )

        override fun sendHeartRate(bpm: Int) = false

        override fun recordingProfiles() = RecordingProfilesResult.Success(emptyList())

        override fun execute(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ) = BridgeProtocol.Result.FAILED
    }
}
