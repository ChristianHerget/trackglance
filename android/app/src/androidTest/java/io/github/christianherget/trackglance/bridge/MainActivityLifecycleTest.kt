package io.github.christianherget.trackglance.bridge

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import io.github.christianherget.trackglance.bridge.core.BridgeFailure
import io.github.christianherget.trackglance.bridge.core.BridgeFailureKind
import io.github.christianherget.trackglance.bridge.core.BridgeStatus
import io.github.christianherget.trackglance.bridge.core.DiagnosticEntry
import io.github.christianherget.trackglance.bridge.core.DiagnosticSeverity
import io.github.christianherget.trackglance.bridge.core.RefreshMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class MainActivityLifecycleTest {
    @get:Rule val composeRule = createAndroidComposeRule<LifecycleCollectionTestActivity>()

    @Test
    fun stoppedScreenCollectsNothingAndRendersLatestStateWhenStarted() {
        val status =
            MutableStateFlow(
                BridgeStatus(
                    pebbleAppPackage = "coredevices.coreapp",
                    watchConnected = true,
                    watchVersion = "before-stop",
                )
            )
        val diagnostics = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
        val refreshMode = MutableStateFlow(RefreshMode.ADAPTIVE)

        composeRule.setContent {
            TrackGlanceTheme(darkTheme = false) {
                LifecycleAwareBridgeScreen(
                    statusFlow = status,
                    diagnosticEntriesFlow = diagnostics,
                    refreshModeFlow = refreshMode,
                    versionName = "expected-version",
                    onRefreshModeSelected = {},
                    onClearDiagnostics = {},
                    onOpenLegal = {},
                )
            }
        }
        composeRule.waitUntil {
            allFlowsHaveOneSubscriber(status, diagnostics, refreshMode)
        }

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.waitUntil { allFlowsHaveNoSubscribers(status, diagnostics, refreshMode) }

        status.value = status.value.copy(watchVersion = "emitted-while-stopped")
        diagnostics.value =
            listOf(
                DiagnosticEntry(
                    failure = BridgeFailure(BridgeFailureKind.LOCUS_UNAVAILABLE),
                    severity = DiagnosticSeverity.WARNING,
                    firstSeenEpochMillis = 1_000L,
                    lastSeenEpochMillis = 1_000L,
                    count = 1,
                )
            )
        refreshMode.value = RefreshMode.TEN_SECONDS

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        composeRule.waitUntil { allFlowsHaveOneSubscriber(status, diagnostics, refreshMode) }
        // Collection has restarted at STARTED; RESUMED lets the test drive display frames.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.onNodeWithText("emitted-while-stopped", substring = true).assertIsDisplayed()
        composeRule.onNodeWithTag("refresh-mode").performScrollTo().assertTextContains("10 seconds")
        composeRule.onNodeWithText("Locus is unavailable").performScrollTo().assertIsDisplayed()
    }
}

private fun allFlowsHaveOneSubscriber(
    status: MutableStateFlow<BridgeStatus>,
    diagnostics: MutableStateFlow<List<DiagnosticEntry>>,
    refreshMode: MutableStateFlow<RefreshMode>,
) =
    status.subscriptionCount.value == 1 &&
        diagnostics.subscriptionCount.value == 1 &&
        refreshMode.subscriptionCount.value == 1

private fun allFlowsHaveNoSubscribers(
    status: MutableStateFlow<BridgeStatus>,
    diagnostics: MutableStateFlow<List<DiagnosticEntry>>,
    refreshMode: MutableStateFlow<RefreshMode>,
) =
    status.subscriptionCount.value == 0 &&
        diagnostics.subscriptionCount.value == 0 &&
        refreshMode.subscriptionCount.value == 0
