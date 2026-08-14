package app.locuspebble.bridge.core

import app.locuspebble.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BridgeStatus(
    val watchAppOpen: Boolean = false,
    val pebbleAppPackage: String? = null,
    val watchConnected: Boolean = false,
    val locusAvailable: Boolean = false,
    val recordingState: BridgeProtocol.RecordingState = BridgeProtocol.RecordingState.UNAVAILABLE,
    val lastUpdateEpochMillis: Long? = null,
    val lastError: String? = null,
)

object BridgeState {
    private val mutable = MutableStateFlow(BridgeStatus())
    val status = mutable.asStateFlow()
    fun update(transform: (BridgeStatus) -> BridgeStatus) { mutable.value = transform(mutable.value) }
}
