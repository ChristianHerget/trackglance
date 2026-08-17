package app.locuspebble.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.locuspebble.bridge.core.BoundedAbandonableCallExecutor
import app.locuspebble.bridge.core.BridgeRuntime
import app.locuspebble.bridge.core.BridgeState
import app.locuspebble.bridge.core.Preferences
import app.locuspebble.bridge.core.RefreshMode
import app.locuspebble.bridge.core.withDiagnosticsSnapshot
import app.locuspebble.bridge.core.withPebbleConnectionFailure
import app.locuspebble.bridge.core.withPebbleSelection
import app.locuspebble.bridge.locus.LocusGateway
import app.locuspebble.bridge.pebble.TRUSTED_CORE_APP_PACKAGE
import app.locuspebble.bridge.pebble.CoreAppSignerPolicy
import app.locuspebble.bridge.pebble.CoreAppTrustKind
import app.locuspebble.bridge.pebble.CoreAppTrustStatus
import app.locuspebble.bridge.pebble.TrustedPebbleCompanionProvider
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val coreAppTrust = MutableStateFlow<CoreAppTrustStatus?>(null)
    private lateinit var refreshModePreference: RefreshModePreferenceState
    private val diagnosticsMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContext = applicationContext
        refreshModePreference = RefreshModePreferenceState(
            readPreference = { Preferences.refreshMode(appContext) },
        )
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
    }

    private suspend fun refreshDiagnostics() = diagnosticsMutex.withLock {
        var pebbleError: String? = null
        try {
            // Construction loads the durable command journal and publishes its health.
            withContext(Dispatchers.IO) { BridgeRuntime.get(applicationContext) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            pebbleError = error.message ?: error.javaClass.simpleName
        }
        val companionPin = TrustedPebbleCompanionProvider.get(this@MainActivity)
        val trusted = try {
            companionPin.ensureTrustedBounded()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            pebbleError = error.message ?: error.javaClass.simpleName
            false
        }
        val trust = TrustedPebbleCompanionProvider.inspect(this@MainActivity)
        coreAppTrust.value = trust
        val identityTrusted = trusted && trust.trusted
        val selected = TRUSTED_CORE_APP_PACKAGE.takeIf { identityTrusted }
        if (!identityTrusted && pebbleError == null) {
            pebbleError = when (trust.kind) {
                CoreAppTrustKind.APPROVAL_REQUIRED -> "CoreApp signer approval is required"
                CoreAppTrustKind.NOT_INSTALLED -> "CoreApp is not installed"
                CoreAppTrustKind.INVALID_PACKAGE -> trust.detail ?: "CoreApp signing identity is invalid"
                else -> "Trusted CoreApp could not be selected"
            }
        }
        BridgeState.update { it.withPebbleSelection(selected) }
        if (!identityTrusted) BridgeRuntime.resetForCompanionTrustLoss()
        val snapshot = withContext(Dispatchers.IO) { LocusGateway(this@MainActivity).readSnapshot() }
        BridgeState.update {
            it.withDiagnosticsSnapshot(
                recordingState = snapshot.state,
                sampledAtMillis = System.currentTimeMillis(),
                currentHeartRate = snapshot.currentHeartRate,
                error = pebbleError ?: if (
                    snapshot.state == BridgeProtocol.RecordingState.UNAVAILABLE
                ) "Locus is unavailable" else null,
            )
        }
        if (selected != null) {
            try {
                val infoContext = applicationContext
                val outcome = generationGuardedForeignQuery(
                    executor = WATCH_QUERY_EXECUTOR,
                    timeoutMillis = CONNECTION_QUERY_TIMEOUT_MILLIS,
                    admit = {
                        TrustedPebbleCompanionProvider.captureTrustedOutboundAdmission(
                            this@MainActivity,
                        )
                    },
                    query = {
                        // ContentResolver.query is synchronous beneath this Flow. If a foreign
                        // provider hangs, the bounded worker may be abandoned without retaining
                        // the outbound revocation lease or the Activity's main coroutine.
                        runBlocking {
                            withTimeout(CONNECTION_QUERY_TIMEOUT_MILLIS) {
                                DefaultPebbleInfoRetriever(infoContext).getConnectedWatches().first()
                            }
                        }
                    },
                    publishIfCurrent = { admission, watches ->
                        TrustedPebbleCompanionProvider.withOutboundAdmission(
                            this@MainActivity,
                            admission,
                        ) {
                            BridgeState.update { it.copy(watchConnected = watches.isNotEmpty()) }
                        }
                    },
                )
                when (outcome) {
                    GuardedForeignQueryOutcome.PUBLISHED -> Unit
                    GuardedForeignQueryOutcome.STALE -> return@withLock
                    GuardedForeignQueryOutcome.UNTRUSTED ->
                        error("CoreApp trust changed while querying connected Pebble watches")
                    GuardedForeignQueryOutcome.FAILED ->
                        error("Timed out while querying connected Pebble watches")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                val stillTrusted = try {
                    companionPin.guard.isTrusted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!stillTrusted) {
                    coreAppTrust.value = TrustedPebbleCompanionProvider.inspect(this@MainActivity)
                    BridgeRuntime.resetForCompanionTrustLoss()
                }
                BridgeState.update { status ->
                    val current = if (stillTrusted) status else status.withPebbleSelection(null)
                    current.withPebbleConnectionFailure(message)
                }
            }
        }
    }

    private fun approveCoreAppSigner(digest: String) {
        lifecycleScope.launch {
            val saved = TrustedPebbleCompanionProvider.approveCurrentSigner(this@MainActivity, digest)
            if (!saved) {
                BridgeState.update { it.copy(lastError = "CoreApp signer approval could not be saved") }
                return@launch
            }
            refreshDiagnostics()
        }
    }

    private fun clearCoreAppSignerApproval() {
        lifecycleScope.launch {
            val cleared = TrustedPebbleCompanionProvider.clearApprovedSigner(this@MainActivity)
            if (!cleared) {
                BridgeState.update { it.copy(lastError = "CoreApp signer approval could not be cleared") }
                return@launch
            }
            refreshDiagnostics()
        }
    }

    @Composable
    private fun DiagnosticsScreen() {
        val status by BridgeState.status.collectAsState()
        val trust by coreAppTrust.collectAsState()
        val refreshMode by refreshModePreference.selection.collectAsState()
        var signerAwaitingConfirmation by remember { mutableStateOf<String?>(null) }
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Locus Pebble Bridge", style = MaterialTheme.typography.headlineSmall)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusLine("Pebble watchapp", if (status.watchAppOpen) "Open" else "Closed")
                        StatusLine("Pebble/Core app", status.pebbleAppPackage ?: "Not selected")
                        StatusLine("Pebble watch", if (status.watchConnected) "Connected" else "Not connected")
                        StatusLine(
                            "Watchapp version",
                            status.watchVersion?.let { version ->
                                if (version == BuildConfig.VERSION_NAME) version
                                else "$version (expected ${BuildConfig.VERSION_NAME})"
                            } ?: "Not reported",
                        )
                        StatusLine("Locus Map", if (status.locusAvailable) "Available" else "Unavailable")
                        StatusLine("Recording", status.recordingState.label())
                        StatusLine(
                            "Locus profiles",
                            status.locusProfiles?.let { profiles ->
                                if (profiles.isEmpty()) "None returned" else "${profiles.size}: ${profiles.joinToString()}"
                            } ?: "Not queried",
                        )
                        StatusLine("Last profile request", status.lastProfileRequestEpochMillis?.let {
                            DateFormat.getTimeInstance().format(Date(it))
                        } ?: "Never")
                        StatusLine("Last update", status.lastUpdateEpochMillis?.let {
                            DateFormat.getTimeInstance().format(Date(it))
                        } ?: "Never")
                        StatusLine("Last watch HR", status.lastWatchHeartRate?.let { "$it bpm" } ?: "Never")
                        StatusLine("Last HR forwarded", status.lastHeartRateForwardedEpochMillis?.let {
                            DateFormat.getTimeInstance().format(Date(it))
                        } ?: "Never")
                        StatusLine("Current Locus HR", status.currentLocusHeartRate?.let { "$it bpm" } ?: "Unavailable")
                        status.commandJournalError?.let {
                            Text("Command safety: $it", color = MaterialTheme.colorScheme.error)
                        }
                        status.diagnosticsError?.let { StatusLine("Diagnostics", it) }
                        status.lastError?.let { StatusLine("Last runtime error", it) }
                    }
                }
                CoreAppTrustCard(
                    trust = trust,
                    onApprove = { signerAwaitingConfirmation = it },
                    onClearApproval = ::clearCoreAppSignerApproval,
                )
                Text("Refresh while watchapp is open", style = MaterialTheme.typography.titleMedium)
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
                        Text(mode.label())
                    }
                }
                Text(
                    "Adaptive refreshes every 2 seconds after opening or a command, then every 10 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        signerAwaitingConfirmation?.let { digest ->
            AlertDialog(
                onDismissRequest = { signerAwaitingConfirmation = null },
                title = { Text("Trust this CoreApp build?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Package: $TRUSTED_CORE_APP_PACKAGE")
                        Text("SHA-256 signer:")
                        Text(
                            CoreAppSignerPolicy.displayDigest(digest),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text("Approve only if you built this CoreApp or independently verified this exact digest.")
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        signerAwaitingConfirmation = null
                        approveCoreAppSigner(digest)
                    }) { Text("Trust exact signer") }
                },
                dismissButton = {
                    TextButton(onClick = { signerAwaitingConfirmation = null }) { Text("Cancel") }
                },
            )
        }
    }

    private companion object {
        const val CONNECTION_QUERY_TIMEOUT_MILLIS = 3_000L
        // Process-wide ceiling: Activity recreation cannot accumulate abandoned provider threads.
        val WATCH_QUERY_EXECUTOR = BoundedAbandonableCallExecutor(
            maxWorkers = 2,
            threadNamePrefix = "pebble-info-query",
        )
    }
}

