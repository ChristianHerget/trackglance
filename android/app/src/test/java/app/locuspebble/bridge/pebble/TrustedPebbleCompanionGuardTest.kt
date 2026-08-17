package app.locuspebble.bridge.pebble

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedPebbleCompanionGuardTest {
    @Test fun successfulBlockingInitializationIsIdempotentAcrossComponentStartup() {
        var selected: String? = null
        var selections = 0
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf(TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = { packageName ->
                selections++
                selected = packageName
            },
            selectedPackage = { selected },
        )

        assertTrue(pin.initializeBlocking())
        assertTrue(pin.initializeBlocking())
        assertEquals(1, selections)
    }

    @Test fun boundedInitializationFailsClosedWhenPickerStorageNeverReturns() = runBlocking {
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf(TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = { awaitCancellation() },
            selectedPackage = { TRUSTED_CORE_APP_PACKAGE },
        )

        assertFalse(pin.initializeBounded(timeoutMillis = 25))
        assertFalse(pin.guard.isTrusted())
    }

    @Test fun boundedTrustRecoveryAlsoBoundsAStalledSelectionLookup() = runBlocking {
        var selectionLookupStalls = false
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf(TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = {},
            selectedPackage = {
                if (selectionLookupStalls) awaitCancellation()
                TRUSTED_CORE_APP_PACKAGE
            },
        )
        assertTrue(pin.initialize())
        selectionLookupStalls = true

        assertFalse(pin.ensureTrustedBounded(timeoutMillis = 25))
    }

    @Test fun initializationDisablesAutoSelectionAndPinsTheExactCorePackage() = runBlocking {
        var autoSelectionEnabled = true
        var selected: String? = "attacker.example"
        val selections = mutableListOf<String>()
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = { autoSelectionEnabled = false },
            eligiblePackages = { listOf("attacker.example", TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = { packageName -> selections += packageName; selected = packageName },
            selectedPackage = { selected },
        )

        assertFalse(pin.guard.isTrusted())
        assertTrue(pin.initialize())
        assertFalse(autoSelectionEnabled)
        assertEquals(listOf(TRUSTED_CORE_APP_PACKAGE), selections)
        assertTrue(pin.guard.isTrusted())

        selected = "attacker.example"
        assertFalse(pin.guard.isTrusted())
    }

    @Test fun unavailableOrFailingCoreSelectionLeavesInitializationFailClosed() = runBlocking {
        var selections = 0
        val unavailable = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf("attacker.example") },
            selectPackage = { selections++ },
            selectedPackage = { TRUSTED_CORE_APP_PACKAGE },
        )
        assertFalse(unavailable.initialize())
        assertFalse(unavailable.guard.isTrusted())
        assertEquals(0, selections)

        val failing = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf(TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = { error("picker storage unavailable") },
            selectedPackage = { TRUSTED_CORE_APP_PACKAGE },
        )
        assertFalse(failing.initialize())
        assertFalse(failing.guard.isTrusted())
    }

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
