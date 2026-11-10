package io.github.vikas0706.openblur.shell

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.api.ExportReport
import io.github.vikas0706.openblur.designsystem.Notice
import io.github.vikas0706.openblur.designsystem.NoticeTone
import io.github.vikas0706.openblur.designsystem.OpenBlurDimens
import io.github.vikas0706.openblur.designsystem.OpenBlurIcons
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.PrimaryButton
import io.github.vikas0706.openblur.designsystem.SecondaryButton
import io.github.vikas0706.openblur.designsystem.SectionHeading
import io.github.vikas0706.openblur.designsystem.TopBar
import io.github.vikas0706.openblur.designsystem.formatTimeUs
import io.github.vikas0706.openblur.designsystem.tabularFigures

@Composable
internal fun ResultScreen(
    outcome: ExportOutcome,
    canReturnToEditor: Boolean,
    onBackToEditor: () -> Unit,
    onBlurAnother: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBackToEditor)
    when (outcome) {
        is ExportOutcome.Saved -> SavedResult(outcome.report, canReturnToEditor, onBackToEditor, onBlurAnother, modifier)
        is ExportOutcome.Failed -> FailedResult(outcome, canReturnToEditor, onBackToEditor, onBlurAnother, modifier)
        ExportOutcome.Cancelled -> CancelledResult(canReturnToEditor, onBackToEditor, onBlurAnother, modifier)
    }
}

@Composable
private fun SavedResult(
    report: ExportReport,
    canReturnToEditor: Boolean,
    onBackToEditor: () -> Unit,
    onBlurAnother: () -> Unit,
    modifier: Modifier,
) {
    val output = report.output
    val aspect = if (output.displayWidth > 0 && output.displayHeight > 0) {
        output.displayWidth.toFloat() / output.displayHeight.toFloat()
    } else {
        16f / 9f
    }
    Column(modifier.fillMaxSize().background(OpenBlurTheme.colors.surface)) {
        TopBar(title = stringResource(R.string.result_saved_title), onBack = onBackToEditor)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) {
            // Side by side only when the window is wide and short; otherwise the video would squeeze the details.
            val sideBySide = maxWidth >= 720.dp && maxWidth > maxHeight
            val availableHeight = maxHeight
            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(OpenBlurDimens.ScreenPadding),
                    horizontalArrangement = Arrangement.spacedBy(OpenBlurDimens.SectionGap),
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
                    ) {
                        SavedVideoPlayer(report.outputUri, aspect, Modifier.heightIn(max = availableHeight * 0.6f))
                    }
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
                    ) {
                        SavedDetails(report, canReturnToEditor, onBackToEditor, onBlurAnother)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = ContentMaxWidth)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(OpenBlurDimens.ScreenPadding),
                        verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
                    ) {
                        SavedVideoPlayer(
                            uri = report.outputUri,
                            aspect = aspect,
                            modifier = Modifier.heightIn(max = (availableHeight * 0.5f).coerceAtLeast(160.dp)),
                        )
                        SavedDetails(report, canReturnToEditor, onBackToEditor, onBlurAnother)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SavedDetails(
    report: ExportReport,
    canReturnToEditor: Boolean,
    onBackToEditor: () -> Unit,
    onBlurAnother: () -> Unit,
) {
    val context = LocalContext.current
    var noApp by remember { mutableStateOf(false) }

    Column {
        BodyText(stringResource(R.string.result_saved_as, report.displayName))
        if (report.elapsedMs > 0L) {
            BodyText(stringResource(R.string.result_took, formatTimeUs(report.elapsedMs * 1000L)), muted = true)
        }
    }
    PrimaryButton(
        text = stringResource(R.string.result_share),
        onClick = { noApp = !ResultIntents.share(context, report.outputUri) },
        modifier = Modifier.fillMaxWidth(),
        icon = OpenBlurIcons.Share,
    )
    SecondaryButton(
        text = stringResource(R.string.result_open_external),
        onClick = { noApp = !ResultIntents.open(context, report.outputUri) },
        modifier = Modifier.fillMaxWidth(),
        icon = OpenBlurIcons.OpenExternal,
    )
    if (noApp) Notice(text = stringResource(R.string.result_no_app), tone = NoticeTone.Error)

    ComparisonTable(report)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(stringResource(R.string.result_differences_heading))
        if (report.notices.isEmpty()) {
            BodyText(stringResource(R.string.result_no_differences), muted = true)
        } else {
            PlanNotices(report.notices)
        }
    }

    LeaveActions(canReturnToEditor, primary = false, onBackToEditor, onBlurAnother)
}

/** Source and output side by side, both read from the files, so the user can check the result. */
@Composable
private fun ComparisonTable(report: ExportReport) {
    val resources = LocalResources.current
    val source = report.source
    val output = report.output
    Column {
        SectionHeading(stringResource(R.string.result_compare_heading))
        CompareRow(
            label = "",
            source = stringResource(R.string.result_column_original),
            output = stringResource(R.string.result_column_saved),
            header = true,
        )
        CompareRow(
            stringResource(R.string.fact_resolution),
            Labels.resolution(resources, source),
            Labels.resolution(resources, output),
        )
        CompareRow(
            stringResource(R.string.fact_frame_rate),
            Labels.frameRate(resources, source.frameRate),
            Labels.frameRate(resources, output.frameRate),
        )
        CompareRow(
            stringResource(R.string.fact_codec),
            Labels.videoCodec(resources, source),
            Labels.videoCodec(resources, output),
        )
        CompareRow(
            stringResource(R.string.fact_bitrate),
            Labels.bitrate(resources, source.bitrate),
            Labels.bitrate(resources, output.bitrate),
        )
        CompareRow(
            stringResource(R.string.fact_audio),
            Labels.audio(resources, source.audioMimeType),
            outputAudio(report),
        )
        CompareRow(
            stringResource(R.string.fact_file_size),
            Labels.fileSize(resources, source.fileSizeBytes),
            Labels.fileSize(resources, output.fileSizeBytes),
        )
        CompareRow(
            stringResource(R.string.fact_hdr),
            Labels.hdr(resources, source),
            Labels.hdr(resources, output),
        )
        CompareRow(
            stringResource(R.string.fact_duration),
            Labels.duration(source.durationUs),
            Labels.duration(output.durationUs),
        )
    }
}

