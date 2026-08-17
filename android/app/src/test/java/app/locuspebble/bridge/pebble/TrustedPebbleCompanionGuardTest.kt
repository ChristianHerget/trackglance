package app.locuspebble.bridge.pebble

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedPebbleCompanionGuardTest {
    @Test fun onlyTheExactSupportedCoreAppPackageIsTrusted() = runBlocking {
        assertTrue(TrustedPebbleCompanionGuard { TRUSTED_CORE_APP_PACKAGE }.isTrusted())
        assertFalse(TrustedPebbleCompanionGuard { null }.isTrusted())
        assertFalse(TrustedPebbleCompanionGuard { "attacker.example" }.isTrusted())
        assertFalse(TrustedPebbleCompanionGuard { "CoreDevices.CoreApp" }.isTrusted())
    }

    @Test fun lookupFailuresFailClosedWithoutSwallowingCancellation() = runBlocking {
        assertFalse(TrustedPebbleCompanionGuard { error("picker unavailable") }.isTrusted())
        assertThrows(CancellationException::class.java) {
            runBlocking {
                TrustedPebbleCompanionGuard { throw CancellationException("cancelled") }.isTrusted()
            }
        }
        Unit
    }

    @Test fun lifecycleMutationsRemainOrderedAcrossSuspendingTrustChecks() = runBlocking {
        val lookups = AtomicInteger()
        val firstLookupStarted = CompletableDeferred<Unit>()
        val releaseFirstLookup = CompletableDeferred<Unit>()
        val mutations = mutableListOf<String>()
        val callbacks = SerializedTrustedLifecycleCallbacks(
            TrustedPebbleCompanionGuard {
                if (lookups.incrementAndGet() == 1) {
                    firstLookupStarted.complete(Unit)
                    releaseFirstLookup.await()
                }
                TRUSTED_CORE_APP_PACKAGE
            },
        )

        val opened = async(start = CoroutineStart.UNDISPATCHED) {
            callbacks.runIfTrusted { mutations += "opened" }
        }
        firstLookupStarted.await()
        val closed = async(start = CoroutineStart.UNDISPATCHED) {
            callbacks.runIfTrusted { mutations += "closed" }
        }

        assertEquals(1, lookups.get())
        assertTrue(mutations.isEmpty())
        releaseFirstLookup.complete(Unit)
        awaitAll(opened, closed)

        assertEquals(listOf("opened", "closed"), mutations)
    }
}
