package app.locuspebble.bridge

import app.locuspebble.bridge.core.BoundedAbandonableCallExecutor
import app.locuspebble.bridge.core.RefreshMode
import app.locuspebble.bridge.core.loadOffMain
import app.locuspebble.bridge.pebble.SerializedCoreTrustLeases
import app.locuspebble.bridge.pebble.TrustAdmission
import app.locuspebble.bridge.pebble.TrustLeaseResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffMainAndForeignQueryTest {
    @Test fun refreshPreferenceConstructorUsesSafeDefaultWithoutReadingStorage() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "preference-io")
        }.asCoroutineDispatcher()
        var reads = 0
        var readThread = ""
        try {
            val state = RefreshModePreferenceState(
                readPreference = {
                    reads++
                    readThread = Thread.currentThread().name
                    RefreshMode.TEN_SECONDS
                },
                ioDispatcher = dispatcher,
            )

            assertEquals(0, reads)
            assertEquals(RefreshMode.ADAPTIVE, state.selection.value)
            state.load()
            assertEquals(1, reads)
            assertTrue(readThread.startsWith("preference-io"))
            assertEquals(RefreshMode.TEN_SECONDS, state.selection.value)
        } finally {
            dispatcher.close()
        }
    }

    @Test fun runtimeFactoryHelperExecutesOnItsIoDispatcherNotTheCaller() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "runtime-storage-io")
        }.asCoroutineDispatcher()
        try {
            val caller = Thread.currentThread().name
            val constructionThread = loadOffMain(dispatcher) { Thread.currentThread().name }
            assertTrue(constructionThread.startsWith("runtime-storage-io"))
            assertFalse(caller == constructionThread)
        } finally {
            dispatcher.close()
        }
    }

    @Test fun blockedForeignQueryDoesNotDelayRevocationAndReapprovalQuietlyDiscardsItsResult() = runBlocking {
        val workers = BoundedAbandonableCallExecutor(1, "provider-query-test")
        val leases = SerializedCoreTrustLeases()
        val queryStarted = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        var generation = 7L
        var trusted = true
        try {
            val query = async(start = CoroutineStart.UNDISPATCHED) {
                generationGuardedForeignQuery(
                    executor = workers,
                    timeoutMillis = 2_000,
                    admit = {
                        leases.withOutbound { TrustAdmission(generation).takeIf { trusted } }
                    },
                    query = {
                        queryStarted.countDown()
                        releaseQuery.await()
                        "stale-watch"
                    },
                    publishIfCurrent = { admitted, _ ->
                        leases.withOutbound {
                            when {
                                admitted.generation != generation -> TrustLeaseResult.Stale
                                !trusted -> TrustLeaseResult.Untrusted
                                else -> TrustLeaseResult.Admitted(Unit)
                            }
                        }
                    },
                )
            }
            assertTrue(queryStarted.await(2, TimeUnit.SECONDS))

            leases.mutateTrust {
                generation++
                // Models signer B already being approved when signer A's provider result returns.
                trusted = true
            }
            releaseQuery.countDown()

            assertEquals(GuardedForeignQueryOutcome.STALE, query.await())
        } finally {
            releaseQuery.countDown()
            workers.close()
        }
    }
}
