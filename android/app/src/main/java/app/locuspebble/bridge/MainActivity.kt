package app.locuspebble.bridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.locuspebble.bridge.core.BridgeState
import app.locuspebble.bridge.core.Preferences
import app.locuspebble.bridge.core.RefreshMode
import app.locuspebble.bridge.locus.LocusGateway
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DiagnosticsScreen() } }
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        lifecycleScope.launch {
            val selected = DefaultPebbleAndroidAppPicker.getInstance(this@MainActivity)
                .getCurrentlySelectedApp()
            BridgeState.update { it.copy(pebbleAppPackage = selected) }
            val snapshot = withContext(Dispatchers.IO) { LocusGateway(this@MainActivity).readSnapshot() }
            BridgeState.update {
                it.copy(
                    locusAvailable = snapshot.state != BridgeProtocol.RecordingState.UNAVAILABLE,
                    recordingState = snapshot.state,
                    lastUpdateEpochMillis = System.currentTimeMillis(),
                )
            }
            if (selected != null) {
                runCatching {
                    DefaultPebbleInfoRetriever(this@MainActivity).getConnectedWatches()
                        .flowOn(Dispatchers.IO)
                        .collect { watches -> BridgeState.update { it.copy(watchConnected = watches.isNotEmpty()) } }
                }.onFailure { error -> BridgeState.update { it.copy(lastError = error.message) } }
            }
        }
    }

    @Composable
    private fun DiagnosticsScreen() {
        val status by BridgeState.status.collectAsState()
        var refreshMode by remember { mutableStateOf(Preferences.refreshMode(this)) }
        Scaffold { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp),
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
                        status.lastError?.let { StatusLine("Last error", it) }
                    }
                }
                Text("Refresh while watchapp is open", style = MaterialTheme.typography.titleMedium)
                RefreshMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = refreshMode == mode,
                            onClick = {
                                refreshMode = mode
                                Preferences.setRefreshMode(this@MainActivity, mode)
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
