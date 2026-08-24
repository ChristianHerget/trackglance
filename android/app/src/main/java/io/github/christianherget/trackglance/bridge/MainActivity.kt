package io.github.christianherget.trackglance.bridge

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.christianherget.trackglance.bridge.core.BoundedAbandonableCallExecutor
import io.github.christianherget.trackglance.bridge.core.BridgeFailure
import io.github.christianherget.trackglance.bridge.core.BridgeFailureKind
import io.github.christianherget.trackglance.bridge.core.BridgeRuntime
import io.github.christianherget.trackglance.bridge.core.BridgeState
import io.github.christianherget.trackglance.bridge.core.DiagnosticSeverity
import io.github.christianherget.trackglance.bridge.core.Preferences
import io.github.christianherget.trackglance.bridge.core.RecentDiagnostics
import io.github.christianherget.trackglance.bridge.core.RefreshMode
import io.github.christianherget.trackglance.bridge.core.withDiagnosticsSnapshot
import io.github.christianherget.trackglance.bridge.core.withPebbleConnectionFailure
import io.github.christianherget.trackglance.bridge.core.withPebbleSelection
import io.github.christianherget.trackglance.bridge.locus.LocusGateway
import io.github.christianherget.trackglance.bridge.pebble.CoreAppConnectionKind
import io.github.christianherget.trackglance.bridge.pebble.TRUSTED_CORE_APP_PACKAGE
import io.github.christianherget.trackglance.bridge.pebble.TrustedPebbleCompanionProvider
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class MainActivity : ComponentActivity() {
    private lateinit var refreshModePreference: RefreshModePreferenceState
    private lateinit var watchAppLauncher: WatchAppLauncher
    private val diagnosticsMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        refreshModePreference =
            RefreshModePreferenceState(readPreference = { Preferences.refreshMode(appContext) })
        watchAppLauncher = WatchAppLauncher.create(appContext)
        setContent { MaterialTheme { DiagnosticsScreen() } }
        lifecycleScope.launch {
            // The safe default renders immediately; the first SharedPreferences read is never a
            // Compose/main-thread constructor side effect.
            refreshModePreference.load()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                runPeriodicDiagnostics(::refreshDiagnostics)
            }
        }
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (!isLocusWatchLaunchIntent(intent?.action)) return
        lifecycleScope.launch {
            val result = watchAppLauncher.launch()
            Log.i(TAG, "Locus watchapp auto-start result: $result")
            BridgeState.update { status ->
                status.copy(
                    watchAppLaunchResult = result,
                    lastError =
                        when (result) {
                            WatchAppLaunchResult.LOOKUP_FAILED ->
                                BridgeFailure(BridgeFailureKind.PEBBLE_WATCH_LOOKUP_FAILED)
                            WatchAppLaunchResult.LAUNCH_FAILED ->
                                BridgeFailure(BridgeFailureKind.WATCHAPP_LAUNCH_FAILED)
                            WatchAppLaunchResult.TIMED_OUT ->
                                BridgeFailure(BridgeFailureKind.WATCHAPP_LAUNCH_TIMED_OUT)
                            WatchAppLaunchResult.UNTRUSTED_COMPANION,
                            WatchAppLaunchResult.STALE_COMPANION ->
                                BridgeFailure(BridgeFailureKind.PEBBLE_COMPANION_UNTRUSTED)
                            WatchAppLaunchResult.STARTED,
                            WatchAppLaunchResult.NO_CONNECTED_WATCH -> status.lastError
                        },
                )
            }
        }
    }

    private suspend fun refreshDiagnostics() = diagnosticsMutex.withLock {
        var pebbleError: BridgeFailure? = null
        try {
            // Runtime construction initializes process-local bridge state off the main thread.
            withContext(Dispatchers.IO) { BridgeRuntime.get(applicationContext) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            pebbleError = BridgeFailure.technical(error)
        }
        val companionPin = TrustedPebbleCompanionProvider.get(this@MainActivity)
        val trusted =
            try {
                companionPin.ensureTrustedBounded()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                pebbleError = BridgeFailure.technical(error)
                false
            }
        val connection = TrustedPebbleCompanionProvider.inspect(this@MainActivity)
        val coreSelected = trusted && connection.available
        val selected = TRUSTED_CORE_APP_PACKAGE.takeIf { coreSelected }
        if (!coreSelected && pebbleError == null) {
            pebbleError =
                when (connection.kind) {
                    CoreAppConnectionKind.NOT_INSTALLED ->
                        BridgeFailure(BridgeFailureKind.PEBBLE_COMPANION_NOT_INSTALLED)
                    CoreAppConnectionKind.NOT_SELECTED ->
                        BridgeFailure(
                            BridgeFailureKind.PEBBLE_COMPANION_NOT_SELECTED,
                            connection.detail,
                        )
                    CoreAppConnectionKind.SELECTED ->
                        BridgeFailure(BridgeFailureKind.PEBBLE_COMPANION_NOT_SELECTED)
                }
        }
        BridgeState.update { it.withPebbleSelection(selected) }
        if (!coreSelected) BridgeRuntime.resetForCompanionTrustLoss()
        val snapshot =
            withContext(Dispatchers.IO) { LocusGateway(this@MainActivity).readSnapshot() }
        BridgeState.update {
            it.withDiagnosticsSnapshot(
                recordingState = snapshot.state,
                activeLocusProfile = snapshot.locusProfileName,
                sampledAtMillis = System.currentTimeMillis(),
                currentHeartRate = snapshot.currentHeartRate,
                error =
                    pebbleError
                        ?: if (snapshot.state == BridgeProtocol.RecordingState.UNAVAILABLE)
                            BridgeFailure(BridgeFailureKind.LOCUS_UNAVAILABLE)
                        else null,
            )
        }
        if (selected != null) {
            try {
                val infoContext = applicationContext
                val outcome =
                    generationGuardedForeignQuery(
                        executor = WATCH_QUERY_EXECUTOR,
                        timeoutMillis = CONNECTION_QUERY_TIMEOUT_MILLIS,
                        admit = {
                            TrustedPebbleCompanionProvider.captureTrustedOutboundAdmission(
                                this@MainActivity
                            )
                        },
                        query = {
                            // ContentResolver.query is synchronous beneath this Flow. If a foreign
                            // provider hangs, the bounded worker may be abandoned without retaining
                            // the outbound revocation lease or the Activity's main coroutine.
                            runBlocking {
                                withTimeout(CONNECTION_QUERY_TIMEOUT_MILLIS) {
                                    DefaultPebbleInfoRetriever(infoContext)
                                        .getConnectedWatches()
                                        .first()
                                }
                            }
                        },
                        publishIfCurrent = { admission, watches ->
                            TrustedPebbleCompanionProvider.withOutboundAdmission(
                                this@MainActivity,
                                admission,
                            ) {
                                BridgeState.update {
                                    it.copy(watchConnected = watches.isNotEmpty())
                                }
                            }
                        },
                    )
                when (outcome) {
                    GuardedForeignQueryOutcome.PUBLISHED -> Unit
                    GuardedForeignQueryOutcome.STALE -> return@withLock
                    GuardedForeignQueryOutcome.UNTRUSTED ->
                        error(
                            "Pebble App selection changed while querying connected Pebble watches"
                        )
                    GuardedForeignQueryOutcome.FAILED ->
                        error("Timed out while querying connected Pebble watches")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val failure = BridgeFailure.technical(error)
                val stillTrusted =
                    try {
                        companionPin.guard.isTrusted()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                if (!stillTrusted) {
                    BridgeRuntime.resetForCompanionTrustLoss()
                }
                BridgeState.update { status ->
                    val current = if (stillTrusted) status else status.withPebbleSelection(null)
                    current.withPebbleConnectionFailure(failure)
                }
            }
        }
    }

    @Composable
    private fun DiagnosticsScreen() {
        val status by BridgeState.status.collectAsState()
        val diagnosticEntries by RecentDiagnostics.entries.collectAsState()
        val refreshMode by refreshModePreference.selection.collectAsState()
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(R.drawable.trackglance_mark),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        stringResource(R.string.title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Text(
                    stringResource(R.string.version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                )
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StatusLine(
                            stringResource(R.string.pebble_watchapp),
                            if (status.watchAppOpen) stringResource(R.string.open)
                            else stringResource(R.string.closed),
                        )
                        StatusLine(
                            stringResource(R.string.pebble_app),
                            status.pebbleAppPackage
                                ?: stringResource(R.string.no_pebble_app_selected),
                        )
                        StatusLine(
                            stringResource(R.string.pebble_watch),
                            if (status.watchConnected) stringResource(R.string.connected)
                            else stringResource(R.string.not_connected),
                        )
                        StatusLine(
                            stringResource(R.string.watchapp_auto_start),
                            status.watchAppLaunchResult?.localized()
                                ?: stringResource(R.string.not_requested),
                        )
                        StatusLine(
                            stringResource(R.string.watchapp_version),
                            status.watchVersion?.let { version ->
                                if (version == BuildConfig.VERSION_NAME) version
                                else
                                    stringResource(
                                        R.string.version_expected,
                                        version,
                                        BuildConfig.VERSION_NAME,
                                    )
                            } ?: stringResource(R.string.not_reported),
                        )
                        StatusLine(
                            stringResource(R.string.locus_map),
                            if (status.locusAvailable) stringResource(R.string.available)
                            else stringResource(R.string.unavailable),
                        )
                        StatusLine(
                            stringResource(R.string.recording),
                            status.recordingState.localized(),
                        )
                        StatusLine(
                            stringResource(R.string.locus_profiles),
                            status.locusProfiles?.let { profiles ->
                                if (profiles.isEmpty()) stringResource(R.string.none_returned)
                                else
                                    stringResource(
                                        R.string.profile_count,
                                        profiles.size,
                                        profiles.joinToString(),
                                    )
                            } ?: stringResource(R.string.not_queried),
                        )
                        StatusLine(
                            stringResource(R.string.last_profile_request),
                            status.lastProfileRequestEpochMillis?.let {
                                DateFormat.getTimeInstance().format(Date(it))
                            } ?: stringResource(R.string.never),
                        )
                        StatusLine(
                            stringResource(R.string.last_update),
                            status.lastUpdateEpochMillis?.let {
                                DateFormat.getTimeInstance().format(Date(it))
                            } ?: stringResource(R.string.never),
                        )
                        StatusLine(
                            stringResource(R.string.last_watch_hr),
                            status.lastWatchHeartRate?.let {
                                stringResource(R.string.heart_rate_value, it)
                            } ?: stringResource(R.string.never),
                        )
                        StatusLine(
                            stringResource(R.string.last_hr_forwarded),
                            status.lastHeartRateForwardedEpochMillis?.let {
                                DateFormat.getTimeInstance().format(Date(it))
                            } ?: stringResource(R.string.never),
                        )
                        StatusLine(
                            stringResource(R.string.current_locus_hr),
                            status.currentLocusHeartRate?.let {
                                stringResource(R.string.heart_rate_value, it)
                            } ?: stringResource(R.string.unavailable),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.recent_diagnostics),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(
                        onClick = RecentDiagnostics::clear,
                        enabled = diagnosticEntries.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.clear))
                    }
                }
                if (diagnosticEntries.isEmpty()) {
                    Text(stringResource(R.string.no_recent_diagnostics))
                } else
                    diagnosticEntries.forEach { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    if (entry.severity == DiagnosticSeverity.WARNING)
                                        stringResource(R.string.warning)
                                    else stringResource(R.string.error),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(entry.failure.localizedSummary())
                                StatusLine(
                                    stringResource(R.string.last_seen),
                                    DateFormat.getTimeInstance()
                                        .format(Date(entry.lastSeenEpochMillis)),
                                )
                                StatusLine(
                                    stringResource(R.string.occurrences),
                                    entry.count.toString(),
                                )
                                entry.failure.technicalDetail?.let {
                                    StatusLine(stringResource(R.string.technical_detail), it)
                                }
                            }
                        }
                    }
                Text(
                    stringResource(R.string.refresh_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                RefreshMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = refreshMode == mode,
                            onClick = {
                                refreshModePreference.select(mode)
                                lifecycleScope.launch(Dispatchers.IO) {
                                    Preferences.setRefreshMode(this@MainActivity, mode)
                                }
                            },
                        )
                        Text(mode.localized())
                    }
                }
                Text(
                    stringResource(R.string.refresh_explanation),
                    style = MaterialTheme.typography.bodySmall,
                )
                val context = LocalContext.current
                TextButton(
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, LEGAL_URL.toUri()))
                    }
                ) {
                    Text(stringResource(R.string.legal_footer))
                }
            }
        }
    }

    private companion object {
        const val TAG = "TrackGlance"
        const val CONNECTION_QUERY_TIMEOUT_MILLIS = 3_000L
        const val LEGAL_URL = "https://christianherget.github.io/trackglance/legal.html"
        // Process-wide ceiling: Activity recreation cannot accumulate abandoned provider threads.
        val WATCH_QUERY_EXECUTOR =
            BoundedAbandonableCallExecutor(
                maxWorkers = 2,
                threadNamePrefix = "pebble-info-query",
            )
    }
}

