package io.github.christianherget.locuspebble.bridge.core

import io.github.christianherget.locuspebble.bridge.locus.CommandExecution
import io.github.christianherget.locuspebble.bridge.locus.LocusBridgeGateway
import io.github.christianherget.locuspebble.bridge.locus.RecordingProfilesResult
import io.github.christianherget.locuspebble.bridge.pebble.PebbleDictionarySender
import io.github.christianherget.locuspebble.bridge.pebble.PebbleMessages
import io.github.christianherget.locuspebble.bridge.pebble.ReliablePebbleTransport
import io.github.christianherget.locuspebble.bridge.pebble.SerializedCoreSessionLeases
import io.github.christianherget.locuspebble.bridge.pebble.TrustAdmission
import io.github.christianherget.locuspebble.bridge.pebble.TrustLeaseResult
import io.github.christianherget.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRuntimeTest {
    @Test fun openingAnotherWatchReplacesTheActiveLifecycle() {
        val sender = RecordingSender()
        val runtime = runtime(sender = sender)
        val watchA = WatchIdentifier("watch-a")
        val watchB = WatchIdentifier("watch-b")
        try {
            runtime.watchAppOpened(watchA)
            runtime.watchAppOpened(watchB)
            assertTrue(BridgeState.status.value.watchAppOpen)
            assertEquals(listOf(watchA), sender.calls[0].watches)
            assertEquals(listOf(watchB), sender.calls[1].watches)

            runtime.watchAppClosed(watchA)
            assertTrue(BridgeState.status.value.watchAppOpen)
            runtime.watchAppClosed(watchB)
            assertFalse(BridgeState.status.value.watchAppOpen)
        } finally {
            runtime.close()
        }
    }

    @Test fun inboundMessageAfterProcessRestartRecoversTheOpenWatchLifecycle() {
        val sender = RecordingSender()
        val runtime = runtime(sender = sender)
        val watch = WatchIdentifier("watch-after-restart")
        try {
            runtime.watchObserved(watch)
            assertTrue(BridgeState.status.value.watchAppOpen)
            assertEquals(listOf(watch), sender.calls.single().watches)
        } finally {
            runtime.watchAppClosed(watch)
            runtime.close()
        }
    }

    @Test fun commandResultsAndRefreshesReturnOnlyToTheirSourceWatch() = runBlocking {
        val sender = RecordingSender()
        val locus = FakeLocus()
        val runtime = runtime(sender = sender, locus = locus)
        val watchA = WatchIdentifier("watch-a")
        val watchB = WatchIdentifier("watch-b")
        try {
            assertTrue(runtime.handleCommand(watchA, 7, 1, BridgeProtocol.Command.STOP_SAVE, null, null))
            assertTrue(runtime.handleCommand(watchA, 7, 1, BridgeProtocol.Command.STOP_SAVE, "ignored", null))
            assertTrue(runtime.handleCommand(watchB, 7, 1, BridgeProtocol.Command.STOP_SAVE, null, null))

            assertEquals(2, locus.executions)
            assertTrue(sender.calls.take(4).all { it.watches == listOf(watchA) })
            assertTrue(sender.calls.drop(4).take(2).all { it.watches == listOf(watchB) })

            assertTrue(runtime.handleCommand(watchA, 7, 1, BridgeProtocol.Command.START, "Hiking", null))
            assertEquals(2, locus.executions)
            assertEquals(
                BridgeProtocol.Result.FAILED.wire,
                PebbleMessages.signed32(sender.calls.last().dictionary, BridgeProtocol.Key.RESULT),
            )
            assertEquals(BridgeProtocol.Command.START, BridgeState.status.value.lastCommand)
            assertEquals(BridgeProtocol.Result.FAILED, BridgeState.status.value.lastCommandResult)
            assertEquals(null, BridgeState.status.value.lastWaypointName)
        } finally {
            runtime.close()
        }
    }

    @Test fun commandResultFollowsANewerAcceptedSnapshotAndLateOldDeliveryIsRejected() = runBlocking {
        val sender = ReceiverOrderingSender()
        val locus = StateChangingLocus()
        val runtime = runtime(sender = sender, locus = locus, maxAttempts = 2)
        val watch = WatchIdentifier("ordered-watch")
        try {
            val oldSnapshot = async { runtime.refresh(listOf(watch)) }
            sender.firstSnapshotAttemptStarted.await()
            val command = async {
                runtime.handleCommand(watch, 8, 1, BridgeProtocol.Command.START, "Hiking", null)
            }
            yield()

            assertEquals(0, locus.executions)
            assertFalse(command.isCompleted)

            sender.releaseFirstSnapshotAttempt.complete(Unit)
            assertFalse(oldSnapshot.await())
            assertTrue(command.await())

            assertEquals(
                listOf(
                    BridgeProtocol.MessageType.SNAPSHOT.wire,
                    BridgeProtocol.MessageType.SNAPSHOT.wire,
                    BridgeProtocol.MessageType.SNAPSHOT.wire,
                    BridgeProtocol.MessageType.COMMAND_RESULT.wire,
                ),
                sender.attemptTypes,
            )
            assertEquals(sender.snapshotEpochs[0], sender.snapshotEpochs[1])
            assertTrue(sender.snapshotEpochs[2] > sender.snapshotEpochs[0])
            assertEquals(sender.snapshotEpochs[2], sender.epochAcceptedBeforeResult)
            assertFalse(sender.deliverDelayedPreCommandSnapshot())
        } finally {
            runtime.close()
        }
    }

    @Test fun commandResultIsNotIssuedWhenThePostCommandSnapshotCannotBeDelivered() = runBlocking {
        val sender = SnapshotFailingSender()
        val locus = StateChangingLocus()
        val runtime = runtime(sender = sender, locus = locus)
        try {
            assertFalse(
                runtime.handleCommand(
                    WatchIdentifier("watch"),
                    8,
                    1,
                    BridgeProtocol.Command.START,
                    "Hiking",
                    null,
                ),
            )

            assertEquals(1, locus.executions)
            assertEquals(
                listOf(
                    BridgeProtocol.MessageType.SNAPSHOT.wire,
                ),
                sender.attemptTypes,
            )
        } finally {
            runtime.close()
        }
    }

    @Test fun delayedLocusTransitionIsObservedBeforeAnOkCommandResult() = runBlocking {
        val sender = RecordingSender()
        val locus = DelayedStateChangingLocus(transitionAfterPostExecuteReads = 3)
        val runtime = runtime(sender = sender, locus = locus)
        try {
            assertTrue(
                runtime.handleCommand(
                    WatchIdentifier("watch"),
                    8,
                    1,
                    BridgeProtocol.Command.START,
                    "Hiking",
                    null,
                ),
            )

            val barrierIndex = sender.calls.indexOfFirst { call ->
                PebbleMessages.signed32(
                    call.dictionary,
                    BridgeProtocol.Key.MESSAGE_TYPE,
                ) == BridgeProtocol.MessageType.SNAPSHOT.wire
            }
            val resultIndex = sender.calls.indexOfFirst { call ->
                PebbleMessages.signed32(
                    call.dictionary,
                    BridgeProtocol.Key.MESSAGE_TYPE,
                ) == BridgeProtocol.MessageType.COMMAND_RESULT.wire
            }
            assertTrue(barrierIndex in 0 until resultIndex)
            assertEquals(
                BridgeProtocol.RecordingState.RECORDING.wire,
                PebbleMessages.signed32(
                    sender.calls[barrierIndex].dictionary,
                    BridgeProtocol.Key.RECORDING_STATE,
                ),
            )
            assertEquals(
                BridgeProtocol.Result.OK.wire,
                PebbleMessages.signed32(
                    sender.calls[resultIndex].dictionary,
                    BridgeProtocol.Key.RESULT,
                ),
            )
            assertEquals(1, locus.executions)
            assertEquals(3, locus.postExecuteReads)
            assertEquals(2, sender.calls.size)
        } finally {
            runtime.close()
        }
    }

    @Test fun unconfirmedTransitionReturnsFailedAndDedupeSkipsObsoleteTargetPolling() = runBlocking {
        val sender = RecordingSender()
        val locus = DelayedStateChangingLocus(transitionAfterPostExecuteReads = null)
        val runtime = runtime(sender = sender, locus = locus)
        val watch = WatchIdentifier("watch")
        try {
            assertTrue(
                runtime.handleCommand(
                    watch,
                    8,
                    1,
                    BridgeProtocol.Command.START,
                    "Hiking",
                    null,
                ),
            )
            val firstResult = sender.calls.single { call ->
                PebbleMessages.signed32(
                    call.dictionary,
                    BridgeProtocol.Key.MESSAGE_TYPE,
                ) == BridgeProtocol.MessageType.COMMAND_RESULT.wire
            }
            assertEquals(
                BridgeProtocol.Result.FAILED.wire,
                PebbleMessages.signed32(firstResult.dictionary, BridgeProtocol.Key.RESULT),
            )
            val firstBarrier = sender.calls.first { call ->
                PebbleMessages.signed32(
                    call.dictionary,
                    BridgeProtocol.Key.MESSAGE_TYPE,
                ) == BridgeProtocol.MessageType.SNAPSHOT.wire
            }
            assertEquals(
                BridgeProtocol.RecordingState.STOPPED.wire,
                PebbleMessages.signed32(firstBarrier.dictionary, BridgeProtocol.Key.RECORDING_STATE),
            )
            val readsAfterFirstAttempt = locus.postExecuteReads
            assertTrue(readsAfterFirstAttempt > 1)

            sender.calls.clear()
            assertTrue(
                runtime.handleCommand(
                    watch,
                    8,
                    1,
                    BridgeProtocol.Command.START,
                    "Hiking",
                    null,
                ),
            )

            assertEquals(1, locus.executions)
            assertTrue(locus.postExecuteReads - readsAfterFirstAttempt <= 2)
            assertEquals(
                BridgeProtocol.Result.FAILED.wire,
                PebbleMessages.signed32(
                    sender.calls.single { call ->
                        PebbleMessages.signed32(
                            call.dictionary,
                            BridgeProtocol.Key.MESSAGE_TYPE,
                        ) == BridgeProtocol.MessageType.COMMAND_RESULT.wire
                    }.dictionary,
                    BridgeProtocol.Key.RESULT,
                ),
            )
        } finally {
            runtime.close()
        }
    }

    @Test fun pauseResumeUsesTheTargetFromTheGatewaysExactRoutingDecision() = runBlocking {
        val sender = RecordingSender()
        val locus = PauseResumeRoutingRaceLocus()
        val runtime = runtime(sender = sender, locus = locus)
        try {
            assertTrue(
                runtime.handleCommand(
                    WatchIdentifier("watch"),
                    8,
                    1,
                    BridgeProtocol.Command.PAUSE_RESUME,
                    null,
                    null,
                ),
            )

            val barrier = sender.calls.first { call ->
                PebbleMessages.signed32(
                    call.dictionary,
                    BridgeProtocol.Key.MESSAGE_TYPE,
                ) == BridgeProtocol.MessageType.SNAPSHOT.wire
            }
            assertEquals(
                BridgeProtocol.RecordingState.RECORDING.wire,
                PebbleMessages.signed32(barrier.dictionary, BridgeProtocol.Key.RECORDING_STATE),
            )
            assertEquals(2, locus.postExecuteReads)
            assertEquals(
                BridgeProtocol.Result.OK.wire,
                PebbleMessages.signed32(
                    sender.calls.single { call ->
                        PebbleMessages.signed32(
                            call.dictionary,
                            BridgeProtocol.Key.MESSAGE_TYPE,
                        ) == BridgeProtocol.MessageType.COMMAND_RESULT.wire
                    }.dictionary,
                    BridgeProtocol.Key.RESULT,
                ),
            )
        } finally {
            runtime.close()
        }
    }

    @Test fun commandFromAnExpiredConnectionSessionFailsBeforeMutation() =
        runBlocking {
            val currentGeneration = AtomicLong(1)
            val currentAdmission = { TrustAdmission(currentGeneration.get()) }
            val sender = AdmissionRecordingSender(currentAdmission)
            val locus = StateChangingLocus()
            val mutationGateReached = CompletableDeferred<Unit>()
            val releaseMutationGate = CompletableDeferred<Unit>()
            val runtime = runtime(
                sender = sender,
                locus = locus,
                trustedMutationGate = { admission, block ->
                    mutationGateReached.complete(Unit)
                    releaseMutationGate.await()
                    if (admission != currentAdmission()) {
                        TrustLeaseResult.Stale
                    } else {
                        block()
                        TrustLeaseResult.Admitted(Unit)
                    }
                },
                admissionCurrent = { it == currentAdmission() },
            )
            val watch = WatchIdentifier("watch")
            val sessionA = TrustAdmission(1)
            val sessionB = TrustAdmission(2)
            try {
                val oldCommand = async {
                    runtime.handleCommand(
                        watch,
                        8,
                        1,
                        BridgeProtocol.Command.START,
                        "Hiking",
                        null,
                        sessionA,
                    )
                }
                mutationGateReached.await()
                currentGeneration.set(sessionB.generation)
                releaseMutationGate.complete(Unit)

                assertFalse(oldCommand.await())
                assertEquals(0, locus.executions)
                assertTrue(sender.calls.isEmpty())

                // The begun record was durably completed FAILED, so B may retry it without action.
                assertTrue(
                    runtime.handleCommand(
                        watch,
                        8,
                        1,
                        BridgeProtocol.Command.START,
                        "Hiking",
                        null,
                        sessionB,
                    ),
                )
                assertEquals(0, locus.executions)
                assertEquals(listOf(sessionB, sessionB), sender.calls.map { it.admission })
                assertEquals(
                    listOf(
                        BridgeProtocol.MessageType.SNAPSHOT.wire,
                        BridgeProtocol.MessageType.COMMAND_RESULT.wire,
                    ),
                    sender.calls.map { call ->
                        PebbleMessages.signed32(
                            call.dictionary,
                            BridgeProtocol.Key.MESSAGE_TYPE,
                        )
                    },
                )
            } finally {
                releaseMutationGate.complete(Unit)
                runtime.close()
            }
        }

    @Test fun revocationWaitsOnlyForTheExactLocusActionNotConfirmationOrDelivery() = runBlocking {
        val leases = SerializedCoreSessionLeases()
        val currentGeneration = AtomicLong(1)
        val currentAdmission = { TrustAdmission(currentGeneration.get()) }
        val sender = AdmissionRecordingSender(currentAdmission, leases)
        val locus = BlockingCommandConfirmationLocus()
        val runtime = runtime(
            sender = sender,
            locus = locus,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ioDispatcher = Dispatchers.Default,
            trustedMutationGate = { admission, block ->
                leases.withInbound {
                    if (admission != currentAdmission()) {
                        TrustLeaseResult.Stale
                    } else {
                        block()
                        TrustLeaseResult.Admitted(Unit)
                    }
                }
            },
            admissionCurrent = { it == currentAdmission() },
        )
        val sessionA = TrustAdmission(1)
        try {
            val command = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                runtime.handleCommand(
                    WatchIdentifier("watch"),
                    8,
                    1,
                    BridgeProtocol.Command.START,
                    "Hiking",
                    null,
                    sessionA,
                )
            }
            withTimeout(5_000) { locus.actionStarted.await() }
            val revoke = async {
                leases.mutateSession {
                    currentGeneration.incrementAndGet()
                    runtime.companionTrustLost()
                }
            }
            yield()
            assertFalse(revoke.isCompleted)

            locus.releaseAction.countDown()
            withTimeout(5_000) { locus.confirmationReadStarted.await() }
            withTimeout(2_000) { revoke.await() }
            assertFalse(command.isCompleted)

            locus.releaseConfirmationRead.countDown()
            assertFalse(command.await())
            assertEquals(1, locus.executions)
            assertTrue(sender.calls.isEmpty())
        } finally {
            locus.releaseAction.countDown()
            locus.releaseConfirmationRead.countDown()
            runtime.close()
        }
    }

    @Test fun heartRateConsumerSurvivesOneSampleFailureAndRoutesTheNextUpdate() {
        val sender = RecordingSender()
        val locus = FakeLocus(heartRateFailures = 1)
        val runtime = runtime(sender = sender, locus = locus)
        val watch = WatchIdentifier("heart-rate-watch")
        try {
            assertTrue(runtime.handleHeartRate(watch, 9, 1, 120, 1_000))
            assertTrue(runtime.handleHeartRate(watch, 9, 2, 121, 1_000))

            assertEquals(2, locus.heartRateCalls)
            assertEquals(1, sender.calls.size)
            assertEquals(listOf(watch), sender.calls.single().watches)
        } finally {
            runtime.close()
        }
    }

    @Test fun queuedHeartRateSampleIsDroppedAcrossConnectionReset() = runBlocking {
        val sender = RecordingSender()
        val locus = FakeLocus()
        val leases = SerializedCoreSessionLeases()
        val consumerDequeuedSample = CompletableDeferred<Unit>()
        val releaseConsumer = CompletableDeferred<Unit>()
        val consumerFinished = CompletableDeferred<Unit>()
        val runtime = runtime(
            sender = sender,
            locus = locus,
            trustedWorkLease = { block ->
                consumerDequeuedSample.complete(Unit)
                try {
                    releaseConsumer.await()
                    leases.withInbound(block)
                } finally {
                    consumerFinished.complete(Unit)
                }
            },
        )
        try {
            assertTrue(runtime.handleHeartRate(WatchIdentifier("watch"), 9, 1, 120, 1_000))
            consumerDequeuedSample.await()

            // A connection reset uses this same inbound->outbound boundary.
            leases.mutateSession { runtime.companionTrustLost() }
            releaseConsumer.complete(Unit)
            consumerFinished.await()

            assertEquals(0, locus.heartRateCalls)
            assertTrue(sender.calls.isEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test fun queuedHeartRateSampleIsDroppedWhenTheDeferredSelectionGuardIsNowFalse() = runBlocking {
        val sender = RecordingSender()
        val locus = FakeLocus()
        val leases = SerializedCoreSessionLeases()
        val consumerReachedGuard = CompletableDeferred<Unit>()
        val releaseGuard = CompletableDeferred<Unit>()
        val consumerFinished = CompletableDeferred<Unit>()
        var trusted = true
        lateinit var runtime: BridgeRuntime
        runtime = runtime(
            sender = sender,
            locus = locus,
            trustedWorkLease = { block ->
                consumerReachedGuard.complete(Unit)
                releaseGuard.await()
                try {
                    leases.withInbound {
                        if (trusted) block() else runtime.companionTrustLost()
                    }
                } finally {
                    consumerFinished.complete(Unit)
                }
            },
        )
        try {
            assertTrue(runtime.handleHeartRate(WatchIdentifier("watch"), 9, 1, 120, 1_000))
            consumerReachedGuard.await()

            trusted = false
            releaseGuard.complete(Unit)
            consumerFinished.await()

            assertEquals(0, locus.heartRateCalls)
            assertTrue(sender.calls.isEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test fun revocationWaitsForAnAdmittedHeartRateMutationToFinish() = runBlocking {
        val sender = RecordingSender()
        val locus = BlockingHeartRateLocus()
        val leases = SerializedCoreSessionLeases()
        val runtime = runtime(
            sender = sender,
            locus = locus,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ioDispatcher = Dispatchers.Default,
            trustedWorkLease = leases::withInbound,
        )
        try {
            assertTrue(runtime.handleHeartRate(WatchIdentifier("watch"), 9, 1, 120, 1_000))
            assertTrue(locus.mutationStarted.await(5, TimeUnit.SECONDS))
            val revocation = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                leases.mutateSession { runtime.companionTrustLost() }
            }
            assertFalse(revocation.isCompleted)

            locus.releaseMutation.countDown()
            revocation.await()

            assertEquals(1, locus.heartRateCalls)
            assertTrue(sender.calls.isEmpty())
        } finally {
            locus.releaseMutation.countDown()
            runtime.close()
        }
    }

    @Test fun selectionLossClearsTheActiveWatchSoARealReopenStartsPollingAgain() {
        val sender = RecordingSender()
        val runtime = runtime(sender = sender)
        val watch = WatchIdentifier("watch")
        try {
            runtime.watchAppOpened(watch)
            val callsBeforeLoss = sender.calls.size
            assertTrue(callsBeforeLoss > 0)
            assertTrue(BridgeState.status.value.watchAppOpen)

            runtime.companionTrustLost()
            assertFalse(BridgeState.status.value.watchAppOpen)

            runtime.watchAppOpened(watch)
            assertTrue(BridgeState.status.value.watchAppOpen)
            assertTrue(sender.calls.size > callsBeforeLoss)
        } finally {
            runtime.close()
        }
    }

    @Test fun connectionResetCancelsOldPollingBeforeTheNewSessionReopens() = runBlocking {
        val currentGeneration = AtomicLong(1)
        val currentAdmission = { TrustAdmission(currentGeneration.get()) }
        val sender = AdmissionRecordingSender(currentAdmission)
        val locus = FirstReadBlockingLocus()
        val runtime = runtime(
            sender = sender,
            locus = locus,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            ioDispatcher = Dispatchers.Default,
            admissionCurrent = { it == currentAdmission() },
        )
        val sessionA = TrustAdmission(1)
        val sessionB = TrustAdmission(2)
        try {
            runtime.watchAppOpened(WatchIdentifier("watch-a"), sessionA)
            assertTrue(locus.firstReadStarted.await(5, TimeUnit.SECONDS))
            // This creates the separate immediate-refresh child while A's poll owns serialization.
            runtime.watchAppOpened(WatchIdentifier("watch-a-2"), sessionA)

            currentGeneration.set(sessionB.generation)
            runtime.companionTrustLost()
            runtime.watchAppOpened(WatchIdentifier("watch-b"), sessionB)
            locus.releaseFirstRead.countDown()

            withTimeout(5_000) {
                while (sender.calls.isEmpty()) yield()
            }
            assertEquals(listOf(sessionB), sender.calls.map { it.admission }.distinct())
            assertEquals(
                setOf(WatchIdentifier("watch-b")),
                sender.calls.flatMap { it.watches }.toSet(),
            )
        } finally {
            locus.releaseFirstRead.countDown()
            runtime.close()
        }
    }

    @Test fun staleSnapshotPublicationCannotOverwriteNewSessionDiagnostics() = runBlocking {
        val currentGeneration = AtomicLong(1)
        val currentAdmission = { TrustAdmission(currentGeneration.get()) }
        val sender = AdmissionRecordingSender(currentAdmission)
        val publicationReached = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        val runtime = runtime(
            sender = sender,
            locus = StateChangingLocus(),
            admissionCurrent = { it == currentAdmission() },
            trustedPublicationGate = { admission, block ->
                publicationReached.complete(Unit)
                releasePublication.await()
                if (admission != currentAdmission()) {
                    TrustLeaseResult.Stale
                } else {
                    block()
                    TrustLeaseResult.Admitted(Unit)
                }
            },
        )
        val sessionA = TrustAdmission(1)
        try {
            val staleRefresh = async {
                runtime.refresh(WatchIdentifier("watch"), sessionA)
            }
            publicationReached.await()
            currentGeneration.incrementAndGet()
            BridgeState.update {
                it.copy(
                    recordingState = BridgeProtocol.RecordingState.PAUSED,
                    lastError = "new-session-diagnostics",
                )
            }
            releasePublication.complete(Unit)

            assertFalse(staleRefresh.await())
            assertEquals(BridgeProtocol.RecordingState.PAUSED, BridgeState.status.value.recordingState)
            assertEquals("new-session-diagnostics", BridgeState.status.value.lastError)
            assertTrue(sender.calls.isEmpty())
        } finally {
            releasePublication.complete(Unit)
            runtime.close()
        }
    }

    @Test fun profileTransfersForTheActiveWatchAreSerialized() = runBlocking {
        val sender = RecordingSender(yieldDuringSend = true)
        val locus = FakeLocus(profiles = listOf("x".repeat(200)))
        val runtime = runtime(sender = sender, locus = locus)
        val watch = WatchIdentifier("watch")
        try {
            coroutineScope {
                launch { assertTrue(runtime.sendRecordingProfiles(watch)) }
                launch { assertTrue(runtime.sendRecordingProfiles(watch)) }
            }

            val transferIds = sender.calls.map {
                PebbleMessages.signed32(it.dictionary, BridgeProtocol.Key.TRANSFER_ID)
            }
            assertEquals(2, transferIds.distinct().size)
            assertEquals(2, transferIds.zipWithNext().count { (first, second) -> first != second } + 1)
            assertTrue(sender.calls.all { it.watches == listOf(watch) })
        } finally {
            runtime.close()
        }
    }

    @Test fun failedOrInvalidProfileQueriesNeverSendAnAuthoritativeEmptyTransfer() = runBlocking {
        val oversized = (0 until 40).map { index ->
            "profile-$index-${"x".repeat(240)}"
        }
        val failures = listOf(
            FakeLocus(profileFailure = "Locus is unavailable"),
            FakeLocus(throwProfileQuery = true),
            FakeLocus(profiles = listOf("broken\nname")),
            FakeLocus(profiles = listOf("Hiking", "Hiking")),
            FakeLocus(profiles = oversized),
        )

        failures.forEachIndexed { index, locus ->
            val sender = RecordingSender()
            val runtime = runtime(sender = sender, locus = locus)
            try {
                assertFalse(runtime.sendRecordingProfiles(WatchIdentifier("watch-$index")))
                assertTrue(sender.calls.isEmpty())
            } finally {
                runtime.close()
            }
        }
    }

    @Test fun successfulEmptyProfileQuerySendsTheAuthoritativeEmptyResult() = runBlocking {
        val sender = RecordingSender()
        val runtime = runtime(sender, FakeLocus(profiles = emptyList()))
        try {
            assertTrue(runtime.sendRecordingProfiles(WatchIdentifier("watch")))
            assertEquals(1, sender.calls.size)
            assertEquals(
                BridgeProtocol.Result.FAILED.wire,
                PebbleMessages.signed32(sender.calls.single().dictionary, BridgeProtocol.Key.RESULT),
            )
            assertEquals(
                "",
                PebbleMessages.string(sender.calls.single().dictionary, BridgeProtocol.Key.CHUNK_DATA),
            )
        } finally {
            runtime.close()
        }
    }

    @Test fun rejectedProfileQueryDoesNotConsumeATransferIdentifier() = runBlocking {
        val sender = RecordingSender()
        val locus = FakeLocus(profiles = listOf("Hiking", "Hiking"))
        val runtime = runtime(sender, locus)
        try {
            assertFalse(runtime.sendRecordingProfiles(WatchIdentifier("watch")))
            locus.profiles = listOf("Hiking")
            assertTrue(runtime.sendRecordingProfiles(WatchIdentifier("watch")))
            assertEquals(
                0,
                PebbleMessages.signed32(sender.calls.single().dictionary, BridgeProtocol.Key.TRANSFER_ID),
            )
        } finally {
            runtime.close()
        }
    }

    private fun runtime(
        sender: PebbleDictionarySender,
        locus: LocusBridgeGateway = FakeLocus(),
        maxAttempts: Int = 1,
        commandJournal: CommandJournal = CommandJournal(),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        trustedWorkLease: suspend (suspend () -> Unit) -> Unit = { block -> block() },
        trustedMutationGate: (suspend (
            TrustAdmission,
            suspend () -> Unit,
        ) -> TrustLeaseResult<Unit>)? = null,
        trustedPublicationGate: (suspend (
            TrustAdmission,
            suspend () -> Unit,
        ) -> TrustLeaseResult<Unit>)? = null,
        admissionCurrent: (TrustAdmission) -> Boolean = { true },
    ): BridgeRuntime = BridgeRuntime(
        scope = scope,
        locus = locus,
        transport = ReliablePebbleTransport(sender, maxAttempts = maxAttempts, retryDelay = {}),
        commandJournal = commandJournal,
        refreshMode = { RefreshMode.ADAPTIVE },
        ioDispatcher = ioDispatcher,
        monotonicMillis = { 1_000L },
        wallMillis = { 1_000_000L },
        delayMillis = { duration -> if (duration >= 2_000L) delay(Long.MAX_VALUE) },
        trustedMutationGate = trustedMutationGate ?: { _, block ->
                var executed = false
                trustedWorkLease {
                    block()
                    executed = true
                }
                if (executed) TrustLeaseResult.Admitted(Unit) else TrustLeaseResult.Untrusted
            },
        trustedPublicationGate = trustedPublicationGate,
        admissionCurrent = admissionCurrent,
    )

    private class FakeLocus(
        private var heartRateFailures: Int = 0,
        var profiles: List<String> = listOf("Hiking"),
        private val profileFailure: String? = null,
        private val throwProfileQuery: Boolean = false,
    ) : LocusBridgeGateway {
        var executions = 0
        var heartRateCalls = 0
        private var currentHeartRate: Int? = null
        private var state = BridgeProtocol.RecordingState.RECORDING

        override fun readSnapshot(nowMillis: Long): BridgeProtocol.Snapshot = BridgeProtocol.Snapshot(
            state = state,
            sampledAtEpochSeconds = nowMillis / 1000,
            currentHeartRate = currentHeartRate,
        )

        override fun sendHeartRate(bpm: Int): Boolean {
            heartRateCalls++
            if (heartRateFailures > 0) {
                heartRateFailures--
                throw IllegalStateException("synthetic Locus failure")
            }
            currentHeartRate = bpm
            return true
        }

        override fun recordingProfiles(): RecordingProfilesResult {
            if (throwProfileQuery) error("synthetic profile query failure")
            return profileFailure?.let(RecordingProfilesResult::Failure)
                ?: RecordingProfilesResult.Success(profiles)
        }

        override fun execute(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): BridgeProtocol.Result {
            executions++
            return when (command) {
                BridgeProtocol.Command.START -> if (state == BridgeProtocol.RecordingState.STOPPED) {
                    state = BridgeProtocol.RecordingState.RECORDING
                    BridgeProtocol.Result.OK
                } else {
                    BridgeProtocol.Result.INVALID_STATE
                }
                BridgeProtocol.Command.PAUSE_RESUME -> when (state) {
                    BridgeProtocol.RecordingState.RECORDING -> {
                        state = BridgeProtocol.RecordingState.PAUSED
                        BridgeProtocol.Result.OK
                    }
                    BridgeProtocol.RecordingState.PAUSED -> {
                        state = BridgeProtocol.RecordingState.RECORDING
                        BridgeProtocol.Result.OK
                    }
                    else -> BridgeProtocol.Result.INVALID_STATE
                }
                BridgeProtocol.Command.STOP_SAVE -> if (
                    state == BridgeProtocol.RecordingState.RECORDING ||
                    state == BridgeProtocol.RecordingState.PAUSED
                ) {
                    state = BridgeProtocol.RecordingState.STOPPED
                    BridgeProtocol.Result.OK
                } else {
                    BridgeProtocol.Result.INVALID_STATE
                }
                BridgeProtocol.Command.ADD_WAYPOINT,
                BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE,
                -> if (state == BridgeProtocol.RecordingState.RECORDING) {
                    BridgeProtocol.Result.OK
                } else {
                    BridgeProtocol.Result.INVALID_STATE
                }
            }
        }

        override fun executeWithExpectedState(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): CommandExecution {
            val result = execute(command, profileName, waypointName)
            val expected = if (
                result == BridgeProtocol.Result.OK &&
                command != BridgeProtocol.Command.ADD_WAYPOINT &&
                command != BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE
            ) state else null
            return CommandExecution(result, expected)
        }
    }

    private class StateChangingLocus : LocusBridgeGateway {
        var executions = 0
        private var state = BridgeProtocol.RecordingState.STOPPED

        override fun readSnapshot(nowMillis: Long) = BridgeProtocol.Snapshot(
            state = state,
            sampledAtEpochSeconds = nowMillis / 1_000,
        )

        override fun sendHeartRate(bpm: Int) = false
        override fun recordingProfiles() = RecordingProfilesResult.Success(listOf("Hiking"))

        override fun execute(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): BridgeProtocol.Result {
            executions++
            state = BridgeProtocol.RecordingState.RECORDING
            return BridgeProtocol.Result.OK
        }

        override fun executeWithExpectedState(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): CommandExecution = CommandExecution(
            execute(command, profileName, waypointName),
            BridgeProtocol.RecordingState.RECORDING,
        )
    }

    private class DelayedStateChangingLocus(
        private val transitionAfterPostExecuteReads: Int?,
    ) : LocusBridgeGateway {
        var executions = 0
        var postExecuteReads = 0
        private var executeIssued = false
        private var state = BridgeProtocol.RecordingState.STOPPED

        override fun readSnapshot(nowMillis: Long): BridgeProtocol.Snapshot {
            if (executeIssued && state == BridgeProtocol.RecordingState.STOPPED) {
                postExecuteReads++
                if (
                    transitionAfterPostExecuteReads != null &&
                    postExecuteReads >= transitionAfterPostExecuteReads
                ) {
                    state = BridgeProtocol.RecordingState.RECORDING
                }
            }
            return BridgeProtocol.Snapshot(
                state = state,
                sampledAtEpochSeconds = nowMillis / 1_000,
            )
        }

        override fun sendHeartRate(bpm: Int) = false
        override fun recordingProfiles() = RecordingProfilesResult.Success(listOf("Hiking"))

        override fun execute(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): BridgeProtocol.Result {
            executions++
            executeIssued = true
            return BridgeProtocol.Result.OK
        }

        override fun executeWithExpectedState(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): CommandExecution = CommandExecution(
            execute(command, profileName, waypointName),
            BridgeProtocol.RecordingState.RECORDING,
        )
    }

    /** Models the UI changing RECORDING to PAUSED before Locus routes the same command as Resume. */
    private class PauseResumeRoutingRaceLocus : LocusBridgeGateway {
        var postExecuteReads = 0
        private var routed = false

        override fun readSnapshot(nowMillis: Long): BridgeProtocol.Snapshot {
            val state = if (!routed) {
                BridgeProtocol.RecordingState.RECORDING
            } else {
                postExecuteReads++
                if (postExecuteReads == 1) {
                    BridgeProtocol.RecordingState.PAUSED
                } else {
                    BridgeProtocol.RecordingState.RECORDING
                }
            }
            return BridgeProtocol.Snapshot(state, nowMillis / 1_000)
        }

        override fun sendHeartRate(bpm: Int) = false
        override fun recordingProfiles() = RecordingProfilesResult.Success(emptyList())

        override fun execute(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): BridgeProtocol.Result = error("Runtime must use the enriched routing result")

        override fun executeWithExpectedState(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): CommandExecution {
            routed = true
            return CommandExecution(
                BridgeProtocol.Result.OK,
                BridgeProtocol.RecordingState.RECORDING,
            )
        }
    }

    private class BlockingHeartRateLocus : LocusBridgeGateway {
        val mutationStarted = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        var heartRateCalls = 0
        private var currentHeartRate: Int? = null

        override fun readSnapshot(nowMillis: Long) = BridgeProtocol.Snapshot(
            state = BridgeProtocol.RecordingState.RECORDING,
            sampledAtEpochSeconds = nowMillis / 1_000,
            currentHeartRate = currentHeartRate,
        )

        override fun sendHeartRate(bpm: Int): Boolean {
            heartRateCalls++
            mutationStarted.countDown()
            check(releaseMutation.await(5, TimeUnit.SECONDS)) { "test mutation was not released" }
            currentHeartRate = bpm
            return true
        }

        override fun recordingProfiles() = RecordingProfilesResult.Success(emptyList())

        override fun execute(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ) = BridgeProtocol.Result.FAILED
    }

    private class BlockingCommandConfirmationLocus : LocusBridgeGateway {
        val actionStarted = CompletableDeferred<Unit>()
        val releaseAction = CountDownLatch(1)
        val confirmationReadStarted = CompletableDeferred<Unit>()
        val releaseConfirmationRead = CountDownLatch(1)
        var executions = 0

        override fun readSnapshot(nowMillis: Long): BridgeProtocol.Snapshot {
            confirmationReadStarted.complete(Unit)
            check(releaseConfirmationRead.await(5, TimeUnit.SECONDS)) {
                "test confirmation read was not released"
            }
            return BridgeProtocol.Snapshot(
                BridgeProtocol.RecordingState.RECORDING,
                nowMillis / 1_000,
            )
        }

        override fun sendHeartRate(bpm: Int) = false
        override fun recordingProfiles() = RecordingProfilesResult.Success(emptyList())

        override fun execute(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): BridgeProtocol.Result = error("Runtime must use the enriched routing result")

        override fun executeWithExpectedState(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): CommandExecution {
            executions++
            actionStarted.complete(Unit)
            check(releaseAction.await(5, TimeUnit.SECONDS)) { "test action was not released" }
            return CommandExecution(
                BridgeProtocol.Result.OK,
                BridgeProtocol.RecordingState.RECORDING,
            )
        }
    }

    private class FirstReadBlockingLocus : LocusBridgeGateway {
        val firstReadStarted = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        private val reads = AtomicLong()

        override fun readSnapshot(nowMillis: Long): BridgeProtocol.Snapshot {
            if (reads.incrementAndGet() == 1L) {
                firstReadStarted.countDown()
                check(releaseFirstRead.await(5, TimeUnit.SECONDS)) { "test read was not released" }
            }
            return BridgeProtocol.Snapshot(
                BridgeProtocol.RecordingState.STOPPED,
                nowMillis / 1_000,
            )
        }

        override fun sendHeartRate(bpm: Int) = false
        override fun recordingProfiles() = RecordingProfilesResult.Success(emptyList())
        override fun execute(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ) = BridgeProtocol.Result.FAILED
    }

    private class RecordingSender(
        private val yieldDuringSend: Boolean = false,
    ) : PebbleDictionarySender {
        data class Call(val dictionary: PebbleDictionary, val watches: List<WatchIdentifier>)
        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun send(
            dictionary: PebbleDictionary,
            watch: WatchIdentifier,
            admission: TrustAdmission,
        ): TransmissionResult {
            calls += Call(dictionary, listOf(watch))
            if (yieldDuringSend) yield()
            return TransmissionResult.Success
        }

        override fun close() = Unit
    }

    private class AdmissionRecordingSender(
        private val currentAdmission: () -> TrustAdmission,
        private val leases: SerializedCoreSessionLeases? = null,
    ) : PebbleDictionarySender {
        data class Call(
            val dictionary: PebbleDictionary,
            val watches: List<WatchIdentifier>,
            val admission: TrustAdmission,
        )

        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun send(
            dictionary: PebbleDictionary,
            watch: WatchIdentifier,
            admission: TrustAdmission,
        ): TransmissionResult? {
            val deliver = {
                if (admission != currentAdmission()) {
                    null
                } else {
                    calls += Call(dictionary, listOf(watch), admission)
                    TransmissionResult.Success
                }
            }
            return leases?.withOutbound(deliver) ?: deliver()
        }

        override fun close() = Unit
    }

    private class ReceiverOrderingSender : PebbleDictionarySender {
        val firstSnapshotAttemptStarted = CompletableDeferred<Unit>()
        val releaseFirstSnapshotAttempt = CompletableDeferred<Unit>()
        val attemptTypes = mutableListOf<Int>()
        val snapshotEpochs = mutableListOf<Long>()
        var epochAcceptedBeforeResult: Long? = null
            private set
        private var snapshotAttempts = 0
        private var acceptedEpoch: Long? = null
        private var delayedPreCommandEpoch: Long? = null

        override suspend fun send(
            dictionary: PebbleDictionary,
            watch: WatchIdentifier,
            admission: TrustAdmission,
        ): TransmissionResult? {
            val type = requireNotNull(PebbleMessages.signed32(dictionary, BridgeProtocol.Key.MESSAGE_TYPE))
            attemptTypes += type
            if (type == BridgeProtocol.MessageType.SNAPSHOT.wire) {
                val epoch = requireNotNull(
                    PebbleMessages.unsigned32(dictionary, BridgeProtocol.Key.SAMPLE_EPOCH_SECONDS),
                )
                snapshotEpochs += epoch
                snapshotAttempts++
                if (snapshotAttempts == 1) {
                    delayedPreCommandEpoch = epoch
                    firstSnapshotAttemptStarted.complete(Unit)
                    releaseFirstSnapshotAttempt.await()
                    return null
                }
                if (snapshotAttempts == 2) return null
                acceptedEpoch = epoch
            } else if (type == BridgeProtocol.MessageType.COMMAND_RESULT.wire) {
                epochAcceptedBeforeResult = acceptedEpoch
            }
            return TransmissionResult.Success
        }

        fun deliverDelayedPreCommandSnapshot(): Boolean {
            val delayed = requireNotNull(delayedPreCommandEpoch)
            val floor = requireNotNull(acceptedEpoch)
            return (delayed >= floor).also { accepted ->
                if (accepted) acceptedEpoch = delayed
            }
        }

        override fun close() = Unit
    }

    private class SnapshotFailingSender : PebbleDictionarySender {
        val attemptTypes = mutableListOf<Int>()

        override suspend fun send(
            dictionary: PebbleDictionary,
            watch: WatchIdentifier,
            admission: TrustAdmission,
        ): TransmissionResult? {
            val type = requireNotNull(PebbleMessages.signed32(dictionary, BridgeProtocol.Key.MESSAGE_TYPE))
            attemptTypes += type
            return if (type == BridgeProtocol.MessageType.SNAPSHOT.wire) {
                null
            } else {
                TransmissionResult.Success
            }
        }

        override fun close() = Unit
    }
}

private val TEST_ADMISSION = TrustAdmission(0)

private fun BridgeRuntime.watchAppOpened(watch: WatchIdentifier) =
    watchAppOpened(watch, TEST_ADMISSION)

private fun BridgeRuntime.watchObserved(watch: WatchIdentifier) =
    watchObserved(watch, TEST_ADMISSION)

private fun BridgeRuntime.watchAppClosed(watch: WatchIdentifier) =
    watchAppClosed(watch, TEST_ADMISSION)

private fun BridgeRuntime.handleHeartRate(
    watch: WatchIdentifier,
    sessionId: Long,
    sequence: Long,
    bpm: Int,
    sampledAtEpochSeconds: Long,
): Boolean = handleHeartRate(
    watch,
    sessionId,
    sequence,
    bpm,
    sampledAtEpochSeconds,
    TEST_ADMISSION,
)

private suspend fun BridgeRuntime.handleCommand(
    watch: WatchIdentifier,
    sessionId: Long,
    commandId: Long,
    command: BridgeProtocol.Command,
    profileName: String?,
    waypointName: String?,
): Boolean = handleCommand(
    watch,
    sessionId,
    commandId,
    command,
    profileName,
    waypointName,
    TEST_ADMISSION,
)

private suspend fun BridgeRuntime.sendRecordingProfiles(watch: WatchIdentifier): Boolean =
    sendRecordingProfiles(watch, TEST_ADMISSION)

private suspend fun BridgeRuntime.refresh(watches: Collection<WatchIdentifier>): Boolean =
    refresh(watches.single(), TEST_ADMISSION)
