package io.github.christianherget.trackglance.bridge.locus

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import io.github.christianherget.trackglance.bridge.core.BridgeFailure
import io.github.christianherget.trackglance.bridge.core.BridgeFailureKind
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import locus.api.android.ActionBasics
import locus.api.android.objects.LocusVersion
import locus.api.android.objects.VersionCode
import locus.api.android.utils.LocusUtils

interface LocusBridgeGateway {
    fun readSnapshot(nowMillis: Long = System.currentTimeMillis()): BridgeProtocol.Snapshot

    fun sendHeartRate(bpm: Int): Boolean

    fun recordingProfiles(): RecordingProfilesResult

    fun execute(
        command: BridgeProtocol.Command,
        profileName: String? = null,
        waypointName: String? = null,
    ): BridgeProtocol.Result

    /** Returns the target chosen by the same state read that routed a state-changing broadcast. */
    fun executeWithExpectedState(
        command: BridgeProtocol.Command,
        profileName: String? = null,
        waypointName: String? = null,
    ): CommandExecution = CommandExecution(execute(command, profileName, waypointName))
}

data class CommandExecution(
    val result: BridgeProtocol.Result,
    val expectedState: BridgeProtocol.RecordingState? = null,
)

sealed interface RecordingProfilesResult {
    data class Success(val profiles: List<BridgeProtocol.RecordingProfile>) :
        RecordingProfilesResult

    data class Failure(val failure: BridgeFailure) : RecordingProfilesResult
}

class LocusGateway(context: Context) : LocusBridgeGateway {
    private val context = context.applicationContext
    private val unitPreferences = sharedUnitPreferences(this.context)

    init {
        // Prime at process gateway startup; every gateway shares the same rate-limited cache.
        unitPreferences.current()
    }

    companion object {
        @Volatile private var processUnitPreferences: LocusUnitPreferencesCache? = null

        private fun sharedUnitPreferences(context: Context): LocusUnitPreferencesCache =
            processUnitPreferences
                ?: synchronized(this) {
                    processUnitPreferences
                        ?: LocusUnitPreferencesCache(
                                read = {
                                    val version =
                                        LocusUtils.getActiveVersion(context, VersionCode.UPDATE_13)
                                            ?: return@LocusUnitPreferencesCache null
                                    ActionBasics.getLocusInfo(context, version)?.let {
                                        RawLocusUnitPreferences(
                                            length = it.unitsFormatLength,
                                            altitude = it.unitsFormatAltitude,
                                            speed = it.unitsFormatSpeed,
                                            slope = it.unitsFormatSlope,
                                            energy = it.unitsFormatEnergy,
                                        )
                                    }
                                },
                                monotonicMillis = SystemClock::elapsedRealtime,
                            )
                            .also { processUnitPreferences = it }
                }
    }

    private fun activeVersion(): LocusVersion? =
        LocusUtils.getActiveVersion(context, VersionCode.UPDATE_13)

    override fun readSnapshot(nowMillis: Long): BridgeProtocol.Snapshot =
        try {
            val version = activeVersion() ?: return unavailableSnapshot(nowMillis)
            readSnapshot(version, nowMillis)
        } catch (_: Exception) {
            unavailableSnapshot(nowMillis)
        }