internal fun isLocusWatchLaunchIntent(action: String?): Boolean =
    action == "locus.api.android.INTENT_ITEM_MAIN_FUNCTION"

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BridgeProtocol.RecordingState.localized() =
    stringResource(
        when (this) {
            BridgeProtocol.RecordingState.STOPPED -> R.string.state_stopped
            BridgeProtocol.RecordingState.RECORDING -> R.string.state_recording
            BridgeProtocol.RecordingState.PAUSED -> R.string.state_paused
            BridgeProtocol.RecordingState.UNAVAILABLE -> R.string.state_unavailable
        }
    )

@Composable
private fun RefreshMode.localized() =
    stringResource(
        when (this) {
            RefreshMode.ADAPTIVE -> R.string.refresh_adaptive
            RefreshMode.FIVE_SECONDS -> R.string.refresh_five
            RefreshMode.TEN_SECONDS -> R.string.refresh_ten
        }
    )

@Composable
private fun WatchAppLaunchResult.localized() =
    stringResource(
        when (this) {
            WatchAppLaunchResult.STARTED -> R.string.launch_started
            WatchAppLaunchResult.NO_CONNECTED_WATCH -> R.string.launch_no_watch
            WatchAppLaunchResult.LOOKUP_FAILED -> R.string.launch_lookup_failed
            WatchAppLaunchResult.LAUNCH_FAILED -> R.string.launch_failed
            WatchAppLaunchResult.TIMED_OUT -> R.string.launch_timed_out
            WatchAppLaunchResult.UNTRUSTED_COMPANION,
            WatchAppLaunchResult.STALE_COMPANION -> R.string.launch_untrusted
        }
    )