@Composable
private fun outputAudio(report: ExportReport): String {
    val resources = LocalResources.current
    val codec = Labels.audio(resources, report.output.audioMimeType)
    // "Copied" only means something when both files have audio.
    if (report.source.audioMimeType == null || report.output.audioMimeType == null) return codec
    return stringResource(if (report.audioCopied) R.string.result_audio_copied else R.string.result_audio_reencoded, codec)
}

@Composable
private fun CompareRow(label: String, source: String, output: String, header: Boolean = false) {
    val colors = OpenBlurTheme.colors
    val spoken = stringResource(R.string.result_compare_spoken, label, source, output)
    val valueStyle = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium.tabularFigures()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            // The header only labels the columns; each row already speaks both column names.
            .then(
                if (header) Modifier.clearAndSetSemantics { }
                else Modifier.clearAndSetSemantics { contentDescription = spoken },
            ),
        horizontalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.3f),
            color = colors.textMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = source,
            modifier = Modifier.weight(0.35f),
            color = if (header) colors.textMuted else colors.text,
            style = valueStyle,
        )
        Text(
            text = output,
            modifier = Modifier.weight(0.35f),
            color = if (header) colors.textMuted else colors.text,
            style = valueStyle,
        )
    }
}

@Composable
private fun FailedResult(
    outcome: ExportOutcome.Failed,
    canReturnToEditor: Boolean,
    onBackToEditor: () -> Unit,
    onBlurAnother: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    var copied by remember(outcome) { mutableStateOf(false) }
    val clipLabel = stringResource(R.string.result_details_label)

    ScreenFrame(title = stringResource(R.string.result_failed_title), onBack = onBackToEditor, modifier = modifier) {
        Notice(text = Labels.exportFailure(resources, outcome.failure), tone = NoticeTone.Error)
        Column {
            SectionHeading(stringResource(R.string.result_what_to_do))
            BodyText(Labels.exportFailureAdvice(resources, outcome.failure))
        }
        LeaveActions(canReturnToEditor, primary = true, onBackToEditor, onBlurAnother)
        SecondaryButton(
            text = stringResource(R.string.result_copy_details),
            onClick = { copied = copyToClipboard(context, clipLabel, outcome.detail) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (copied) {
            BodyText(
                text = stringResource(R.string.result_details_copied),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                muted = true,
            )
        }
    }
}

@Composable
private fun CancelledResult(
    canReturnToEditor: Boolean,
    onBackToEditor: () -> Unit,
    onBlurAnother: () -> Unit,
    modifier: Modifier,
) {
    ScreenFrame(title = stringResource(R.string.result_cancelled_title), onBack = onBackToEditor, modifier = modifier) {
        MosaicMark(Modifier.padding(top = 8.dp))
        BodyText(stringResource(R.string.result_cancelled_text))
        LeaveActions(canReturnToEditor, primary = true, onBackToEditor, onBlurAnother)
    }
}

/**
 * The ways off a result screen. [primary] makes the first one the screen's main action,
 * which the saved result does not want because sharing is its main action.
 */
@Composable
private fun LeaveActions(
    canReturnToEditor: Boolean,
    primary: Boolean,
    onBackToEditor: () -> Unit,
    onBlurAnother: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap)) {
        if (canReturnToEditor) {
            if (primary) {
                PrimaryButton(
                    text = stringResource(R.string.result_back_to_editor),
                    onClick = onBackToEditor,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                SecondaryButton(
                    text = stringResource(R.string.result_back_to_editor),
                    onClick = onBackToEditor,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (primary && !canReturnToEditor) {
            PrimaryButton(
                text = stringResource(R.string.result_blur_another),
                onClick = onBlurAnother,
                modifier = Modifier.fillMaxWidth(),
                icon = OpenBlurIcons.Video,
            )
        } else {
            SecondaryButton(
                text = stringResource(R.string.result_blur_another),
                onClick = onBlurAnother,
                modifier = Modifier.fillMaxWidth(),
                icon = OpenBlurIcons.Video,
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String): Boolean = try {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    true
} catch (_: RuntimeException) {
    // Some phones refuse very large clips or clipboard access from the background.
    false
}

internal object ResultIntents {
    /** Both return false when no app on the phone takes the video. */
    fun share(context: Context, uri: Uri): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeOf(context, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            // The clip carries the read grant to whichever app the user picks in the chooser.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun open(context: Context, uri: Uri): Boolean {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeOf(context, uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(context, view)
    }

    private fun mimeTypeOf(context: Context, uri: Uri): String = try {
        context.contentResolver.getType(uri)
    } catch (_: RuntimeException) {
        null
    } ?: "video/*"

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@Composable
internal fun VideoBackdrop(aspect: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .aspectRatio(aspect)
            // Pure black, so letterbox bars and the video's own black frames match.
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