    private fun readSnapshot(version: LocusVersion, nowMillis: Long): BridgeProtocol.Snapshot {
        val update =
            ActionBasics.getUpdateContainer(context, version)
                ?: return unavailableSnapshot(nowMillis)
        val state =
            when {
                !update.isTrackRecRecording -> BridgeProtocol.RecordingState.STOPPED
                update.isTrackRecPaused -> BridgeProtocol.RecordingState.PAUSED
                else -> BridgeProtocol.RecordingState.RECORDING
            }
        val stats = update.trackRecStats
        return BridgeProtocol.Snapshot(
            state = state,
            sampledAtEpochSeconds = nowMillis / 1000,
            elapsedSeconds = (stats?.totalTime ?: 0L) / 1000,
            movingSeconds = stats?.totalTimeMove?.div(1000),
            distanceMetres = stats?.totalLength,
            movingDistanceMetres = stats?.totalLengthMove,
            currentSpeedMps = update.locMyLocation.speed.takeIf { update.isGpsLocValid },
            averageSpeedMps = stats?.getSpeedAverage(false),
            maxSpeedMps = stats?.speedMax,
            currentPaceMinutesPerKilometre =
                update.pace.takeIf { update.isGpsLocValid && it > 0.0f },
            altitudeMetres = update.locMyLocation.altitude.takeIf { update.isGpsLocValid },
            ascentMetres = stats?.elePositiveHeight,
            descentMetres = stats?.eleNegativeHeight?.let { kotlin.math.abs(it) },
            verticalSpeedMps = update.speedVertical.takeIf { update.isGpsLocValid },
            slopeRatio = update.slope.takeIf { update.isGpsLocValid },
            averageHeartRate = stats?.heartRateAverage?.takeIf { it > 0 },
            currentHeartRate = update.locMyLocation.sensorHeartRate?.toInt()?.takeIf { it > 0 },
            maxHeartRate = stats?.heartRateMax?.takeIf { it > 0 },
            averageCadence = stats?.cadenceAverage?.takeIf { it > 0 },
            maxCadence = stats?.cadenceMax?.takeIf { it > 0 },
            averagePower = stats?.powerAverage?.takeIf { it > 0 },
            maxPower = stats?.powerMax?.takeIf { it > 0 },
            energyJoules = stats?.energy?.takeIf { it > 0 },
            recordingStartMillis = stats?.startTime?.takeIf { it > 0 },
            locusProfileName = update.trackRecProfileName.takeIf { it.isNotBlank() },
            unitPreferences = unitPreferences.current(),
        )
    }

