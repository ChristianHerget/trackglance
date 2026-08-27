@file:Suppress(
    "CyclomaticComplexMethod",
    "DestructuringDeclarationWithTooManyEntries",
    "FunctionNaming",
    "LongMethod",
    "TooManyFunctions",
)

package io.github.christianherget.trackglance.bridge

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.christianherget.trackglance.bridge.core.BridgeFailure
import io.github.christianherget.trackglance.bridge.core.BridgeFailureKind
import io.github.christianherget.trackglance.bridge.core.BridgeStatus
import io.github.christianherget.trackglance.bridge.core.DiagnosticEntry
import io.github.christianherget.trackglance.bridge.core.DiagnosticSeverity
import io.github.christianherget.trackglance.bridge.core.RefreshMode
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.StateFlow

private enum class StatusTone {
    POSITIVE,
    NEUTRAL,
    WARNING,
    ERROR,
}

@Composable
internal fun LifecycleAwareBridgeScreen(
    statusFlow: StateFlow<BridgeStatus>,
    diagnosticEntriesFlow: StateFlow<List<DiagnosticEntry>>,
    refreshModeFlow: StateFlow<RefreshMode>,
    versionName: String,
    onRefreshModeSelected: (RefreshMode) -> Unit,
    onClearDiagnostics: () -> Unit,
    onOpenLegal: () -> Unit,
) {
    val status by statusFlow.collectAsStateWithLifecycle()
    val diagnosticEntries by diagnosticEntriesFlow.collectAsStateWithLifecycle()
    val refreshMode by refreshModeFlow.collectAsStateWithLifecycle()
    BridgeScreen(
        status = status,
        diagnosticEntries = diagnosticEntries,
        refreshMode = refreshMode,
        versionName = versionName,
        onRefreshModeSelected = onRefreshModeSelected,
        onClearDiagnostics = onClearDiagnostics,
        onOpenLegal = onOpenLegal,
    )
}

@Composable
internal fun BridgeScreen(
    status: BridgeStatus,
    diagnosticEntries: List<DiagnosticEntry>,
    refreshMode: RefreshMode,
    versionName: String,
    onRefreshModeSelected: (RefreshMode) -> Unit,
    onClearDiagnostics: () -> Unit,
    onOpenLegal: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.align(Alignment.TopCenter)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BrandHeader(versionName)
                SectionHeading(stringResource(R.string.status))
                StatusCard(stringResource(R.string.section_pebble)) {
                    StatusRow(
                        stringResource(R.string.pebble_app),
                        status.pebbleAppPackage ?: stringResource(R.string.no_pebble_app_selected),
                        if (status.pebbleAppPackage == null) StatusTone.WARNING
                        else StatusTone.POSITIVE,
                    )
                    StatusRow(
                        stringResource(R.string.pebble_watch),
                        if (status.watchConnected) stringResource(R.string.connected)
                        else stringResource(R.string.not_connected),
                        if (status.watchConnected) StatusTone.POSITIVE else StatusTone.WARNING,
                    )
                    StatusRow(
                        stringResource(R.string.pebble_watchapp),
                        if (status.watchAppOpen) stringResource(R.string.open)
                        else stringResource(R.string.closed),
                        if (status.watchAppOpen) StatusTone.POSITIVE else StatusTone.NEUTRAL,
                    )
                    StatusRow(
                        stringResource(R.string.watchapp_auto_start),
                        status.watchAppLaunchResult?.localized()
                            ?: stringResource(R.string.not_requested),
                        status.watchAppLaunchResult.tone(),
                    )
                    val reportedVersion = status.watchVersion
                    StatusRow(
                        stringResource(R.string.watchapp_version),
                        reportedVersion?.let { version ->
                            if (version == versionName) version
                            else stringResource(R.string.version_expected, version, versionName)
                        } ?: stringResource(R.string.not_reported),
                        when {
                            reportedVersion == null -> StatusTone.NEUTRAL
                            reportedVersion == versionName -> StatusTone.POSITIVE
                            else -> StatusTone.ERROR
                        },
                    )
                }
                StatusCard(stringResource(R.string.locus_map)) {
                    StatusRow(
                        stringResource(R.string.availability),
                        if (status.locusAvailable) stringResource(R.string.available)
                        else stringResource(R.string.unavailable),
                        if (status.locusAvailable) StatusTone.POSITIVE else StatusTone.WARNING,
                    )
                    StatusRow(
                        stringResource(R.string.recording),
                        status.recordingState.localized(),
                        status.recordingState.tone(),
                    )
                    DetailRow(
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
                    DetailRow(
                        stringResource(R.string.last_profile_request),
                        status.lastProfileRequestEpochMillis.localizedTime(),
                    )
                    DetailRow(
                        stringResource(R.string.last_update),
                        status.lastUpdateEpochMillis.localizedTime(),
                    )
                }
                StatusCard(stringResource(R.string.section_heart_rate)) {
                    DetailRow(
                        stringResource(R.string.current_locus_hr),
                        status.currentLocusHeartRate.localizedHeartRate(
                            stringResource(R.string.unavailable)
                        ),
                    )
                    DetailRow(
                        stringResource(R.string.last_watch_hr),
                        status.lastWatchHeartRate.localizedHeartRate(
                            stringResource(R.string.never)
                        ),
                    )
                    DetailRow(
                        stringResource(R.string.last_hr_forwarded),
                        status.lastHeartRateForwardedEpochMillis.localizedTime(),
                    )
                }
                SectionHeading(stringResource(R.string.section_settings))
                SettingsCard(refreshMode, onRefreshModeSelected)
                DiagnosticsSection(diagnosticEntries, onClearDiagnostics)
                TextButton(
                    onClick = onOpenLegal,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.legal_footer), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun BrandHeader(versionName: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.trackglance_mark),
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.version, versionName),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun StatusCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            Text(
                title,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, tone: StatusTone) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusBadge(value, tone)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
}

