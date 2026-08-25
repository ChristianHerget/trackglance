package io.github.christianherget.trackglance.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.christianherget.trackglance.bridge.core.BridgeStatus
import io.github.christianherget.trackglance.bridge.core.RefreshMode
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol

/** Debug-only deterministic host used to capture the documentation's native Bridge screenshots. */
class BridgeScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val darkTheme = intent.getBooleanExtra(EXTRA_DARK_THEME, false)
        setContent {
            TrackGlanceTheme(darkTheme = darkTheme) {
                BridgeScreen(
                    status = healthyScreenshotStatus(),
                    diagnosticEntries = emptyList(),
                    refreshMode = RefreshMode.ADAPTIVE,
                    versionName = BuildConfig.VERSION_NAME,
                    onRefreshModeSelected = {},
                    onClearDiagnostics = {},
                    onOpenLegal = {},
                )
            }
        }
    }

    private companion object {
        const val EXTRA_DARK_THEME = "dark_theme"
    }
}

private fun healthyScreenshotStatus() =
    BridgeStatus(
        watchAppOpen = true,
        pebbleAppPackage = "coredevices.coreapp",
        watchConnected = true,
        watchVersion = BuildConfig.VERSION_NAME,
        watchAppLaunchResult = WatchAppLaunchResult.STARTED,
        locusAvailable = true,
        recordingState = BridgeProtocol.RecordingState.RECORDING,
        locusProfiles = listOf("Hiking", "Cycling", "Running"),
        lastProfileRequestEpochMillis = 1_786_768_620_000L,
        lastUpdateEpochMillis = 1_786_768_680_000L,
        lastWatchHeartRate = 128,
        lastHeartRateForwardedEpochMillis = 1_786_768_675_000L,
        currentLocusHeartRate = 128,
    )
