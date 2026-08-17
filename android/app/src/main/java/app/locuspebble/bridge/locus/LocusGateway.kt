package app.locuspebble.bridge.locus

import android.content.Context
import android.content.Intent
import app.locuspebble.bridge.protocol.BridgeProtocol
import locus.api.android.ActionBasics
import locus.api.android.objects.LocusVersion
import locus.api.android.objects.VersionCode
import locus.api.android.utils.LocusUtils

interface LocusBridgeGateway {
    fun readSnapshot(nowMillis: Long = System.currentTimeMillis()): BridgeProtocol.Snapshot
    fun sendHeartRate(bpm: Int): Boolean
    fun recordingProfiles(): List<String>
    fun execute(
        command: BridgeProtocol.Command,
        profileName: String? = null,
        waypointName: String? = null,
    ): BridgeProtocol.Result
}

class LocusGateway(private val context: Context) : LocusBridgeGateway {
    private fun activeVersion(): LocusVersion? =
        LocusUtils.getActiveVersion(context, VersionCode.UPDATE_13)

    override fun readSnapshot(nowMillis: Long): BridgeProtocol.Snapshot = try {
        val version = activeVersion() ?: return unavailableSnapshot(nowMillis)
        readSnapshot(version, nowMillis)
    } catch (_: Exception) {
        unavailableSnapshot(nowMillis)
    }

    private fun readSnapshot(version: LocusVersion, nowMillis: Long): BridgeProtocol.Snapshot {
        val update = ActionBasics.getUpdateContainer(context, version)
            ?: return unavailableSnapshot(nowMillis)
        val state = when {
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
            altitudeMetres = update.locMyLocation.altitude.takeIf { update.isGpsLocValid },
            ascentMetres = stats?.elePositiveHeight,
            descentMetres = stats?.eleNegativeHeight?.let { kotlin.math.abs(it) },
            verticalSpeedMps = update.speedVertical.takeIf { update.isGpsLocValid },
            slopePercent = update.slope.takeIf { update.isGpsLocValid },
            averageHeartRate = stats?.heartRateAverage?.takeIf { it > 0 },
            currentHeartRate = update.locMyLocation.sensorHeartRate?.toInt()?.takeIf { it > 0 },
            maxHeartRate = stats?.heartRateMax?.takeIf { it > 0 },
            averageCadence = stats?.cadenceAverage?.takeIf { it > 0 },
            maxCadence = stats?.cadenceMax?.takeIf { it > 0 },
            averagePower = stats?.powerAverage?.takeIf { it > 0 },
            maxPower = stats?.powerMax?.takeIf { it > 0 },
            energyKcal = stats?.energy?.takeIf { it > 0 },
            locusProfileName = update.trackRecProfileName.takeIf { it.isNotBlank() },
        )
    }

