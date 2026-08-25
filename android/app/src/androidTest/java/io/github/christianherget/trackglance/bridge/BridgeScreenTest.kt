package io.github.christianherget.trackglance.bridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.christianherget.trackglance.bridge.core.BridgeFailure
import io.github.christianherget.trackglance.bridge.core.BridgeFailureKind
import io.github.christianherget.trackglance.bridge.core.BridgeStatus
import io.github.christianherget.trackglance.bridge.core.DiagnosticEntry
import io.github.christianherget.trackglance.bridge.core.DiagnosticSeverity
import io.github.christianherget.trackglance.bridge.core.RefreshMode
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BridgeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun groupedScreenRetainsStatusSettingsAndEmptyDiagnostics() {
        showScreen()

        composeRule.onNodeWithText("TrackGlance Bridge").assertIsDisplayed()
        composeRule.onNodeWithText("Locus Map on your Pebble").assertIsDisplayed()
        composeRule.onNodeWithText("Pebble").assertExists()
        composeRule.onNodeWithText("Locus Map").assertExists()
        composeRule.onNodeWithText("Heart rate").assertExists()
        composeRule.onNodeWithText("Settings").assertExists()
        composeRule.onNodeWithText("Recent warnings and errors").assertExists()
        composeRule.onNodeWithText("No recent warnings or errors.").performScrollTo()
        composeRule.onNodeWithTag("clear-diagnostics").assertIsNotEnabled()
    }

    @Test
    fun dropdownAndDiagnosticClearActionsRemainInteractive() {
        var selected: RefreshMode? = null
        var cleared = false
        val diagnostic =
            DiagnosticEntry(
                failure = BridgeFailure(BridgeFailureKind.LOCUS_UNAVAILABLE),
                severity = DiagnosticSeverity.WARNING,
                firstSeenEpochMillis = 1_000L,
                lastSeenEpochMillis = 2_000L,
                count = 2,
            )
        showScreen(
            diagnosticEntries = listOf(diagnostic),
            onRefreshModeSelected = { selected = it },
            onClearDiagnostics = { cleared = true },
        )

        composeRule.onNodeWithTag("refresh-mode").performScrollTo().performClick()
        composeRule.onNodeWithText("10 seconds").performClick()
        assertEquals(RefreshMode.TEN_SECONDS, selected)

        composeRule
            .onNodeWithTag("clear-diagnostics")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertTrue(cleared)
        composeRule.onNodeWithText("Locus is unavailable").assertExists()
        composeRule.onNodeWithText("2").assertExists()
    }

    private fun showScreen(
        diagnosticEntries: List<DiagnosticEntry> = emptyList(),
        onRefreshModeSelected: (RefreshMode) -> Unit = {},
        onClearDiagnostics: () -> Unit = {},
    ) {
        composeRule.setContent {
            TrackGlanceTheme(darkTheme = false) {
                BridgeScreen(
                    status = healthyStatus(),
                    diagnosticEntries = diagnosticEntries,
                    refreshMode = RefreshMode.ADAPTIVE,
                    versionName = "0.2.3",
                    onRefreshModeSelected = onRefreshModeSelected,
                    onClearDiagnostics = onClearDiagnostics,
                    onOpenLegal = {},
                )
            }
        }
    }
}

private fun healthyStatus() =
    BridgeStatus(
        watchAppOpen = true,
        pebbleAppPackage = "coredevices.coreapp",
        watchConnected = true,
        watchVersion = "0.2.3",
        watchAppLaunchResult = WatchAppLaunchResult.STARTED,
        locusAvailable = true,
        recordingState = BridgeProtocol.RecordingState.RECORDING,
        locusProfiles = listOf("Hiking"),
        currentLocusHeartRate = 128,
    )