    /** Sends one live sensor value. Locus provides no acknowledgement for this broadcast. */
    override fun sendHeartRate(bpm: Int): Boolean {
        if (bpm !in 25..250) return false
        return try {
            val version = activeVersion() ?: return false
            val intent =
                Intent(LocusHeartRateTask.ACTION)
                    .putExtra(LocusHeartRateTask.EXTRA_TASKS, LocusHeartRateTask.payload(bpm))
            LocusUtils.sendBroadcast(context, intent, version)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun recordingProfiles(): RecordingProfilesResult =
        try {
            val version =
                activeVersion()
                    ?: return RecordingProfilesResult.Failure(
                        BridgeFailure(BridgeFailureKind.LOCUS_UNAVAILABLE)
                    )
            RecordingProfilesResult.Success(recordingProfiles(version))
        } catch (error: Exception) {
            RecordingProfilesResult.Failure(
                BridgeFailure(
                    BridgeFailureKind.LOCUS_PROFILE_QUERY_FAILED,
                    error.message?.takeIf(String::isNotBlank),
                )
            )
        }

    private fun recordingProfiles(version: LocusVersion): List<BridgeProtocol.RecordingProfile> =
        ActionBasics.getTrackRecordingProfiles(context, version).map {
            BridgeProtocol.RecordingProfile(it.id, it.name)
        }

    override fun execute(
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
    ): BridgeProtocol.Result = executeWithExpectedState(command, profileName, waypointName).result

    override fun executeWithExpectedState(
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
    ): CommandExecution {
        return try {
            val version =
                activeVersion() ?: return CommandExecution(BridgeProtocol.Result.LOCUS_UNAVAILABLE)
            val current = readSnapshot(version, System.currentTimeMillis())
            if (current.state == BridgeProtocol.RecordingState.UNAVAILABLE) {
                return CommandExecution(BridgeProtocol.Result.LOCUS_UNAVAILABLE)
            }
            val expectedState =
                when (command) {
                    BridgeProtocol.Command.START ->
                        return CommandExecution(BridgeProtocol.Result.INVALID_STATE)
                    BridgeProtocol.Command.PAUSE_RESUME -> {
                        if (
                            current.state != BridgeProtocol.RecordingState.RECORDING &&
                                current.state != BridgeProtocol.RecordingState.PAUSED
                        ) {
                            return CommandExecution(BridgeProtocol.Result.INVALID_STATE)
                        }
                        when (LocusCommandRouting.actionFor(command, current.state)) {
                            LocusRecordingAction.PAUSE -> {
                                ActionBasics.actionTrackRecordPause(context, version)
                                BridgeProtocol.RecordingState.PAUSED
                            }
                            LocusRecordingAction.START_OR_RESUME -> {
                                ActionBasics.actionTrackRecordStart(context, version)
                                BridgeProtocol.RecordingState.RECORDING
                            }
                            else -> error("Unexpected pause/resume routing")
                        }
                    }
                    BridgeProtocol.Command.STOP_SAVE -> {
                        if (
                            LocusCommandRouting.actionFor(command, current.state) !=
                                LocusRecordingAction.STOP_SAVE
                        ) {
                            return CommandExecution(BridgeProtocol.Result.INVALID_STATE)
                        }
                        ActionBasics.actionTrackRecordStop(context, version, true)
                        BridgeProtocol.RecordingState.STOPPED
                    }
                    BridgeProtocol.Command.ADD_WAYPOINT,
                    BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE -> {
                        if (current.state != BridgeProtocol.RecordingState.RECORDING) {
                            return CommandExecution(BridgeProtocol.Result.INVALID_STATE)
                        }
                        val resolvedName =
                            LocusCommandRouting.waypointNameFor(command, waypointName)
                                ?: return CommandExecution(
                                    BridgeProtocol.Result.INVALID_WAYPOINT_NAME
                                )
                        ActionBasics.actionTrackRecordAddWpt(
                            context,
                            version,
                            resolvedName,
                            LocusCommandRouting.WAYPOINT_AUTO_SAVE,
                        )
                        null
                    }
                }
            CommandExecution(BridgeProtocol.Result.OK, expectedState)
        } catch (_: Exception) {
            CommandExecution(BridgeProtocol.Result.FAILED)
        }
    }

    private fun unavailableSnapshot(nowMillis: Long) =
        BridgeProtocol.Snapshot(
            state = BridgeProtocol.RecordingState.UNAVAILABLE,
            sampledAtEpochSeconds = nowMillis / 1000,
        )
}

object LocusHeartRateTask {
    const val ACTION = "com.asamm.locus.DATA_TASK"
    const val EXTRA_TASKS = "tasks"

    fun payload(bpm: Int): String {
        require(bpm in 25..250)
        return "{heart_rate:{data:${bpm}.0}}"
    }
}

enum class LocusRecordingAction {
    START_OR_RESUME,
    PAUSE,
    STOP_SAVE,
    ADD_WAYPOINT,
    INVALID,
}

object LocusCommandRouting {
    const val WAYPOINT_AUTO_SAVE = true
    const val DEFAULT_WAYPOINT_NAME = "Pebble waypoint"

    fun waypointNameFor(command: BridgeProtocol.Command, dictatedName: String?): String? =
        when (command) {
            BridgeProtocol.Command.ADD_WAYPOINT -> DEFAULT_WAYPOINT_NAME
            BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE ->
                dictatedName.takeIf(BridgeProtocol::validWaypointName)
            else -> null
        }

    fun actionFor(
        command: BridgeProtocol.Command,
        state: BridgeProtocol.RecordingState,
    ): LocusRecordingAction =
        when (command) {
            BridgeProtocol.Command.START -> LocusRecordingAction.INVALID
            BridgeProtocol.Command.PAUSE_RESUME ->
                when (state) {
                    BridgeProtocol.RecordingState.RECORDING -> LocusRecordingAction.PAUSE
                    BridgeProtocol.RecordingState.PAUSED -> LocusRecordingAction.START_OR_RESUME
                    else -> LocusRecordingAction.INVALID
                }
            BridgeProtocol.Command.STOP_SAVE ->
                if (
                    state == BridgeProtocol.RecordingState.RECORDING ||
                        state == BridgeProtocol.RecordingState.PAUSED
                )
                    LocusRecordingAction.STOP_SAVE
                else LocusRecordingAction.INVALID
            BridgeProtocol.Command.ADD_WAYPOINT,
            BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE ->
                if (state == BridgeProtocol.RecordingState.RECORDING) {
                    LocusRecordingAction.ADD_WAYPOINT
                } else {
                    LocusRecordingAction.INVALID
                }
        }
}
