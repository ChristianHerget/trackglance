package io.github.christianherget.trackglance.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import io.github.christianherget.trackglance.bridge.core.BridgeState
import io.github.christianherget.trackglance.bridge.locus.LocusGateway
import io.github.christianherget.trackglance.bridge.locus.RecordingProfilesResult
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import locus.api.android.ActionBasics
import locus.api.android.objects.VersionCode
import locus.api.android.utils.LocusUtils

/** Shell-only status surface used by the local Podman end-to-end suite. */
class DebugStatusProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(uri.lastPathSegment == STATUS_PATH) { "Unknown debug status path" }
        val values = currentValues()
        val columns =
            projection?.also { requested ->
                require(requested.all(values::containsKey)) { "Unknown debug status column" }
            } ?: COLUMNS
        return MatrixCursor(columns, 1).apply { addRow(columns.map(values::get)) }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            STATUS_METHOD ->
                Bundle().apply {
                    currentValues().forEach { (key, value) -> putString(key, value?.toString()) }
                }
            ACCEPTANCE_START_RECORDING_METHOD -> {
                val appContext = requireNotNull(context).applicationContext
                val version =
                    requireNotNull(LocusUtils.getActiveVersion(appContext, VersionCode.UPDATE_13)) {
                        "Locus Map is not available"
                    }
                val profileName =
                    requireNotNull(arg?.takeIf(String::isNotBlank)) {
                        "A Locus recording profile name is required"
                    }
                ActionBasics.actionTrackRecordStart(appContext, version, profileName)
                Bundle().apply { putString("result", "requested") }
            }
            else -> error("Unknown debug status method")
        }
    }

    private fun currentValues(): Map<String, Any?> {
        val appContext = requireNotNull(context).applicationContext
        val live = BridgeState.status.value
        val gateway = LocusGateway(appContext)
        val snapshot = gateway.readSnapshot()
        val profiles =
            when (val result = gateway.recordingProfiles()) {
                is RecordingProfilesResult.Success -> result.profiles.map { "${it.id}|${it.name}" }
                is RecordingProfilesResult.Failure -> emptyList()
            }
        return linkedMapOf(
            "bridge_version" to BuildConfig.VERSION_NAME,
            "bridge_version_code" to BuildConfig.VERSION_CODE,
            "protocol_version" to BridgeProtocol.VERSION,
            "watch_app_open" to live.watchAppOpen,
            "pebble_app_package" to live.pebbleAppPackage,
            "watch_connected" to live.watchConnected,
            "watch_version" to live.watchVersion,
            "locus_available" to (snapshot.state != BridgeProtocol.RecordingState.UNAVAILABLE),
            "locus_profiles" to profiles.joinToString("|"),
            "active_profile" to snapshot.locusProfileName,
            "recording_state" to snapshot.state.name,
            "watch_heart_rate" to live.lastWatchHeartRate,
            "watch_steps" to live.lastWatchSteps,
            "locus_heart_rate" to snapshot.currentHeartRate,
            "heart_rate_forwarded_at" to live.lastHeartRateForwardedEpochMillis,
            "last_command" to live.lastCommand?.name,
            "last_command_result" to live.lastCommandResult?.name,
            "last_waypoint_name" to live.lastWaypointName,
            "last_error" to live.lastError,
        )
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.trackglance.status"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = unsupported()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        unsupported()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = unsupported()

    private fun <T> unsupported(): T =
        throw UnsupportedOperationException("Debug status is read-only")

    companion object {
        private const val STATUS_PATH = "status"
        private const val STATUS_METHOD = "status"
        private const val ACCEPTANCE_START_RECORDING_METHOD = "acceptance-start-recording"
        private val COLUMNS =
            arrayOf(
                "bridge_version",
                "bridge_version_code",
                "protocol_version",
                "watch_app_open",
                "pebble_app_package",
                "watch_connected",
                "watch_version",
                "locus_available",
                "locus_profiles",
                "active_profile",
                "recording_state",
                "watch_heart_rate",
                "watch_steps",
                "locus_heart_rate",
                "heart_rate_forwarded_at",
                "last_command",
                "last_command_result",
                "last_waypoint_name",
                "last_error",
            )
    }
}