    /** Sends one live sensor value. Locus provides no acknowledgement for this broadcast. */
    override fun sendHeartRate(bpm: Int): Boolean {
        if (bpm !in 25..250) return false
        return try {
            val version = activeVersion() ?: return false
            val intent = Intent(LocusHeartRateTask.ACTION)
                .putExtra(LocusHeartRateTask.EXTRA_TASKS, LocusHeartRateTask.payload(bpm))
            LocusUtils.sendBroadcast(context, intent, version)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun recordingProfiles(): List<String> = try {
        activeVersion()?.let(::recordingProfiles).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    private fun recordingProfiles(version: LocusVersion): List<String> =
        ActionBasics.getTrackRecordingProfiles(context, version).map { it.name }

    override fun execute(
        command: BridgeProtocol.Command,
        profileName: String?,
        waypointName: String?,
    ): BridgeProtocol.Result {
        return try {
            val version = activeVersion() ?: return BridgeProtocol.Result.LOCUS_UNAVAILABLE
            val current = readSnapshot(version, System.currentTimeMillis())
            if (current.state == BridgeProtocol.RecordingState.UNAVAILABLE) {
                return BridgeProtocol.Result.LOCUS_UNAVAILABLE
            }
            when (command) {
                BridgeProtocol.Command.START -> {
                    if (current.state != BridgeProtocol.RecordingState.STOPPED) return BridgeProtocol.Result.INVALID_STATE
                    if (!BridgeProtocol.validLocusProfileName(profileName)) return BridgeProtocol.Result.INVALID_PROFILE
                    val installedName = BridgeProtocol.autoMatchProfile(profileName!!, recordingProfiles(version))
                        ?: return BridgeProtocol.Result.PROFILE_NOT_FOUND
                    ActionBasics.actionTrackRecordStart(context, version, installedName)
                }
                BridgeProtocol.Command.PAUSE_RESUME -> {
                    if (current.state != BridgeProtocol.RecordingState.RECORDING && current.state != BridgeProtocol.RecordingState.PAUSED) {
                        return BridgeProtocol.Result.INVALID_STATE
                    }
                    when (LocusCommandRouting.actionFor(command, current.state)) {
                        LocusRecordingAction.PAUSE -> ActionBasics.actionTrackRecordPause(context, version)
                        LocusRecordingAction.START_OR_RESUME -> ActionBasics.actionTrackRecordStart(context, version)
                        else -> error("Unexpected pause/resume routing")
                    }
                }
                BridgeProtocol.Command.STOP_SAVE -> {
                    if (LocusCommandRouting.actionFor(command, current.state) != LocusRecordingAction.STOP_SAVE) {
                        return BridgeProtocol.Result.INVALID_STATE
                    }
                    ActionBasics.actionTrackRecordStop(context, version, true)
                }
                BridgeProtocol.Command.ADD_WAYPOINT,
                BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE,
                -> {
                    if (current.state != BridgeProtocol.RecordingState.RECORDING) return BridgeProtocol.Result.INVALID_STATE
                    val resolvedName = LocusCommandRouting.waypointNameFor(command, waypointName)
                        ?: return BridgeProtocol.Result.INVALID_WAYPOINT_NAME
                    ActionBasics.actionTrackRecordAddWpt(
                        context,
                        version,
                        resolvedName,
                        LocusCommandRouting.WAYPOINT_AUTO_SAVE,
                    )
                }
            }
            BridgeProtocol.Result.OK
        } catch (_: Exception) {
            BridgeProtocol.Result.FAILED
        }
    }

    private fun unavailableSnapshot(nowMillis: Long) = BridgeProtocol.Snapshot(
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

enum class LocusRecordingAction { START_OR_RESUME, PAUSE, STOP_SAVE, ADD_WAYPOINT, INVALID }

object LocusCommandRouting {
    const val WAYPOINT_AUTO_SAVE = true
    const val DEFAULT_WAYPOINT_NAME = "Pebble waypoint"

    fun waypointNameFor(command: BridgeProtocol.Command, dictatedName: String?): String? = when (command) {
        BridgeProtocol.Command.ADD_WAYPOINT -> DEFAULT_WAYPOINT_NAME
        BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE -> dictatedName.takeIf(BridgeProtocol::validWaypointName)
        else -> null
    }

    fun actionFor(
        command: BridgeProtocol.Command,
        state: BridgeProtocol.RecordingState,
    ): LocusRecordingAction = when (command) {
        BridgeProtocol.Command.START -> if (state == BridgeProtocol.RecordingState.STOPPED) {
            LocusRecordingAction.START_OR_RESUME
        } else {
            LocusRecordingAction.INVALID
        }
        BridgeProtocol.Command.PAUSE_RESUME -> when (state) {
            BridgeProtocol.RecordingState.RECORDING -> LocusRecordingAction.PAUSE
            BridgeProtocol.RecordingState.PAUSED -> LocusRecordingAction.START_OR_RESUME
            else -> LocusRecordingAction.INVALID
        }
        BridgeProtocol.Command.STOP_SAVE -> if (
            state == BridgeProtocol.RecordingState.RECORDING || state == BridgeProtocol.RecordingState.PAUSED
        ) LocusRecordingAction.STOP_SAVE else LocusRecordingAction.INVALID
        BridgeProtocol.Command.ADD_WAYPOINT,
        BridgeProtocol.Command.ADD_WAYPOINT_WITH_NOTE,
        -> if (state == BridgeProtocol.RecordingState.RECORDING) {
            LocusRecordingAction.ADD_WAYPOINT
        } else {
            LocusRecordingAction.INVALID
        }
    }
}
