package io.github.vikas0706.openblur.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.api.Project
import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.designsystem.ConfirmDialog
import io.github.vikas0706.openblur.designsystem.IconAction
import io.github.vikas0706.openblur.designsystem.InfoRow
import io.github.vikas0706.openblur.designsystem.MosaicProgress
import io.github.vikas0706.openblur.designsystem.Notice
import io.github.vikas0706.openblur.designsystem.NoticeTone
import io.github.vikas0706.openblur.designsystem.OpenBlurDimens
import io.github.vikas0706.openblur.designsystem.OpenBlurIcons
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.PrimaryButton
import io.github.vikas0706.openblur.designsystem.SectionHeading
import io.github.vikas0706.openblur.designsystem.SegmentedChoice
import io.github.vikas0706.openblur.designsystem.SwitchRow

private val SheetShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)

/** States what an export will write, before anything is written, and takes the two export options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExportSheet(
    project: Project,
    sheet: ExportSheetUi,
    onQuality: (ExportQuality) -> Unit,
    onRemoveLocation: (Boolean) -> Unit,
    onRetryPlan: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OpenBlurTheme.colors
    var confirmEmpty by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Half-open would hide the save button below the fold, so the sheet opens fully.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = colors.surfaceRaised,
        contentColor = colors.text,
        tonalElevation = 0.dp,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sheet_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    color = colors.text,
                    style = MaterialTheme.typography.titleLarge,
                )
                IconAction(
                    icon = OpenBlurIcons.Close,
                    contentDescription = stringResource(R.string.action_close),
                    onClick = onDismiss,
                )
            }

            when (val plan = sheet.plan) {
                PlanUi.Loading -> {
                    MosaicProgress(progress = null, contentDescription = stringResource(R.string.sheet_plan_loading))
                    BodyText(stringResource(R.string.sheet_plan_loading), muted = true)
                }
                PlanUi.Failed -> Notice(
                    text = stringResource(R.string.sheet_plan_failed),
                    tone = NoticeTone.Error,
                    actionLabel = stringResource(R.string.action_try_again),
                    onAction = onRetryPlan,
                )
                PlanUi.Unavailable -> Notice(
                    title = stringResource(R.string.sheet_no_plan_title),
                    text = stringResource(R.string.sheet_no_plan_text),
                    tone = NoticeTone.Error,
                )
                is PlanUi.Ready -> PlanSummary(project.info, plan.plan)
            }

            if (sheet.plan != PlanUi.Unavailable) {
                Column {
                    SectionHeading(stringResource(R.string.sheet_quality_heading))
                    SegmentedChoice(
                        options = listOf(
                            stringResource(R.string.sheet_quality_match),
                            stringResource(R.string.sheet_quality_maximum),
                        ),
                        selectedIndex = if (sheet.quality == ExportQuality.MATCH_SOURCE) 0 else 1,
                        onSelect = { onQuality(if (it == 0) ExportQuality.MATCH_SOURCE else ExportQuality.MAXIMUM) },
                    )
                    BodyText(
                        text = stringResource(
                            if (sheet.quality == ExportQuality.MATCH_SOURCE) R.string.sheet_quality_match_text
                            else R.string.sheet_quality_maximum_text,
                        ),
                        modifier = Modifier.padding(top = 8.dp),
                        muted = true,
                    )
                }
                if (project.info.hasLocation) {
                    SwitchRow(
                        title = stringResource(R.string.sheet_remove_location),
                        description = stringResource(R.string.sheet_remove_location_text),
                        checked = sheet.removeLocation,
                        onCheckedChange = onRemoveLocation,
                    )
                }
                if (sheet.nothingBlurred == true) {
                    Notice(text = stringResource(R.string.sheet_nothing_blurred), tone = NoticeTone.Warning)
                }
                sheet.problem?.let { problem ->
                    Notice(
                        text = stringResource(
                            when (problem) {
                                SheetProblem.PREPARE_FAILED -> R.string.sheet_problem_prepare
                                SheetProblem.STORAGE_DENIED -> R.string.sheet_problem_storage
                                SheetProblem.START_FAILED -> R.string.sheet_problem_start
                            },
                        ),
                        tone = NoticeTone.Error,
                    )
                }
                BodyText(
                    text = stringResource(
                        R.string.sheet_destination,
                        stringResource(R.string.export_file_name, Formats.baseName(project.displayName)),
                    ),
                    muted = true,
                )
                PrimaryButton(
                    text = stringResource(R.string.sheet_save),
                    onClick = { if (sheet.nothingBlurred == true) confirmEmpty = true else onSave() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = sheet.canSave,
                )
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = stringResource(R.string.sheet_empty_title),
            text = stringResource(R.string.sheet_empty_text),
            confirmLabel = stringResource(R.string.sheet_empty_confirm),
            dismissLabel = stringResource(R.string.sheet_empty_dismiss),
            onConfirm = {
                confirmEmpty = false
                onSave()
            },
            onDismiss = { confirmEmpty = false },
        )
    }
}

@Composable
private fun PlanSummary(info: SourceVideoInfo, plan: EncodePlan) {
    val resources = LocalResources.current
    val estimatedBytes = Formats.estimatedExportBytes(plan.bitrate, info.durationUs, info.bitrate, info.fileSizeBytes)
    Column {
        SectionHeading(stringResource(R.string.sheet_plan_heading))
        InfoRow(stringResource(R.string.fact_resolution), Labels.resolution(resources, info))
        InfoRow(stringResource(R.string.fact_frame_rate), Labels.frameRate(resources, info.frameRate))
        InfoRow(stringResource(R.string.fact_codec), Formats.videoCodec(plan.mimeType))
        InfoRow(
            stringResource(R.string.fact_bitrate),
            if (plan.bitrate > 0) Labels.about(resources, Labels.bitrate(resources, plan.bitrate))
            else stringResource(R.string.value_unknown),
        )
        InfoRow(
            stringResource(R.string.fact_estimated_size),
            if (estimatedBytes > 0L) Labels.about(resources, Labels.fileSize(resources, estimatedBytes))
            else stringResource(R.string.value_unknown),
        )
        InfoRow(
            stringResource(R.string.fact_audio),
            stringResource(
                when {
                    info.audioMimeType == null -> R.string.sheet_audio_none
                    PlanNotice.AUDIO_REENCODED in plan.notices -> R.string.sheet_audio_reencoded
                    else -> R.string.sheet_audio_copied
                },
            ),
        )
        if (info.isHdr) {
            InfoRow(
                stringResource(R.string.fact_hdr),
                stringResource(if (plan.keepHdr) R.string.sheet_hdr_kept else R.string.sheet_hdr_converted),
            )
        }
    }
    if (plan.notices.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading(stringResource(R.string.sheet_differences_heading))
            PlanNotices(plan.notices)
        }
    }
}

/** One notice per difference, in the enum's order so the list does not reshuffle between screens. */
@Composable
internal fun PlanNotices(notices: Set<PlanNotice>) {
    val resources = LocalResources.current
    for (notice in notices.sortedBy { it.ordinal }) {
        Notice(text = Labels.planNotice(resources, notice), tone = noticeTone(notice))
    }
}

/** Differences that lose something from the original are warnings; the rest only inform. */
private fun noticeTone(notice: PlanNotice): NoticeTone = when (notice) {
    PlanNotice.SIZE_MAY_BE_ADJUSTED,
    PlanNotice.BITRATE_CAPPED_BY_ENCODER,
    PlanNotice.SLOWER_THAN_REAL_TIME,
    -> NoticeTone.Neutral
    else -> NoticeTone.Warning
}
