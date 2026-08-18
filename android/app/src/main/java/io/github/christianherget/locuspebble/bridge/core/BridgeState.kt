package io.github.christianherget.locuspebble.bridge.core

import io.github.christianherget.locuspebble.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class BridgeStatus(
    val watchAppOpen: Boolean = false,
    val pebbleAppPackage: String? = null,
    val watchConnected: Boolean = false,
    val watchVersion: String? = null,
    val locusAvailable: Boolean = false,
    val recordingState: BridgeProtocol.RecordingState = BridgeProtocol.RecordingState.UNAVAILABLE,
    val locusProfiles: List<String>? = null,
    val lastProfileRequestEpochMillis: Long? = null,
    val lastUpdateEpochMillis: Long? = null,
    val lastWatchHeartRate: Int? = null,
    val lastHeartRateForwardedEpochMillis: Long? = null,
    val currentLocusHeartRate: Int? = null,
    
    val diagnosticsError: String? = null,
    val lastError: String? = null,
)

internal fun BridgeStatus.withPebbleSelection(trustedPackage: String?): BridgeStatus = copy(
    pebbleAppPackage = trustedPackage,
    watchConnected = trustedPackage != null && watchConnected,
    watchAppOpen = trustedPackage != null && watchAppOpen,
    watchVersion = watchVersion.takeIf { trustedPackage != null },
)

internal fun BridgeStatus.withPebbleConnectionFailure(message: String): BridgeStatus = copy(
    watchConnected = false,
    diagnosticsError = message,
)

internal fun BridgeStatus.withDiagnosticsSnapshot(
    recordingState: BridgeProtocol.RecordingState,
    sampledAtMillis: Long,
    currentHeartRate: Int?,
    error: String?,
): BridgeStatus = copy(
    locusAvailable = recordingState != BridgeProtocol.RecordingState.UNAVAILABLE,
    recordingState = recordingState,
    lastUpdateEpochMillis = sampledAtMillis,
    currentLocusHeartRate = currentHeartRate,
    diagnosticsError = error,
)

object BridgeState {
    private val mutable = MutableStateFlow(BridgeStatus())
    val status = mutable.asStateFlow()
    fun update(transform: (BridgeStatus) -> BridgeStatus) { mutable.update(transform) }
}
