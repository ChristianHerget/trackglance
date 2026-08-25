package io.github.christianherget.trackglance.bridge.core

import io.github.christianherget.trackglance.bridge.WatchAppLaunchResult
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BridgeStatus(
    val watchAppOpen: Boolean = false,
    val pebbleAppPackage: String? = null,
    val watchConnected: Boolean = false,
    val watchVersion: String? = null,
    val watchAppLaunchResult: WatchAppLaunchResult? = null,
    val locusAvailable: Boolean = false,
    val recordingState: BridgeProtocol.RecordingState = BridgeProtocol.RecordingState.UNAVAILABLE,
    val activeLocusProfile: String? = null,
    val locusProfiles: List<String>? = null,
    val lastProfileRequestEpochMillis: Long? = null,
    val lastUpdateEpochMillis: Long? = null,
    val lastWatchHeartRate: Int? = null,
    val lastHeartRateForwardedEpochMillis: Long? = null,
    val currentLocusHeartRate: Int? = null,
    val lastCommand: BridgeProtocol.Command? = null,
    val lastCommandResult: BridgeProtocol.Result? = null,
    val lastWaypointName: String? = null,
    val diagnosticsError: BridgeFailure? = null,
    val lastError: BridgeFailure? = null,
)

internal fun BridgeStatus.withPebbleSelection(trustedPackage: String?): BridgeStatus =
    copy(
        pebbleAppPackage = trustedPackage,
        watchConnected = trustedPackage != null && watchConnected,
        watchAppOpen = trustedPackage != null && watchAppOpen,
        watchVersion = watchVersion.takeIf { trustedPackage != null },
    )

internal fun BridgeStatus.withPebbleConnectionFailure(failure: BridgeFailure): BridgeStatus =
    copy(
        watchConnected = false,
        diagnosticsError = failure,
    )

internal fun BridgeStatus.withDiagnosticsSnapshot(
    recordingState: BridgeProtocol.RecordingState,
    activeLocusProfile: String?,
    sampledAtMillis: Long,
    currentHeartRate: Int?,
    error: BridgeFailure?,
): BridgeStatus =
    copy(
        locusAvailable = recordingState != BridgeProtocol.RecordingState.UNAVAILABLE,
        recordingState = recordingState,
        activeLocusProfile = activeLocusProfile,
        lastUpdateEpochMillis = sampledAtMillis,
        currentLocusHeartRate = currentHeartRate,
        diagnosticsError = error,
    )

object BridgeState {
    private val mutable = MutableStateFlow(BridgeStatus())
    private val updateLock = Any()
    val status = mutable.asStateFlow()

    fun update(transform: (BridgeStatus) -> BridgeStatus) {
        synchronized(updateLock) {
            val previous = mutable.value
            val next = transform(previous)
            mutable.value = next
            if (next.lastError != null && next.lastError !== previous.lastError) {
                RecentDiagnostics.record(next.lastError)
            }
            if (
                next.diagnosticsError != null && next.diagnosticsError !== previous.diagnosticsError
            ) {
                RecentDiagnostics.record(next.diagnosticsError)
            }
        }
    }
}

enum class DiagnosticSeverity {
    WARNING,
    ERROR,
}

data class DiagnosticEntry(
    val failure: BridgeFailure,
    val severity: DiagnosticSeverity,
    val firstSeenEpochMillis: Long,
    val lastSeenEpochMillis: Long,
    val count: Int,
)

open class DiagnosticHistory(
    private val capacity: Int = 20,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries = mutable.asStateFlow()

    fun record(failure: BridgeFailure) {
        val now = nowMillis()
        mutable.update { entries ->
            val existing = entries.firstOrNull { it.failure == failure }
            val updated =
                existing?.copy(lastSeenEpochMillis = now, count = existing.count + 1)
                    ?: DiagnosticEntry(failure, failure.severity(), now, now, 1)
            (listOf(updated) + entries.filterNot { it.failure == failure }).take(capacity)
        }
    }

    fun clear() {
        mutable.value = emptyList()
    }
}

fun BridgeFailure.severity(): DiagnosticSeverity =
    when (kind) {
        BridgeFailureKind.LOCUS_UNAVAILABLE,
        BridgeFailureKind.LOCUS_RETURNED_NO_PROFILES,
        BridgeFailureKind.PEBBLE_COMPANION_NOT_INSTALLED,
        BridgeFailureKind.PEBBLE_COMPANION_NOT_SELECTED,
        BridgeFailureKind.WATCHAPP_LAUNCH_TIMED_OUT -> DiagnosticSeverity.WARNING
        else -> DiagnosticSeverity.ERROR
    }

object RecentDiagnostics : DiagnosticHistory()