@Composable
private fun CoreAppTrustCard(
    trust: CoreAppTrustStatus?,
    onApprove: (String) -> Unit,
    onClearApproval: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CoreApp identity", style = MaterialTheme.typography.titleMedium)
            StatusLine("Package", TRUSTED_CORE_APP_PACKAGE)
            StatusLine(
                "Trust",
                when (trust?.kind) {
                    CoreAppTrustKind.USER_APPROVED -> "Explicitly approved"
                    CoreAppTrustKind.APPROVAL_REQUIRED -> "Approval required"
                    CoreAppTrustKind.NOT_INSTALLED -> "Not installed"
                    CoreAppTrustKind.INVALID_PACKAGE -> "Invalid signing identity"
                    null -> "Checking"
                },
            )
            trust?.currentSignerDigests?.forEach { digest ->
                Text("Current signer SHA-256", style = MaterialTheme.typography.labelMedium)
                Text(CoreAppSignerPolicy.displayDigest(digest), style = MaterialTheme.typography.bodySmall)
            }
            trust?.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            trust?.enrollmentCandidate?.let { digest ->
                Button(onClick = { onApprove(digest) }) { Text("Trust this exact signer") }
            }
            if (trust?.approvedSignerDigest != null) {
                TextButton(onClick = onClearApproval) { Text("Forget approved signer") }
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun BridgeProtocol.RecordingState.label() = name.lowercase().replaceFirstChar { it.uppercase() }
private fun RefreshMode.label() = when (this) {
    RefreshMode.ADAPTIVE -> "Adaptive"
    RefreshMode.FIVE_SECONDS -> "5 seconds"
    RefreshMode.TEN_SECONDS -> "10 seconds"
}
