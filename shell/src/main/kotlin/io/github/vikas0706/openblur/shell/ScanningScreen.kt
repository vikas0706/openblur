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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.designsystem.ConfirmDialog
import io.github.vikas0706.openblur.designsystem.InfoRow
import io.github.vikas0706.openblur.designsystem.MosaicProgress
import io.github.vikas0706.openblur.designsystem.Notice
import io.github.vikas0706.openblur.designsystem.NoticeTone
import io.github.vikas0706.openblur.designsystem.OpenBlurIcons
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.PrimaryButton
import io.github.vikas0706.openblur.designsystem.SecondaryButton
import io.github.vikas0706.openblur.designsystem.SectionHeading
import io.github.vikas0706.openblur.designsystem.tabularFigures

@Composable
internal fun ScanningScreen(
    scan: ScanUi,
    busyNotice: Boolean,
    onDismissBusyNotice: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onChooseAnother: () -> Unit = onCancel,
    modifier: Modifier = Modifier,
) {
    val failed = scan.stage as? ScanStage.Failed
    var confirmCancel by remember { mutableStateOf(false) }
    // A failed scan has nothing left to lose, so leaving it needs no confirmation.
    val leave: () -> Unit = { if (failed == null) confirmCancel = true else onCancel() }
    BackHandler(onBack = leave)

    ScreenFrame(
        title = stringResource(
            when {
                failed != null -> R.string.scan_title_failed
                scan.thorough -> R.string.scan_title_thorough
                else -> R.string.scan_title
            },
        ),
        onBack = leave,
        modifier = modifier,
    ) {
        if (busyNotice) BusyNotice(onDismissBusyNotice)
        if (failed != null) {
            ScanFailed(failed.failure, scan.isRescan, onRetry, if (scan.isRescan) onCancel else onChooseAnother)
        } else {
            KeepScreenOn()
            ScanProgress(scan.stage)
        }
        if (scan.displayName != null || scan.info != null) VideoFacts(scan.displayName, scan.info)
        if (failed == null) {
            BodyText(stringResource(R.string.scan_keep_open), muted = true)
            SecondaryButton(
                text = stringResource(R.string.scan_cancel),
                onClick = { confirmCancel = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmCancel && failed == null) {
        ConfirmDialog(
            title = stringResource(R.string.scan_cancel_title),
            text = stringResource(R.string.scan_cancel_text),
            confirmLabel = stringResource(R.string.scan_cancel_confirm),
            dismissLabel = stringResource(R.string.scan_cancel_dismiss),
            onConfirm = {
                confirmCancel = false
                onCancel()
            },
            onDismiss = { confirmCancel = false },
            destructive = true,
        )
    }
}

@Composable
internal fun BusyNotice(onDismiss: () -> Unit) {
    Notice(
        text = stringResource(R.string.home_notice_busy),
        tone = NoticeTone.Warning,
        actionLabel = stringResource(R.string.action_dismiss),
        onAction = onDismiss,
    )
}

@Composable
private fun ScanProgress(stage: ScanStage) {
    val detecting = stage as? ScanStage.Detecting
    Column(Modifier.padding(top = 8.dp)) {
        MosaicProgress(
            progress = detecting?.fraction,
            contentDescription = stringResource(R.string.scan_progress_description),
        )
        Text(
            text = when (stage) {
                is ScanStage.Detecting -> stringResource(R.string.format_percent, Formats.percent(stage.fraction))
                ScanStage.Saving -> stringResource(R.string.scan_stage_saving)
                else -> stringResource(R.string.scan_stage_reading)
            },
            modifier = Modifier.padding(top = 16.dp).testTag(TAG_SCAN_STATUS),
            color = OpenBlurTheme.colors.text,
            style = MaterialTheme.typography.headlineMedium.tabularFigures(),
        )
        if (detecting != null) {
            Text(
                text = pluralStringResource(R.plurals.scan_frames, detecting.framesSeen, detecting.framesSeen),
                modifier = Modifier.padding(top = 4.dp),
                color = OpenBlurTheme.colors.textMuted,
                style = MaterialTheme.typography.bodyLarge.tabularFigures(),
            )
            Text(
                text = pluralStringResource(R.plurals.scan_faces, detecting.facesSoFar, detecting.facesSoFar),
                color = OpenBlurTheme.colors.textMuted,
                style = MaterialTheme.typography.bodyLarge.tabularFigures(),
            )
        }
    }
}

@Composable
private fun VideoFacts(displayName: String?, info: SourceVideoInfo?) {
    val resources = LocalResources.current
    Column {
        SectionHeading(stringResource(R.string.scan_video_heading))
        if (displayName != null) InfoRow(stringResource(R.string.fact_name), displayName)
        if (info != null) {
            InfoRow(stringResource(R.string.fact_resolution), Labels.resolution(resources, info))
            InfoRow(stringResource(R.string.fact_frame_rate), Labels.frameRate(resources, info.frameRate))
            InfoRow(stringResource(R.string.fact_duration), Labels.duration(info.durationUs))
            InfoRow(stringResource(R.string.fact_codec), Labels.videoCodec(resources, info))
        }
    }
}

@Composable
private fun ScanFailed(failure: ScanFailure, isRescan: Boolean, onRetry: () -> Unit, onLeave: () -> Unit) {
    val resources = LocalResources.current
    Notice(
        title = Labels.scanFailureTitle(resources, failure),
        text = Labels.scanFailureText(resources, failure),
        tone = NoticeTone.Error,
    )
    PrimaryButton(
        text = stringResource(R.string.scan_retry),
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth(),
        icon = OpenBlurIcons.Rescan,
    )
    SecondaryButton(
        text = stringResource(if (isRescan) R.string.scan_back_to_editor else R.string.scan_choose_another),
        onClick = onLeave,
        modifier = Modifier.fillMaxWidth(),
    )
}

internal const val TAG_SCAN_STATUS = "scan-status"
