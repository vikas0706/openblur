package io.github.vikas0706.openblur.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.designsystem.ConfirmDialog
import io.github.vikas0706.openblur.designsystem.MosaicProgress
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.SecondaryButton
import io.github.vikas0706.openblur.designsystem.formatTimeUs
import io.github.vikas0706.openblur.designsystem.tabularFigures

@Composable
internal fun ExportingScreen(
    running: ExportState.Running,
    remainingMs: Long?,
    busyNotice: Boolean,
    onDismissBusyNotice: () -> Unit,
    onCancel: () -> Unit,
    onLeaveApp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    var confirmCancel by remember { mutableStateOf(false) }
    // Back must not end the activity: the screens behind this one are needed when the export finishes.
    BackHandler(onBack = onLeaveApp)
    KeepScreenOn()

    ScreenFrame(title = stringResource(R.string.export_title), onBack = null, modifier = modifier) {
        if (busyNotice) BusyNotice(onDismissBusyNotice)
        Column(Modifier.padding(top = 8.dp)) {
            Text(
                text = Labels.exportPhase(resources, running.phase),
                modifier = Modifier.padding(bottom = 12.dp),
                color = OpenBlurTheme.colors.textMuted,
                style = MaterialTheme.typography.titleMedium,
            )
            MosaicProgress(
                // Writing the file has no measurable progress of its own.
                progress = if (running.phase == ExportPhase.ENCODING) running.progress else null,
                contentDescription = stringResource(R.string.export_progress_description),
            )
            Text(
                text = stringResource(R.string.format_percent, Formats.percent(running.progress)),
                modifier = Modifier.padding(top = 16.dp).testTag(TAG_EXPORT_PERCENT),
                color = OpenBlurTheme.colors.text,
                style = MaterialTheme.typography.headlineMedium.tabularFigures(),
            )
            Text(
                text = stringResource(R.string.export_elapsed, formatTimeUs(running.elapsedMs * 1000L)),
                modifier = Modifier.padding(top = 4.dp),
                color = OpenBlurTheme.colors.textMuted,
                style = MaterialTheme.typography.bodyLarge.tabularFigures(),
            )
            if (remainingMs != null && running.phase == ExportPhase.ENCODING) {
                Text(
                    text = Labels.remaining(resources, remainingMs),
                    color = OpenBlurTheme.colors.textMuted,
                    style = MaterialTheme.typography.bodyLarge.tabularFigures(),
                )
            }
        }
        BodyText(stringResource(R.string.export_background), muted = true)
        SecondaryButton(
            text = stringResource(R.string.export_cancel),
            onClick = { confirmCancel = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (confirmCancel) {
        ConfirmDialog(
            title = stringResource(R.string.export_cancel_title),
            text = stringResource(R.string.export_cancel_text),
            confirmLabel = stringResource(R.string.export_cancel_confirm),
            dismissLabel = stringResource(R.string.export_cancel_dismiss),
            onConfirm = {
                confirmCancel = false
                onCancel()
            },
            onDismiss = { confirmCancel = false },
            destructive = true,
        )
    }
}

internal const val TAG_EXPORT_PERCENT = "export-percent"
