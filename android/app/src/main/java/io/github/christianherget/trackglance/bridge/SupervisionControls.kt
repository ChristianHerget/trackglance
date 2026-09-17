@file:Suppress("FunctionNaming", "LongMethod")

package io.github.christianherget.trackglance.bridge

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.christianherget.trackglance.bridge.core.SupervisionMode
import io.github.christianherget.trackglance.bridge.core.SupervisionPhase
import io.github.christianherget.trackglance.bridge.core.SupervisionSettings
import io.github.christianherget.trackglance.bridge.core.SupervisionSource
import io.github.christianherget.trackglance.bridge.core.SupervisionStatus
import kotlin.math.roundToInt

@Composable
internal fun SupervisionControls(
    status: SupervisionStatus,
    select: (SupervisionSettings) -> Unit,
    openSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.supervision_title))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SupervisionMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = status.settings.mode == mode,
                    onClick = { select(status.settings.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index, SupervisionMode.entries.size),
                    modifier = Modifier.testTag("supervision-${mode.name}"),
                ) {
                    Text(
                        stringResource(
                            when (mode) {
                                SupervisionMode.OFF -> R.string.supervision_off
                                SupervisionMode.NOTIFY -> R.string.supervision_notify
                                SupervisionMode.AUTO_START -> R.string.supervision_auto
                            }
                        )
                    )
                }
            }
        }
        val delayLabel =
            pluralStringResource(
                R.plurals.supervision_delay,
                status.settings.delaySeconds,
                status.settings.delaySeconds,
            )
        Text(delayLabel)
        Slider(
            value = status.settings.delaySeconds.toFloat(),
            onValueChange = {
                select(status.settings.copy(delaySeconds = (it / 15).roundToInt() * 15))
            },
            valueRange = 15f..90f,
            steps = 4,
            enabled = status.settings.mode != SupervisionMode.OFF,
            modifier =
                Modifier.testTag("supervision-delay").semantics { contentDescription = delayLabel },
        )
        Text(
            stringResource(
                when (status.phase) {
                    SupervisionPhase.OFF -> R.string.supervision_off
                    SupervisionPhase.WAITING -> R.string.supervision_waiting
                    SupervisionPhase.PAUSED -> R.string.supervision_paused
                    SupervisionPhase.CLOSED -> R.string.supervision_closed
                    SupervisionPhase.SUSPENDED -> R.string.supervision_unavailable
                    SupervisionPhase.ACTIVE ->
                        when {
                            status.sources.size == 2 -> R.string.supervision_active_both
                            SupervisionSource.HEART_RATE in status.sources ->
                                R.string.supervision_active_hr
                            else -> R.string.supervision_active_steps
                        }
                }
            )
        )
        Text(stringResource(R.string.supervision_explanation))
        if (status.notificationsBlocked) {
            Text(stringResource(R.string.supervision_blocked))
            TextButton(onClick = openSettings) {
                Text(stringResource(R.string.supervision_notification_settings))
            }
        }
    }
}
