package io.github.christianherget.trackglance.bridge.core

import android.content.Context
import android.os.SystemClock
import androidx.core.content.edit
import io.github.christianherget.trackglance.bridge.locus.CommandExecution
import io.github.christianherget.trackglance.bridge.locus.LocusBridgeGateway
import io.github.christianherget.trackglance.bridge.locus.LocusGateway
import io.github.christianherget.trackglance.bridge.locus.RecordingProfilesResult
import io.github.christianherget.trackglance.bridge.pebble.ActiveWatchSlot
import io.github.christianherget.trackglance.bridge.pebble.DefaultPebbleDictionarySender
import io.github.christianherget.trackglance.bridge.pebble.PebbleMessages
import io.github.christianherget.trackglance.bridge.pebble.ReliablePebbleTransport
import io.github.christianherget.trackglance.bridge.pebble.TrustAdmission
import io.github.christianherget.trackglance.bridge.pebble.TrustLeaseResult
import io.github.christianherget.trackglance.bridge.pebble.TrustedPebbleCompanionProvider
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BridgeRuntime
internal constructor(
    private val scope: CoroutineScope,
    private val locus: LocusBridgeGateway,
    private val transport: ReliablePebbleTransport,
    private val commandJournal: CommandJournal,
    private val refreshMode: () -> RefreshMode,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val trustedMutationGate:
        suspend (
            TrustAdmission,
            suspend () -> Unit,
        ) -> TrustLeaseResult<Unit> =
        { _, block ->
            TrustLeaseResult.Admitted(block())
        },
    private val trustedPublicationGate:
        (suspend (
            TrustAdmission,
            suspend () -> Unit,
        ) -> TrustLeaseResult<Unit>)? =
        null,
    private val admissionCurrent: (TrustAdmission) -> Boolean = { true },
) : AutoCloseable {
    private val activeWatches = ActiveWatchSlot<AdmittedWatch>()
    private val lifecycleLock = Any()
    private val childJobs = mutableSetOf<Job>()
    private val heartRateGate = HeartRateSampleGate()
    private val heartRateSamples = Channel<HeartRateSample>(Channel.CONFLATED)
    private val operations =
        BridgeOperationCoordinator<AdmittedWatch, BridgeProtocol.Snapshot>(
            read = ::readSnapshotForDelivery,
            updateStatus = { snapshot, targets ->
                targets.singleAdmissionOrNull()?.let { admission ->
                    publishIfCurrent(admission) { updateStatus(snapshot) }
                }
            },
            send = ::sendSnapshotAndContext,
        )
    private var updateJob: Job? = null
    private var heartRateJob: Job? = null

    @Volatile private var transitioningUntil = 0L
    @Volatile private var recordingCatalog: List<BridgeProtocol.RecordingProfile>? = null
    @Volatile private var unresolvedProfileRefreshName: String? = null

    init {

        synchronized(lifecycleLock) { ensureHeartRateConsumerLocked() }
    }

    fun handleHeartRate(
        watch: WatchIdentifier,
        sessionId: Long,
        sequence: Long,
        bpm: Int,
        sampledAtEpochSeconds: Long,
        admission: TrustAdmission,
    ): Boolean {
        if (!admissionCurrent(admission)) return false
        val accepted =
            heartRateGate.accept(
                watchId = watch.value,
                session = sessionId,
                sequence = sequence,
                bpm = bpm,
                sampledAt = sampledAtEpochSeconds,
                now = wallMillis() / 1000,
                trustGeneration = admission.generation,
            )
        if (!accepted) return false
        val sample = HeartRateSample(watch, bpm, admission)
        if (heartRateSamples.trySend(sample).isFailure) return false
        return true
    }

    private suspend fun forwardHeartRate(sample: HeartRateSample) {
        val source = AdmittedWatch(sample.watch, sample.admission)
        if (activeWatches.snapshot().singleOrNull()?.let { it != source } == true) return
        if (
            !publishIfCurrent(sample.admission) {
                BridgeState.update { it.copy(lastWatchHeartRate = sample.bpm) }
            }
        )
            return
        val initial = readSnapshot(sample.admission)
        if (initial.state != BridgeProtocol.RecordingState.RECORDING) return
        if (activeWatches.snapshot().singleOrNull()?.let { it != source } == true) return
        var forwarded = false
        val admitted =
            trustedMutationGate(sample.admission) {
                forwarded = withContext(ioDispatcher) { locus.sendHeartRate(sample.bpm) }
            }
        if (admitted !is TrustLeaseResult.Admitted || !forwarded) return
        if (!admissionCurrent(sample.admission)) return

        if (
            !publishIfCurrent(sample.admission) {
                BridgeState.update { it.copy(lastHeartRateForwardedEpochMillis = wallMillis()) }
            }
        )
            return
        val deadline = monotonicMillis() + HEART_RATE_CONFIRMATION_MILLIS
        var snapshot = initial
        do {
            delayMillis(HEART_RATE_POLL_MILLIS)
            snapshot = readSnapshot(sample.admission)
        } while (snapshot.currentHeartRate != sample.bpm && monotonicMillis() < deadline)

        deliverSnapshot(
            targets = listOf(AdmittedWatch(sample.watch, sample.admission)),
            failureKind = BridgeFailureKind.SNAPSHOT_DELIVERY_FAILED,
        )
    }

    fun watchAppOpened(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): Boolean = observeWatch(AdmittedWatch(watch, admission), markTransition = true)

    /** Recovers active-watch state when the process restarted while the watchapp stayed open. */
    fun watchObserved(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): Boolean = observeWatch(AdmittedWatch(watch, admission), markTransition = false)

    private fun observeWatch(target: AdmittedWatch, markTransition: Boolean): Boolean {
        synchronized(lifecycleLock) {
            ensureHeartRateConsumerLocked()
            val newlyOpened =
                if (markTransition) {
                    activeWatches.opened(target)
                } else {
                    if (!activeWatches.observed(target)) return false
                    false
                }
            BridgeState.update {
                it.copy(watchAppOpen = true, lastError = if (markTransition) null else it.lastError)
            }
            if (newlyOpened || markTransition) {
                transitioningUntil = monotonicMillis() + TRANSITION_REFRESH_MILLIS
            }
            if (updateJob?.isActive == true) {
                if (newlyOpened) launchTracked { refreshTargets(listOf(target)) }
                return true
            }
            updateJob = launchTracked {
                while (isActive) {
                    var admission: TrustAdmission? = null
                    try {
                        val targets = activeWatches.snapshot()
                        if (targets.isEmpty()) return@launchTracked
                        admission = targets.singleAdmissionOrNull()
                        refreshTargets(targets)
                        val transitioning = monotonicMillis() < transitioningUntil
                        delayMillis(RefreshPolicy(refreshMode()).nextDelayMillis(transitioning))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        admission?.let { current ->
                            publishIfCurrent(current) { reportError(error) }
                        }
                        delayMillis(POLL_FAILURE_DELAY_MILLIS)
                    }
                }
            }
            return true
        }
    }

    fun watchAppClosed(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ) {
        synchronized(lifecycleLock) {
            activeWatches.closed(AdmittedWatch(watch, admission))
            if (activeWatches.isEmpty()) {
                updateJob?.cancel()
                updateJob = null
                BridgeState.update { it.copy(watchAppOpen = false) }
            }
        }
    }

    internal fun companionTrustLost() {
        while (heartRateSamples.tryReceive().isSuccess) {
            // Drain samples admitted under the previous companion identity.
        }
        synchronized(lifecycleLock) {
            activeWatches.clear()
            childJobs.toList().forEach { it.cancel() }
            childJobs.clear()
            updateJob = null
            heartRateJob = null
            BridgeState.update { it.withPebbleSelection(null) }
        }
    }

    suspend fun handleCommand(
        watch: WatchIdentifier,
        sessionId: Long,
        commandId: Long,
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
        admission: TrustAdmission,
    ): Boolean {
        val key = CommandJournal.Key(watch.value, sessionId, commandId)
        val fingerprint =
            CommandJournal.fingerprint(
                command,
                profileName.takeIf { command == BridgeProtocol.Command.START },
                waypointName.takeIf { command == BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE },
            )
        val target = AdmittedWatch(watch, admission)
        val delivered =
            operations.mutateAndDeliver(
                targets = listOf(target),
                observedEpochSeconds = wallMillis() / 1_000L,
                mutate = {
                    val begun = withContext(ioDispatcher) { commandJournal.begin(key, fingerprint) }

                    val mutation =
                        when (begun) {
                            is CommandJournal.BeginResult.Completed -> CommandMutation(begun.result)
                            is CommandJournal.BeginResult.Execute -> {
                                transitioningUntil = monotonicMillis() + TRANSITION_REFRESH_MILLIS
                                val executed =
                                    executeUnderAdmission(
                                        admission = admission,
                                        command = command,
                                        profileName = profileName,
                                        waypointName = waypointName,
                                    )
                                val confirmed =
                                    executed?.let { confirmExecution(admission, command, it) }
                                        ?: CommandMutation(BridgeProtocol.Result.FAILED)
                                val completed =
                                    withContext(ioDispatcher) {
                                        commandJournal.complete(begun.key, confirmed.result)
                                    }

                                if (completed) {
                                    confirmed
                                } else {
                                    confirmed.copy(result = BridgeProtocol.Result.FAILED)
                                }
                            }
                            CommandJournal.BeginResult.Collision,
                            CommandJournal.BeginResult.Pending ->
                                CommandMutation(BridgeProtocol.Result.FAILED)
                        }
                    publishIfCurrent(admission) {
                        BridgeState.update {
                            it.copy(
                                lastCommand = command,
                                lastCommandResult = mutation.result,
                                lastWaypointName =
                                    waypointName.takeIf {
                                        command == BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE
                                    },
                            )
                        }
                    }
                    mutation
                },
                readAfterMutation = { epoch, mutation ->
                    (mutation.latestSnapshot ?: readSnapshot(admission)).copy(
                        sampledAtEpochSeconds = epoch
                    )
                },
                finish = { mutation, snapshotDelivered ->
                    snapshotDelivered &&
                        isActiveOrUntracked(target) &&
                        transport.send(
                            PebbleMessages.result(sessionId, commandId, mutation.result),
                            watch,
                            admission,
                        )
                },
            )
        if (!delivered)
            publishIfCurrent(admission) {
                reportError(BridgeFailure(BridgeFailureKind.COMMAND_RESULT_DELIVERY_FAILED))
            }
        return delivered
    }

    /** The lease covers only Locus' exact state/profile lookup plus routed action transaction. */
    private suspend fun executeUnderAdmission(
        admission: TrustAdmission,
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
    ): CommandExecution? {
        var executed: CommandExecution? = null
        return try {
            when (
                trustedMutationGate(admission) {
                    executed =
                        withContext(ioDispatcher) {
                            locus.executeWithExpectedState(command, profileName, waypointName)
                        }
                }
            ) {
                is TrustLeaseResult.Admitted -> executed
                TrustLeaseResult.Stale,
                TrustLeaseResult.Untrusted -> null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    /** Confirmation polls run outside the revocation lease and retain the ingress admission. */
    private suspend fun confirmExecution(
        admission: TrustAdmission,
        command: BridgeProtocol.Command,
        executed: CommandExecution,
    ): CommandMutation {
        if (executed.result != BridgeProtocol.Result.OK) return CommandMutation(executed.result)
        val expectedState =
            executed.expectedState
                ?: when (command) {
                    BridgeProtocol.Command.ADD_WAYPOINT,
                    BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE ->
                        return CommandMutation(executed.result)
                    else -> return CommandMutation(BridgeProtocol.Result.FAILED)
                }
        val deadline = monotonicMillis() + BridgeProtocol.COMMAND_CONFIRMATION_MILLIS
        var pollsRemaining = COMMAND_CONFIRMATION_MAX_POLLS
        var latest = readSnapshot(admission)
        while (
            latest.state != expectedState && pollsRemaining-- > 0 && monotonicMillis() < deadline
        ) {
            delayMillis(COMMAND_CONFIRMATION_POLL_MILLIS)
            latest = readSnapshot(admission)
        }
        return CommandMutation(
            result =
                if (latest.state == expectedState) {
                    BridgeProtocol.Result.OK
                } else {
                    BridgeProtocol.Result.FAILED
                },
            latestSnapshot = latest,
        )
    }

    suspend fun sendRecordingProfiles(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): Boolean = operations.serialized {
        val query =
            try {
                withContext(ioDispatcher) { locus.recordingProfiles() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                RecordingProfilesResult.Failure(BridgeFailure.technical(error))
            }
        val profiles =
            when (query) {
                is RecordingProfilesResult.Success -> query.profiles
                is RecordingProfilesResult.Failure -> {
                    publishIfCurrent(admission) {
                        BridgeState.update {
                            it.copy(
                                lastProfileRequestEpochMillis = wallMillis(),
                                lastError = query.failure,
                            )
                        }
                    }
                    return@serialized false
                }
            }
        val transfer = BridgeProtocol.profileTransfer(profiles)
        if (transfer == null) {
            publishIfCurrent(admission) {
                BridgeState.update {
                    it.copy(
                        lastProfileRequestEpochMillis = wallMillis(),
                        lastError = BridgeFailure(BridgeFailureKind.LOCUS_PROFILE_LIST_INVALID),
                    )
                }
            }
            return@serialized false
        }
        if (
            !publishIfCurrent(admission) {
                BridgeState.update {
                    it.copy(
                        locusProfiles = profiles.map { it.name },
                        lastProfileRequestEpochMillis = wallMillis(),
                        lastError =
                            when {
                                profiles.isEmpty() ->
                                    BridgeFailure(BridgeFailureKind.LOCUS_RETURNED_NO_PROFILES)
                                else -> null
                            },
                    )
                }
            }
        )
            return@serialized false
        if (profiles.isNotEmpty()) {
            recordingCatalog = profiles
            unresolvedProfileRefreshName = null
        }
        val transferId = reserveProfileTransferId()
        val messages = PebbleMessages.profileListChunks(transfer, transferId)
        messages.forEach { message ->
            if (
                !isActiveOrUntracked(AdmittedWatch(watch, admission)) ||
                    !transport.send(message, watch, admission)
            ) {
                publishIfCurrent(admission) {
                    reportError(BridgeFailure(BridgeFailureKind.PROFILE_LIST_DELIVERY_FAILED))
                }
                return@serialized false
            }
        }
        true
    }

    private suspend fun sendSnapshotAndContext(
        snapshot: BridgeProtocol.Snapshot,
        targets: Collection<AdmittedWatch>,
    ): Boolean {
        if (!sendToAdmittedTargets(PebbleMessages.snapshot(snapshot), targets)) return false
        if (
            snapshot.state != BridgeProtocol.RecordingState.RECORDING &&
                snapshot.state != BridgeProtocol.RecordingState.PAUSED
        )
            return true
        var profile = resolveActiveProfile(snapshot)
        if (
            profile == null &&
                snapshot.locusProfileName != unresolvedProfileRefreshName &&
                refreshCatalogForContext(snapshot.locusProfileName, targets)
        ) {
            profile = resolveActiveProfile(snapshot)
        }
        if (profile == null) return true
        return sendToAdmittedTargets(
            PebbleMessages.recordingContext(snapshot.state, profile),
            targets,
        )
    }

    private fun resolveActiveProfile(
        snapshot: BridgeProtocol.Snapshot
    ): BridgeProtocol.RecordingProfile? {
        val name = snapshot.locusProfileName ?: return null
        return recordingCatalog?.filter { it.name == name }?.singleOrNull()
    }

    private suspend fun refreshCatalogForContext(
        profileName: String?,
        targets: Collection<AdmittedWatch>,
    ): Boolean {
        unresolvedProfileRefreshName = profileName
        val query =
            try {
                withContext(ioDispatcher) { locus.recordingProfiles() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return false
            }
        val fresh =
            (query as? RecordingProfilesResult.Success)?.profiles?.takeIf { it.isNotEmpty() }
                ?: return false
        val transfer = BridgeProtocol.profileTransfer(fresh) ?: return false
        recordingCatalog = fresh
        val messages =
            PebbleMessages.profileListChunks(
                transfer,
                operations.reserveProfileTransferId(),
            )
        for (message in messages) {
            if (!sendToAdmittedTargets(message, targets)) return false
        }
        if (fresh.any { it.name == profileName }) unresolvedProfileRefreshName = null
        return true
    }

    suspend fun refresh(
        watch: WatchIdentifier,
        admission: TrustAdmission,
    ): Boolean {
        return refreshTargets(listOf(AdmittedWatch(watch, admission)))
    }

    private suspend fun refreshTargets(targets: Collection<AdmittedWatch>): Boolean =
        deliverSnapshot(targets, BridgeFailureKind.SNAPSHOT_DELIVERY_FAILED)

    private suspend fun deliverSnapshot(
        targets: Collection<AdmittedWatch>,
        failureKind: BridgeFailureKind,
    ): Boolean {
        val delivered = operations.deliver(targets)
        val admission = targets.singleAdmissionOrNull()
        if (!delivered && admission != null) {
            publishIfCurrent(admission) {
                reportError(BridgeFailure(failureKind))
            }
        }
        return delivered
    }

    private suspend fun readSnapshot(admission: TrustAdmission? = null): BridgeProtocol.Snapshot =
        try {
            val now = wallMillis()
            withContext(ioDispatcher) { locus.readSnapshot(now) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (admission == null) {
                reportError(error)
            } else {
                publishIfCurrent(admission) { reportError(error) }
            }
            BridgeProtocol.Snapshot(
                state = BridgeProtocol.RecordingState.UNAVAILABLE,
                sampledAtEpochSeconds = wallMillis() / 1000,
            )
        }

    private suspend fun readSnapshotForDelivery(
        targets: Collection<AdmittedWatch>
    ): BridgeProtocol.Snapshot? {
        val admission = targets.singleAdmissionOrNull() ?: return null
        val snapshot = readSnapshot(admission)
        val next = reserveSnapshotEpoch(snapshot.sampledAtEpochSeconds, admission)
        return snapshot.copy(sampledAtEpochSeconds = next)
    }

    private suspend fun reserveSnapshotEpoch(
        observedEpochSeconds: Long,
        admission: TrustAdmission? = null,
    ): Long {
        return operations.reserveSnapshotEpoch(observedEpochSeconds)
    }

    private suspend fun sendToAdmittedTargets(
        dictionary: io.rebble.pebblekit2.common.model.PebbleDictionary,
        targets: Collection<AdmittedWatch>,
    ): Boolean {
        val admission = targets.singleAdmissionOrNull() ?: return false
        val target = targets.singleOrNull() ?: return false
        if (!isActiveOrUntracked(target)) return false
        val watch = target.watch
        return transport.send(dictionary, watch, admission)
    }

    private fun isActiveOrUntracked(target: AdmittedWatch): Boolean =
        activeWatches.snapshot().singleOrNull()?.let { it == target } != false

    private fun updateStatus(snapshot: BridgeProtocol.Snapshot) = BridgeState.update {
        it.copy(
            locusAvailable = snapshot.state != BridgeProtocol.RecordingState.UNAVAILABLE,
            recordingState = snapshot.state,
            activeLocusProfile = snapshot.locusProfileName,
            lastUpdateEpochMillis = wallMillis(),
            currentLocusHeartRate = snapshot.currentHeartRate,
            lastError =
                if (snapshot.state == BridgeProtocol.RecordingState.UNAVAILABLE) {
                    BridgeFailure(BridgeFailureKind.LOCUS_UNAVAILABLE)
                } else {
                    null
                },
        )
    }

    private suspend fun publishIfCurrent(
        admission: TrustAdmission,
        block: suspend () -> Unit,
    ): Boolean {
        val gate = trustedPublicationGate
        if (gate == null) {
            if (!admissionCurrent(admission)) return false
            block()
            return true
        }
        return gate(admission, block) is TrustLeaseResult.Admitted
    }

    private fun reportError(error: Throwable) = reportError(BridgeFailure.technical(error))

    private fun reportError(failure: BridgeFailure) = BridgeState.update {
        it.copy(lastError = failure)
    }

    private fun ensureHeartRateConsumerLocked() {
        if (heartRateJob?.isActive == true) return
        heartRateJob = launchTracked {
            for (sample in heartRateSamples) {
                try {
                    forwardHeartRate(sample)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    publishIfCurrent(sample.admission) { reportError(error) }
                }
            }
        }
    }

    private fun launchTracked(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        synchronized(lifecycleLock) { childJobs += job }
        job.invokeOnCompletion { synchronized(lifecycleLock) { childJobs -= job } }
        job.start()
        return job
    }

    override fun close() {
        synchronized(lifecycleLock) {
            childJobs.toList().forEach { it.cancel() }
            childJobs.clear()
            updateJob = null
            heartRateJob = null
        }
        heartRateSamples.close()
        scope.cancel()
        transport.close()
    }

    companion object {
        private const val TRANSITION_REFRESH_MILLIS = 15_000L
        private const val HEART_RATE_CONFIRMATION_MILLIS = 1_500L
        private const val HEART_RATE_POLL_MILLIS = 100L
        private const val COMMAND_CONFIRMATION_POLL_MILLIS = 100L
        private const val COMMAND_CONFIRMATION_MAX_POLLS = 15
        private const val POLL_FAILURE_DELAY_MILLIS = 1_000L

        @Volatile private var instance: BridgeRuntime? = null

        fun get(context: Context): BridgeRuntime =
            instance
                ?: synchronized(this) {
                    instance ?: create(context.applicationContext).also { instance = it }
                }

        internal fun resetForCompanionTrustLoss() {
            val current = instance
            if (current == null) {
                BridgeState.update { it.withPebbleSelection(null) }
            } else {
                current.companionTrustLost()
            }
        }

        private fun create(context: Context): BridgeRuntime =
            BridgeRuntime(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                locus = LocusGateway(context),
                transport = ReliablePebbleTransport(DefaultPebbleDictionarySender(context)),
                commandJournal = CommandJournal(),
                refreshMode = { Preferences.refreshMode(context) },
                trustedMutationGate = { admission, block ->
                    TrustedPebbleCompanionProvider.withInboundAdmission(context, admission, block)
                },
                trustedPublicationGate = { admission, block ->
                    TrustedPebbleCompanionProvider.withOutboundAdmission(context, admission, block)
                },
                admissionCurrent = TrustedPebbleCompanionProvider::isAdmissionCurrent,
            )
    }
}

private data class HeartRateSample(
    val watch: WatchIdentifier,
    val bpm: Int,
    val admission: TrustAdmission,
)

private data class AdmittedWatch(
    val watch: WatchIdentifier,
    val admission: TrustAdmission,
)

private fun Collection<AdmittedWatch>.singleAdmissionOrNull(): TrustAdmission? =
    map(AdmittedWatch::admission).distinct().singleOrNull()

private data class CommandMutation(
    val result: BridgeProtocol.Result,
    val latestSnapshot: BridgeProtocol.Snapshot? = null,
)

object Preferences {
    private const val FILE = "bridge_preferences"
    private const val KEY_REFRESH_MODE = "refresh_mode"

    fun refreshMode(context: Context): RefreshMode {
        return runCatching {
                val name =
                    context
                        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                        .getString(KEY_REFRESH_MODE, RefreshMode.ADAPTIVE.name)
                RefreshMode.valueOf(name ?: RefreshMode.ADAPTIVE.name)
            }
            .getOrDefault(RefreshMode.ADAPTIVE)
    }

    fun setRefreshMode(context: Context, mode: RefreshMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_REFRESH_MODE, mode.name)
        }
    }
}
