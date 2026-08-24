package io.github.christianherget.trackglance.bridge

import io.github.christianherget.trackglance.bridge.pebble.TrustAdmission
import io.github.christianherget.trackglance.bridge.pebble.TrustLeaseResult
import io.github.christianherget.trackglance.bridge.core.BoundedAbandonableCallExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchAppLauncherTest {
    private val admission = TrustAdmission(1)

    @Test fun trustedConnectedWatchStartsThroughTheAdmittedBoundary() = runBlocking {
        var started = emptyList<String>()
        val launcher = launcher(start = { started = it })

        assertEquals(WatchAppLaunchResult.STARTED, launcher.launch())
        assertEquals(listOf("watch-a"), started)
    }

    @Test fun noConnectedWatchIsDistinctAndDoesNotStart() = runBlocking {
        var starts = 0
        val launcher = launcher(watches = { emptyList() }, start = { starts++ })

        assertEquals(WatchAppLaunchResult.NO_CONNECTED_WATCH, launcher.launch())
        assertEquals(0, starts)
    }

    @Test fun untrustedAndStaleCompanionsAreDistinct() = runBlocking {
        assertEquals(
            WatchAppLaunchResult.UNTRUSTED_COMPANION,
            launcher(trusted = { false }).launch(),
        )
        assertEquals(
            WatchAppLaunchResult.STALE_COMPANION,
            launcher(gate = { _, _ -> TrustLeaseResult.Stale }).launch(),
        )
    }

    @Test fun lookupAndLaunchFailuresAreDistinct() = runBlocking {
        assertEquals(
            WatchAppLaunchResult.LOOKUP_FAILED,
            launcher(watches = { error("lookup") }).launch(),
        )
        assertEquals(
            WatchAppLaunchResult.LAUNCH_FAILED,
            launcher(start = { error("launch") }).launch(),
        )
    }

    @Test fun timeoutIsReportedAndExternalCancellationIsPreserved() = runBlocking {
        assertEquals(
            WatchAppLaunchResult.TIMED_OUT,
            launcher(watches = { delay(100); listOf("late") }, timeoutMillis = 10).launch(),
        )
        val running = async { launcher(watches = { awaitCancellation() }).launch() }
        delay(10)
        running.cancel()
        try {
            running.await()
        } catch (_: CancellationException) {
            // Expected: caller cancellation is never converted to an operational result.
        }
        assertTrue(running.isCancelled)
    }

    @Test fun blockingLookupIsReallyBoundedAndAReleasedWorkerRecovers() = runBlocking {
        val workers = BoundedAbandonableCallExecutor(1, "watch-launch-test")
        val lookupStarted = CountDownLatch(1)
        val releaseLookup = CountDownLatch(1)
        val lookupExited = CountDownLatch(1)
        try {
            val blocked = launcher(
                watches = {
                    workers.run {
                        lookupStarted.countDown()
                        try {
                            releaseLookup.await()
                            listOf("late-watch")
                        } finally {
                            lookupExited.countDown()
                        }
                    }
                },
                timeoutMillis = 25,
            )

            assertEquals(WatchAppLaunchResult.TIMED_OUT, blocked.launch())
            assertTrue(lookupStarted.await(1, TimeUnit.SECONDS))
            releaseLookup.countDown()
            assertTrue(lookupExited.await(1, TimeUnit.SECONDS))
            assertEquals(
                WatchAppLaunchResult.STARTED,
                launcher(watches = { workers.run { listOf("recovered-watch") } }).launch(),
            )
        } finally {
            releaseLookup.countDown()
            workers.close()
        }
    }

    @Test fun repeatedLocusIntentsAreSafeAndOtherIntentsAreIgnored() = runBlocking {
        var starts = 0
        val launcher = launcher(start = { starts++ })

        assertTrue(isLocusWatchLaunchIntent("locus.api.android.INTENT_ITEM_MAIN_FUNCTION"))
        assertFalse(isLocusWatchLaunchIntent("android.intent.action.MAIN"))
        assertEquals(WatchAppLaunchResult.STARTED, launcher.launch())
        assertEquals(WatchAppLaunchResult.STARTED, launcher.launch())
        assertEquals(2, starts)
    }

    private fun launcher(
        trusted: suspend () -> Boolean = { true },
        gate: suspend (
            TrustAdmission,
            suspend () -> WatchAppLaunchResult,
        ) -> TrustLeaseResult<WatchAppLaunchResult> = { _, block ->
            TrustLeaseResult.Admitted(block())
        },
        watches: suspend () -> List<String> = { listOf("watch-a") },
        start: suspend (List<String>) -> Unit = {},
        timeoutMillis: Long = 1_000,
    ) = WatchAppLauncher(
        ensureTrusted = trusted,
        captureAdmission = { admission },
        underAdmission = gate,
        connectedWatchIds = watches,
        startWatchApp = start,
        timeoutMillis = timeoutMillis,
    )
}
