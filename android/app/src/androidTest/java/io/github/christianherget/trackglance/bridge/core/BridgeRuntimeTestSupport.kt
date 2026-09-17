package io.github.christianherget.trackglance.bridge.core

import io.github.christianherget.trackglance.bridge.locus.CommandExecution
import io.github.christianherget.trackglance.bridge.locus.LocusBridgeGateway
import io.github.christianherget.trackglance.bridge.locus.RecordingProfilesResult
import io.github.christianherget.trackglance.bridge.pebble.PebbleDictionarySender
import io.github.christianherget.trackglance.bridge.pebble.PebbleMessages
import io.github.christianherget.trackglance.bridge.pebble.ReliablePebbleTransport
import io.github.christianherget.trackglance.bridge.pebble.TrustAdmission
import io.github.christianherget.trackglance.bridge.pebble.TrustLeaseResult
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

internal fun runtime(
    sender: PebbleDictionarySender,
    locus: LocusBridgeGateway = FakeLocus(),
    maxAttempts: Int = 1,
    commandJournal: CommandJournal = CommandJournal(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    trustedWorkLease: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    trustedMutationGate:
        (suspend (
            TrustAdmission,
            suspend () -> Unit,
        ) -> TrustLeaseResult<Unit>)? =
        null,
    trustedPublicationGate:
        (suspend (
            TrustAdmission,
            suspend () -> Unit,
        ) -> TrustLeaseResult<Unit>)? =
        null,
    admissionCurrent: (TrustAdmission) -> Boolean = { true },
    stepAccumulator: StepAccumulator = StepAccumulator(),
    watchSessionAuthority: WatchSessionAuthority = WatchSessionAuthority(),
    supervision: io.github.christianherget.trackglance.bridge.SupervisionEvents? = null,
): BridgeRuntime =
    BridgeRuntime(
        scope = scope,
        locus = locus,
        transport = ReliablePebbleTransport(sender, maxAttempts = maxAttempts, retryDelay = {}),
        commandJournal = commandJournal,
        refreshMode = { RefreshMode.ADAPTIVE },
        ioDispatcher = ioDispatcher,
        monotonicMillis = { 1_000L },
        wallMillis = { 1_000_000L },
        delayMillis = { duration -> if (duration >= 2_000L) delay(Long.MAX_VALUE) },
        trustedMutationGate =
            trustedMutationGate
                ?: { _, block ->
                    var executed = false
                    trustedWorkLease {
                        block()
                        executed = true
                    }
                    if (executed) TrustLeaseResult.Admitted(Unit) else TrustLeaseResult.Untrusted
                },
        trustedPublicationGate = trustedPublicationGate,
        admissionCurrent = admissionCurrent,
        stepAccumulator = stepAccumulator,
        watchSessionAuthority = watchSessionAuthority,
        supervision = supervision,
    )

internal class FakeLocus(
    private var heartRateFailures: Int = 0,
    var profiles: List<BridgeProtocol.RecordingProfile> =
        listOf(BridgeProtocol.RecordingProfile(1, "Hiking")),
    var activeProfileName: String? = null,
    private val profileFailure: String? = null,
    private val throwProfileQuery: Boolean = false,
) : LocusBridgeGateway {
    var executions = 0
    var heartRateCalls = 0
    var profileQueries = 0
    private var currentHeartRate: Int? = null
    var state = BridgeProtocol.RecordingState.RECORDING

    override fun readSnapshot(nowMillis: Long): BridgeProtocol.Snapshot =
        BridgeProtocol.Snapshot(
            state = state,
            sampledAtEpochSeconds = nowMillis / 1000,
            currentHeartRate = currentHeartRate,
            locusProfileName = activeProfileName,
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
        profileQueries++
        if (throwProfileQuery) error("synthetic profile query failure")
        return profileFailure?.let {
            RecordingProfilesResult.Failure(BridgeFailure.technical(it))
        } ?: RecordingProfilesResult.Success(profiles)
    }

    override fun execute(
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
    ): BridgeProtocol.Result {
        executions++
        return when (command) {
            BridgeProtocol.Command.START ->
                if (state == BridgeProtocol.RecordingState.STOPPED) {
                    state = BridgeProtocol.RecordingState.RECORDING
                    BridgeProtocol.Result.OK
                } else {
                    BridgeProtocol.Result.INVALID_STATE
                }
            BridgeProtocol.Command.PAUSE_RESUME ->
                when (state) {
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
            BridgeProtocol.Command.STOP_SAVE ->
                if (
                    state == BridgeProtocol.RecordingState.RECORDING ||
                        state == BridgeProtocol.RecordingState.PAUSED
                ) {
                    state = BridgeProtocol.RecordingState.STOPPED
                    BridgeProtocol.Result.OK
                } else {
                    BridgeProtocol.Result.INVALID_STATE
                }
            BridgeProtocol.Command.ADD_WAYPOINT,
            BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE ->
                if (state == BridgeProtocol.RecordingState.RECORDING) {
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
        val expected =
            if (
                result == BridgeProtocol.Result.OK &&
                    command != BridgeProtocol.Command.ADD_WAYPOINT &&
                    command != BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE
            )
                state
            else null
        return CommandExecution(result, expected)
    }
}

internal class StateChangingLocus : LocusBridgeGateway {
    var executions = 0
    private var state = BridgeProtocol.RecordingState.STOPPED

    override fun readSnapshot(nowMillis: Long) =
        BridgeProtocol.Snapshot(
            state = state,
            sampledAtEpochSeconds = nowMillis / 1_000,
        )

    override fun sendHeartRate(bpm: Int) = false

    override fun recordingProfiles() =
        RecordingProfilesResult.Success(listOf(BridgeProtocol.RecordingProfile(1, "Hiking")))

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
    ): CommandExecution =
        CommandExecution(
            execute(command, profileName, waypointName),
            BridgeProtocol.RecordingState.RECORDING,
        )
}

internal class RecordingSender(private val yieldDuringSend: Boolean = false) :
    PebbleDictionarySender {
    data class Call(val dictionary: PebbleDictionary, val watches: List<WatchIdentifier>)

    val calls = CopyOnWriteArrayList<Call>()

    fun types(): List<Int> = calls.map {
        requireNotNull(PebbleMessages.signed32(it.dictionary, BridgeProtocol.Key.MESSAGE_TYPE))
    }

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

internal val TEST_ADMISSION = TrustAdmission(0)

internal fun BridgeRuntime.watchAppOpened(watch: WatchIdentifier) =
    watchAppOpened(watch, TEST_ADMISSION)

internal fun BridgeRuntime.watchObserved(watch: WatchIdentifier) =
    watchObserved(watch, TEST_ADMISSION)

internal fun BridgeRuntime.watchAppClosed(watch: WatchIdentifier) =
    watchAppClosed(watch, TEST_ADMISSION)

internal fun BridgeRuntime.handleHeartRate(
    watch: WatchIdentifier,
    sessionId: Long,
    sequence: Long,
    bpm: Int,
    sampledAtEpochSeconds: Long,
): Boolean =
    handleHeartRate(
        watch,
        sessionId,
        sequence,
        bpm,
        sampledAtEpochSeconds,
        TEST_ADMISSION,
    )

internal suspend fun BridgeRuntime.handleCommand(
    watch: WatchIdentifier,
    sessionId: Long,
    commandId: Long,
    command: BridgeProtocol.Command,
    profileName: String?,
    waypointName: String?,
): Boolean =
    handleCommand(
        watch,
        sessionId,
        commandId,
        command,
        profileName,
        waypointName,
        TEST_ADMISSION,
    )

internal suspend fun BridgeRuntime.sendRecordingProfiles(watch: WatchIdentifier): Boolean =
    sendRecordingProfiles(watch, TEST_ADMISSION)

internal suspend fun BridgeRuntime.refresh(watches: Collection<WatchIdentifier>): Boolean =
    refresh(watches.single(), TEST_ADMISSION)
