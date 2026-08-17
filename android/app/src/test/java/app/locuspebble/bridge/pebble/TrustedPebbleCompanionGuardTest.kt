package app.locuspebble.bridge.pebble

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedPebbleCompanionGuardTest {
    @Test fun pinSelectsOnlyTheExactCorePackage() = runBlocking {
        var selected: String? = null
        var autoSelect = true
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = { autoSelect = false },
            eligiblePackages = { listOf("other.app", TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = { selected = it },
            selectedPackage = { selected },
        )

        assertTrue(pin.initialize())
        assertFalse(autoSelect)
        assertEquals(TRUSTED_CORE_APP_PACKAGE, selected)
        assertTrue(pin.guard.isTrusted())
    }

    @Test fun missingCorePackageFailsClosed() = runBlocking {
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf("other.app") },
            selectPackage = { error("must not select") },
            selectedPackage = { null },
        )

        assertFalse(pin.initialize())
        assertFalse(pin.guard.isTrusted())
    }

    @Test fun guardRequiresInitializationAndExactSelection() = runBlocking {
        var initialized = false
        var selected: String? = TRUSTED_CORE_APP_PACKAGE
        val guard = TrustedPebbleCompanionGuard(
            initialized = { initialized },
            selectedPackage = { selected },
        )

        assertFalse(guard.isTrusted())
        initialized = true
        assertTrue(guard.isTrusted())
        selected = "attacker.example"
        assertFalse(guard.isTrusted())
    }

    @Test fun guardPropagatesCancellationButFailsClosedOnOtherErrors() = runBlocking {
        assertFalse(TrustedPebbleCompanionGuard { error("picker unavailable") }.isTrusted())
        try {
            TrustedPebbleCompanionGuard { throw CancellationException("cancelled") }.isTrusted()
            error("expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test fun inboundAndOutboundSessionLeasesRemainIndependent() = runBlocking {
        val leases = SerializedCoreSessionLeases()
        val inboundEntered = CompletableDeferred<Unit>()
        val releaseInbound = CompletableDeferred<Unit>()
        val inbound = async {
            leases.withInbound {
                inboundEntered.complete(Unit)
                releaseInbound.await()
            }
        }
        inboundEntered.await()

        val outboundCompleted = CompletableDeferred<Unit>()
        leases.withOutbound { outboundCompleted.complete(Unit) }
        assertTrue(outboundCompleted.isCompleted)
        releaseInbound.complete(Unit)
        inbound.await()
    }
}
