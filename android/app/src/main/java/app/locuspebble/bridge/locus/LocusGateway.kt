package app.locuspebble.bridge.locus

import android.content.Context
import app.locuspebble.bridge.protocol.BridgeProtocol
import locus.api.android.ActionBasics
import locus.api.android.objects.LocusVersion
import locus.api.android.utils.LocusUtils

class LocusGateway(private val context: Context) {
    private fun activeVersion(): LocusVersion? = LocusUtils.getActiveVersion(context)

    fun readSnapshot(nowMillis: Long = System.currentTimeMillis()): BridgeProtocol.Snapshot {
        val version = activeVersion() ?: return BridgeProtocol.Snapshot(
            state = BridgeProtocol.RecordingState.UNAVAILABLE,
            sampledAtEpochSeconds = nowMillis / 1000,
        )
        val update = ActionBasics.getUpdateContainer(context, version)
            ?: return BridgeProtocol.Snapshot(BridgeProtocol.RecordingState.UNAVAILABLE, nowMillis / 1000)
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
            distanceMetres = stats?.totalLength ?: 0f,
            currentSpeedMps = update.locMyLocation.speed ?: 0f,
            averageSpeedMps = stats?.getSpeedAverage(false) ?: 0f,
            altitudeMetres = update.locMyLocation.altitude ?: 0.0,
            ascentMetres = stats?.elePositiveHeight ?: 0f,
        )
    }

    fun execute(command: BridgeProtocol.Command): BridgeProtocol.Result {
        val version = activeVersion() ?: return BridgeProtocol.Result.LOCUS_UNAVAILABLE
        val current = readSnapshot()
        return try {
            when (command) {
                BridgeProtocol.Command.START -> {
                    if (current.state != BridgeProtocol.RecordingState.STOPPED) return BridgeProtocol.Result.INVALID_STATE
                    ActionBasics.actionTrackRecordStart(context, version)
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
                    if (current.state == BridgeProtocol.RecordingState.STOPPED) return BridgeProtocol.Result.INVALID_STATE
                    ActionBasics.actionTrackRecordStop(context, version, true)
                }
                BridgeProtocol.Command.ADD_WAYPOINT -> {
                    if (current.state != BridgeProtocol.RecordingState.RECORDING) return BridgeProtocol.Result.INVALID_STATE
                    ActionBasics.actionTrackRecordAddWpt(context, version, "Pebble waypoint", true)
                }
            }
            BridgeProtocol.Result.OK
        } catch (_: Exception) {
            BridgeProtocol.Result.FAILED
        }
    }
}

enum class LocusRecordingAction { START_OR_RESUME, PAUSE, STOP_SAVE, ADD_WAYPOINT, INVALID }

object LocusCommandRouting {
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
        BridgeProtocol.Command.ADD_WAYPOINT -> if (state == BridgeProtocol.RecordingState.RECORDING) {
            LocusRecordingAction.ADD_WAYPOINT
        } else {
            LocusRecordingAction.INVALID
        }
    }
}
