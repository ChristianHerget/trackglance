package app.locuspebble.bridge.locus

import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import app.locuspebble.bridge.protocol.BridgeProtocol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in contract test against a real Locus Map installation.
 *
 * This test creates and saves a short recording. It is skipped unless
 * runLocusIntegration=true is supplied to the instrumentation runner.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LocusEndToEndTest {
    private lateinit var gateway: LocusGateway
    private var testStartedRecording = false

    @Before fun requireExplicitOptInAndIdleLocus() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Real Locus test was not explicitly enabled", arguments.getString("runLocusIntegration") == "true")
        gateway = LocusGateway(ApplicationProvider.getApplicationContext())
        assumeTrue("Locus Map is unavailable", gateway.readSnapshot().state != BridgeProtocol.RecordingState.UNAVAILABLE)
        assumeTrue("Refusing to modify an existing recording", gateway.readSnapshot().state == BridgeProtocol.RecordingState.STOPPED)
    }

    @After fun stopRecordingIfTestFailedMidSequence() {
        if (!testStartedRecording) return
        runCatching { gateway.execute(BridgeProtocol.Command.STOP_SAVE) }
        assertTrue("Cleanup could not stop the test recording", awaitState(BridgeProtocol.RecordingState.STOPPED))
    }

    @Test fun startPauseResumeAndStopRoundTripThroughLocus() {
        val profileName = (gateway.recordingProfiles() as? RecordingProfilesResult.Success)
            ?.names
            ?.firstOrNull()
        assumeTrue("Locus has no recording profile", profileName != null)
        assertEquals(
            "Locus rejected named Start",
            BridgeProtocol.Result.OK,
            gateway.execute(BridgeProtocol.Command.START, profileName),
        )
        testStartedRecording = true
        assertTrue("Locus did not enter recording state", awaitState(BridgeProtocol.RecordingState.RECORDING))
        assertEquals(profileName, gateway.readSnapshot().locusProfileName)
        assertTrue("Locus HR task was not sent", gateway.sendHeartRate(123))
        assertTrue("Locus did not report injected HR", awaitHeartRate(123))
        pauseForObservation()

        assertCommand(BridgeProtocol.Command.PAUSE_RESUME)
        assertTrue("Locus did not pause", awaitState(BridgeProtocol.RecordingState.PAUSED))
        pauseForObservation()

        assertCommand(BridgeProtocol.Command.PAUSE_RESUME)
        assertTrue("Locus did not resume", awaitState(BridgeProtocol.RecordingState.RECORDING))
        pauseForObservation()

        assertEquals(
            "Locus rejected the dictated waypoint",
            BridgeProtocol.Result.OK,
            gateway.execute(
                BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE,
                waypointName = "Pebble integration note",
            ),
        )

        assertCommand(BridgeProtocol.Command.STOP_SAVE)
        assertTrue("Locus did not stop", awaitState(BridgeProtocol.RecordingState.STOPPED))
        testStartedRecording = false
    }

    private fun assertCommand(command: BridgeProtocol.Command) {
        assertEquals("Locus rejected $command", BridgeProtocol.Result.OK, gateway.execute(command))
    }

    private fun pauseForObservation() {
        val delay = InstrumentationRegistry.getArguments()
            .getString("observationDelayMillis")?.toLongOrNull() ?: 0L
        if (delay > 0) Thread.sleep(delay.coerceAtMost(10_000L))
    }

    private fun awaitState(expected: BridgeProtocol.RecordingState, timeoutMillis: Long = 15_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (gateway.readSnapshot().state == expected) return true
            Thread.sleep(250)
        }
        return gateway.readSnapshot().state == expected
    }

    private fun awaitHeartRate(expected: Int, timeoutMillis: Long = 5_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (gateway.readSnapshot().currentHeartRate == expected) return true
            Thread.sleep(100)
        }
        return gateway.readSnapshot().currentHeartRate == expected
    }
}
