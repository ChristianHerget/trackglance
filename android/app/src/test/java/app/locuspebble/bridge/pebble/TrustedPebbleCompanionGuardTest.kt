package app.locuspebble.bridge.pebble

import app.locuspebble.bridge.core.BoundedAbandonableCallExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedPebbleCompanionGuardTest {
    @Test fun successfulInitializationIsIdempotentAcrossComponentStartup() = runBlocking {
        var selected: String? = "attacker.example"
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

        assertTrue(pin.initialize())
        assertTrue(pin.initialize())
        assertEquals(1, selections)
    }

    @Test fun boundedInitializationFailsClosedWhenPickerStorageNeverReturns() = runBlocking {
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf(TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = { awaitCancellation() },
            selectedPackage = { null },
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

    @Test fun firstCallbackWaitsForConcurrentAsyncInitializationThenRevalidates() = runBlocking {
        val selectionStarted = CompletableDeferred<Unit>()
        val releaseSelection = CompletableDeferred<Unit>()
        var selected: String? = null
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf(TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = {
                selectionStarted.complete(Unit)
                releaseSelection.await()
                selected = it
            },
            selectedPackage = { selected },
        )

        val startup = async(start = CoroutineStart.UNDISPATCHED) { pin.initializeBounded(1_000) }
        selectionStarted.await()
        val firstCallback = async(start = CoroutineStart.UNDISPATCHED) {
            pin.ensureTrustedBounded(1_000) && pin.guard.isTrusted()
        }

        assertFalse(firstCallback.isCompleted)
        releaseSelection.complete(Unit)
        assertTrue(startup.await())
        assertTrue(firstCallback.await())
    }

    @Test fun initializationDisablesAutoSelectionAndPinsTheExactCorePackage() = runBlocking {
        var autoSelectionEnabled = true
        var selected: String? = null
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

    @Test fun initializationRecoversAStaleNonCorePickerSelection() = runBlocking {
        var selected: String? = "attacker.example"
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf("attacker.example", TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = { selected = it },
            selectedPackage = { selected },
        )

        assertTrue(pin.initialize())
        assertEquals(TRUSTED_CORE_APP_PACKAGE, selected)
        assertTrue(pin.guard.isTrusted())
    }

    @Test fun signerMustRemainTrustedAfterInitialization() = runBlocking {
        var selected: String? = null
        var signerTrusted = true
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf(TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = { selected = it },
            selectedPackage = { selected },
            signerTrusted = { signerTrusted },
        )

        assertTrue(pin.initialize())
        assertTrue(pin.guard.isTrusted())
        signerTrusted = false
        assertFalse(pin.guard.isTrusted())
    }

    @Test fun unavailableOrFailingCoreSelectionLeavesInitializationFailClosed() = runBlocking {
        var selections = 0
        val unavailable = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf("attacker.example") },
            selectPackage = { selections++ },
            selectedPackage = { null },
        )
        assertFalse(unavailable.initialize())
        assertFalse(unavailable.guard.isTrusted())
        assertEquals(0, selections)

        val failing = TrustedPebbleCompanionPin(
            disableAutoSelect = {},
            eligiblePackages = { listOf(TRUSTED_CORE_APP_PACKAGE) },
            selectPackage = { error("picker storage unavailable") },
            selectedPackage = { null },
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

    @Test fun suspendedMessageObservationCannotReopenAfterALaterCloseCallback() = runBlocking {
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

        val message = async(start = CoroutineStart.UNDISPATCHED) {
            callbacks.runIfTrusted(false) {
                mutations += "observed"
                true
            }
        }
        firstLookupStarted.await()
        val closed = async(start = CoroutineStart.UNDISPATCHED) {
            callbacks.runIfTrusted { mutations += "closed" }
        }

        assertEquals(1, lookups.get())
        assertTrue(mutations.isEmpty())
        releaseFirstLookup.complete(Unit)
        awaitAll(message, closed)

        assertEquals(listOf("observed", "closed"), mutations)
    }

    @Test fun rejectedLifecycleWorkTriggersTheTrustLossReset() = runBlocking {
        var resets = 0
        val callbacks = SerializedTrustedLifecycleCallbacks(
            guard = TrustedPebbleCompanionGuard { "attacker.example" },
            onTrustLost = { resets++ },
        )

        assertFalse(callbacks.runIfTrusted { error("untrusted work must not run") })
        assertEquals(1, resets)
    }

    @Test fun trustMutationWaitsForAdmittedOutboundRequestAndPrecedesTheNextOne() = runBlocking {
        val leases = SerializedCoreTrustLeases()
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            leases.withOutbound {
                events += "request-started"
                requestStarted.complete(Unit)
                releaseRequest.await()
                events += "request-finished"
            }
        }
        requestStarted.await()
        val revoke = async(start = CoroutineStart.UNDISPATCHED) {
            leases.mutateTrust { events += "revoked" }
        }

        assertFalse(revoke.isCompleted)
        releaseRequest.complete(Unit)
        request.await()
        revoke.await()
        leases.withOutbound { events += "later-request-check" }

        assertEquals(
            listOf("request-started", "request-finished", "revoked", "later-request-check"),
            events,
        )
    }

    @Test fun trustMutationWaitsForAnAdmittedCommandIncludingItsNestedResponse() = runBlocking {
        val leases = SerializedCoreTrustLeases()
        val commandStarted = CompletableDeferred<Unit>()
        val releaseCommand = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val command = async(start = CoroutineStart.UNDISPATCHED) {
            leases.withInbound {
                events += "command-admitted"
                commandStarted.complete(Unit)
                releaseCommand.await()
                leases.withOutbound { events += "command-response" }
            }
        }
        commandStarted.await()
        val revoke = async(start = CoroutineStart.UNDISPATCHED) {
            leases.mutateTrust { events += "revoked" }
        }

        assertFalse(revoke.isCompleted)
        releaseCommand.complete(Unit)
        command.await()
        revoke.await()

        assertEquals(listOf("command-admitted", "command-response", "revoked"), events)
    }

    @Test fun blockingTrustPersistenceDoesNotRetainRevocationLeasesAndTimesOutFailClosed() =
        runBlocking {
            val leases = SerializedCoreTrustLeases()
            val workers = BoundedAbandonableCallExecutor(1, "trust-test")
            val persistenceStarted = CountDownLatch(1)
            val releasePersistence = CountDownLatch(1)
            var pinInvalidations = 0
            var runtimeInvalidations = 0
            val coordinator = CompanionTrustMutationCoordinator(
                leases = leases,
                persistenceExecutor = workers,
                invalidateRuntime = { runtimeInvalidations++ },
                persistenceTimeoutMillis = 30,
            )
            try {
                val mutation = async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.mutate(
                        admitAfterSuccess = true,
                        invalidatePin = { pinInvalidations++ },
                        persist = {
                            persistenceStarted.countDown()
                            releasePersistence.await()
                            true
                        },
                    )
                }
                assertTrue(persistenceStarted.await(2, TimeUnit.SECONDS))

                // Persistence is still blocked, yet operational leases are already available.
                assertEquals("progress", leases.withOutbound { "progress" })
                assertFalse(mutation.await())
                assertFalse(coordinator.isAdmitted)
                assertEquals(1, pinInvalidations)
                assertEquals(1, runtimeInvalidations)
            } finally {
                releasePersistence.countDown()
                workers.close()
            }
        }

    @Test fun cancellationAfterTrustInvalidationCannotReauthorizeOrOvertakeBlockedWrite() = runBlocking {
        val leases = SerializedCoreTrustLeases()
        val workers = BoundedAbandonableCallExecutor(1, "trust-cancel-test")
        val persistenceStarted = CountDownLatch(1)
        val releasePersistence = CountDownLatch(1)
        var resets = 0
        val coordinator = CompanionTrustMutationCoordinator(
            leases = leases,
            persistenceExecutor = workers,
            invalidateRuntime = { resets++ },
            persistenceTimeoutMillis = 5_000,
        )
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.mutate(true, {}, persist = {
                    persistenceStarted.countDown()
                    releasePersistence.await()
                    true
                })
            }
            assertTrue(persistenceStarted.await(2, TimeUnit.SECONDS))
            first.cancelAndJoin()

            assertFalse(coordinator.isAdmitted)
            assertEquals(1, resets)
            assertFalse(coordinator.mutate(true, {}, persist = { true }))
            assertFalse(coordinator.isAdmitted)
        } finally {
            releasePersistence.countDown()
            workers.close()
        }
    }

    @Test fun successfulReapprovalInvalidatesEveryAdmissionFromThePreviousSignerGeneration() =
        runBlocking {
            val workers = BoundedAbandonableCallExecutor(1, "trust-generation-test")
            val coordinator = CompanionTrustMutationCoordinator(
                leases = SerializedCoreTrustLeases(),
                persistenceExecutor = workers,
                invalidateRuntime = {},
            )
            try {
                val oldAdmission = TrustAdmission(coordinator.generation)
                assertTrue(coordinator.isCurrent(oldAdmission))

                assertTrue(coordinator.mutate(true, {}, persist = { true }))
                val newAdmission = TrustAdmission(coordinator.generation)

                assertFalse(coordinator.isCurrent(oldAdmission))
                assertTrue(coordinator.isCurrent(newAdmission))
            } finally {
                workers.close()
            }
        }
}