@Composable
private fun FailureLines(label: String, failure: BridgeFailure) {
    StatusLine(label, failure.localizedSummary())
    failure.technicalDetail?.let { StatusLine(stringResource(R.string.technical_detail), it) }
}

@Composable
private fun BridgeFailure.localizedSummary() =
    stringResource(
        when (kind) {
            BridgeFailureKind.LOCUS_UNAVAILABLE -> R.string.failure_locus_unavailable
            BridgeFailureKind.LOCUS_PROFILE_QUERY_FAILED -> R.string.failure_profile_query
            BridgeFailureKind.LOCUS_PROFILE_LIST_INVALID -> R.string.failure_profile_invalid
            BridgeFailureKind.LOCUS_RETURNED_NO_PROFILES -> R.string.failure_no_profiles
            BridgeFailureKind.PEBBLE_WATCH_LOOKUP_FAILED -> R.string.failure_watch_lookup
            BridgeFailureKind.WATCHAPP_LAUNCH_FAILED -> R.string.failure_watchapp_launch
            BridgeFailureKind.WATCHAPP_LAUNCH_TIMED_OUT -> R.string.failure_watchapp_timeout
            BridgeFailureKind.PEBBLE_COMPANION_UNTRUSTED -> R.string.failure_untrusted
            BridgeFailureKind.PEBBLE_COMPANION_NOT_INSTALLED -> R.string.failure_not_installed
            BridgeFailureKind.PEBBLE_COMPANION_NOT_SELECTED -> R.string.failure_not_selected
            BridgeFailureKind.WATCHAPP_INCOMPATIBLE -> R.string.failure_incompatible
            BridgeFailureKind.SNAPSHOT_DELIVERY_FAILED -> R.string.failure_snapshot_delivery
            BridgeFailureKind.COMMAND_RESULT_DELIVERY_FAILED -> R.string.failure_command_delivery
            BridgeFailureKind.PROFILE_LIST_DELIVERY_FAILED -> R.string.failure_profile_delivery
            BridgeFailureKind.THIRD_PARTY_FAILURE -> R.string.failure_third_party
        }
    )