@Composable
private fun StatusBadge(value: String, tone: StatusTone) {
    val colors = LocalTrackGlanceStatusColors.current
    val (accent, foreground, container, glyph) =
        when (tone) {
            StatusTone.POSITIVE ->
                StatusVisual(colors.positive, colors.onPositive, colors.positiveContainer, "✓")
            StatusTone.WARNING ->
                StatusVisual(colors.warning, colors.onWarning, colors.warningContainer, "!")
            StatusTone.ERROR ->
                StatusVisual(colors.error, colors.onError, colors.errorContainer, "×")
            StatusTone.NEUTRAL ->
                StatusVisual(colors.neutral, colors.onNeutral, colors.neutralContainer, "–")
        }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(container)
            .border(1.dp, accent, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).border(1.dp, accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = accent, style = MaterialTheme.typography.labelMedium)
        }
        Text(value, color = foreground, style = MaterialTheme.typography.bodyMedium)
    }
}

private data class StatusVisual(
    val accent: Color,
    val foreground: Color,
    val container: Color,
    val glyph: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(
    refreshMode: RefreshMode,
    onRefreshModeSelected: (RefreshMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.refresh_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = refreshMode.localized(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.refresh_interval)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier =
                        Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("refresh-mode"),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    RefreshMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.localized()) },
                            onClick = {
                                expanded = false
                                onRefreshModeSelected(mode)
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.refresh_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticsSection(
    diagnosticEntries: List<DiagnosticEntry>,
    onClearDiagnostics: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.recent_diagnostics),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onClearDiagnostics,
            enabled = diagnosticEntries.isNotEmpty(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("clear-diagnostics"),
        ) {
            Text(stringResource(R.string.clear))
        }
    }
    if (diagnosticEntries.isEmpty()) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = CardDefaults.outlinedCardBorder(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusGlyph("✓", StatusTone.POSITIVE)
                Text(stringResource(R.string.no_recent_diagnostics))
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            diagnosticEntries.forEach { entry -> DiagnosticCard(entry) }
        }
    }
}

@Composable
private fun DiagnosticCard(entry: DiagnosticEntry) {
    val tone =
        if (entry.severity == DiagnosticSeverity.WARNING) StatusTone.WARNING else StatusTone.ERROR
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusGlyph(if (tone == StatusTone.WARNING) "!" else "×", tone)
                Text(
                    if (entry.severity == DiagnosticSeverity.WARNING)
                        stringResource(R.string.warning)
                    else stringResource(R.string.error),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(entry.failure.localizedSummary(), style = MaterialTheme.typography.bodyLarge)
            DiagnosticDetail(
                stringResource(R.string.last_seen),
                DateFormat.getTimeInstance().format(Date(entry.lastSeenEpochMillis)),
            )
            DiagnosticDetail(stringResource(R.string.occurrences), entry.count.toString())
            entry.failure.technicalDetail?.let {
                DiagnosticDetail(stringResource(R.string.technical_detail), it)
            }
        }
    }
}

@Composable
private fun StatusGlyph(glyph: String, tone: StatusTone) {
    val colors = LocalTrackGlanceStatusColors.current
    val color =
        when (tone) {
            StatusTone.POSITIVE -> colors.positive
            StatusTone.WARNING -> colors.warning
            StatusTone.ERROR -> colors.error
            StatusTone.NEUTRAL -> colors.neutral
        }
    Box(
        Modifier.size(28.dp).clip(CircleShape).border(1.dp, color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DiagnosticDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Long?.localizedTime() =
    this?.let { DateFormat.getTimeInstance().format(Date(it)) } ?: stringResource(R.string.never)

@Composable
private fun Int?.localizedHeartRate(emptyValue: String) =
    this?.let { stringResource(R.string.heart_rate_value, it) } ?: emptyValue

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

private fun BridgeProtocol.RecordingState.tone() =
    when (this) {
        BridgeProtocol.RecordingState.RECORDING -> StatusTone.POSITIVE
        BridgeProtocol.RecordingState.STOPPED -> StatusTone.NEUTRAL
        BridgeProtocol.RecordingState.PAUSED -> StatusTone.WARNING
        BridgeProtocol.RecordingState.UNAVAILABLE -> StatusTone.ERROR
    }

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

private fun WatchAppLaunchResult?.tone() =
    when (this) {
        WatchAppLaunchResult.STARTED -> StatusTone.POSITIVE
        null -> StatusTone.NEUTRAL
        WatchAppLaunchResult.NO_CONNECTED_WATCH,
        WatchAppLaunchResult.TIMED_OUT -> StatusTone.WARNING
        WatchAppLaunchResult.LOOKUP_FAILED,
        WatchAppLaunchResult.LAUNCH_FAILED,
        WatchAppLaunchResult.UNTRUSTED_COMPANION,
        WatchAppLaunchResult.STALE_COMPANION -> StatusTone.ERROR
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
