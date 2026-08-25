package io.github.christianherget.trackglance.bridge

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.christianherget.trackglance.bridge.core.BoundedAbandonableCallExecutor
import io.github.christianherget.trackglance.bridge.core.BridgeFailure
import io.github.christianherget.trackglance.bridge.core.BridgeFailureKind
import io.github.christianherget.trackglance.bridge.core.BridgeRuntime
import io.github.christianherget.trackglance.bridge.core.BridgeState
import io.github.christianherget.trackglance.bridge.core.Preferences
import io.github.christianherget.trackglance.bridge.core.RecentDiagnostics
import io.github.christianherget.trackglance.bridge.core.withDiagnosticsSnapshot
import io.github.christianherget.trackglance.bridge.core.withPebbleConnectionFailure
import io.github.christianherget.trackglance.bridge.core.withPebbleSelection
import io.github.christianherget.trackglance.bridge.locus.LocusGateway
import io.github.christianherget.trackglance.bridge.pebble.CoreAppConnectionKind
import io.github.christianherget.trackglance.bridge.pebble.TRUSTED_CORE_APP_PACKAGE
import io.github.christianherget.trackglance.bridge.pebble.TrustedPebbleCompanionProvider
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
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
        enableEdgeToEdge()
        val appContext = applicationContext
        refreshModePreference =
            RefreshModePreferenceState(readPreference = { Preferences.refreshMode(appContext) })
        watchAppLauncher = WatchAppLauncher.create(appContext)
        setContent { TrackGlanceTheme { DiagnosticsScreen() } }
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
        BridgeScreen(
            status = status,
            diagnosticEntries = diagnosticEntries,
            refreshMode = refreshMode,
            versionName = BuildConfig.VERSION_NAME,
            onRefreshModeSelected = { mode ->
                refreshModePreference.select(mode)
                lifecycleScope.launch(Dispatchers.IO) {
                    Preferences.setRefreshMode(this@MainActivity, mode)
                }
            },
            onClearDiagnostics = RecentDiagnostics::clear,
            onOpenLegal = { startActivity(Intent(Intent.ACTION_VIEW, LEGAL_URL.toUri())) },
        )
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
