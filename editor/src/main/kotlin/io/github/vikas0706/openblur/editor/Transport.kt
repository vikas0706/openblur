package io.github.vikas0706.openblur.editor

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.vikas0706.openblur.designsystem.IconAction
import io.github.vikas0706.openblur.designsystem.OpenBlurDimens
import io.github.vikas0706.openblur.designsystem.OpenBlurIcons
import io.github.vikas0706.openblur.designsystem.OpenBlurShapes
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import io.github.vikas0706.openblur.designsystem.TimeText
import io.github.vikas0706.openblur.designsystem.formatTimeUs
import io.github.vikas0706.openblur.designsystem.openBlurIndication
import io.github.vikas0706.openblur.designsystem.tabularFigures
import kotlin.math.abs

/** Scrubber over a row of playback buttons. Disabled until the player has the video ready. */
@Composable
internal fun Transport(vm: EditorViewModel, state: EditorState, preview: PreviewPlayer, modifier: Modifier = Modifier) {
    val position = vm.positionUs.collectAsState()
    val ready = preview.status == PreviewStatus.READY
    val presence = remember(vm, state.edit, state.selection) { presenceRanges(vm.result, state.edit, state.selection) }
    val marks = remember(state.edit, state.selection) { keyframeMarks(state.edit, state.selection) }

    Column(modifier = modifier.padding(horizontal = 4.dp)) {
        Scrubber(
            position = position,
            durationUs = vm.durationUs,
            presence = presence,
            marks = marks,
            enabled = ready,
            onScrubStart = vm::beginScrub,
            onSeek = vm::seekTo,
            onScrubEnd = vm::endScrub,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(
                icon = if (preview.isPlaying) OpenBlurIcons.Pause else OpenBlurIcons.Play,
                contentDescription = stringResource(if (preview.isPlaying) R.string.editor_pause else R.string.editor_play),
                onClick = vm::togglePlay,
                enabled = ready,
            )
            IconAction(
                icon = OpenBlurIcons.StepBack,
                contentDescription = stringResource(R.string.editor_step_back),
                onClick = { vm.stepFrame(-1) },
                enabled = ready,
            )
            IconAction(
                icon = OpenBlurIcons.StepForward,
                contentDescription = stringResource(R.string.editor_step_forward),
                onClick = { vm.stepFrame(1) },
                enabled = ready,
            )
            PositionText(
                position,
                vm.durationUs,
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            IconAction(
                icon = if (state.muted) OpenBlurIcons.VolumeOff else OpenBlurIcons.VolumeOn,
                contentDescription = stringResource(if (state.muted) R.string.editor_unmute else R.string.editor_mute),
                onClick = { vm.setMuted(!state.muted) },
            )
        }
    }
}

@Composable
private fun PositionText(position: State<Long>, durationUs: Long, modifier: Modifier = Modifier) {
    // Whole seconds only, so this text recomposes once a second and not on every frame.
    val seconds by remember { derivedStateOf { position.value / 1_000_000L } }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TimeText(timeUs = seconds * 1_000_000L, color = OpenBlurTheme.colors.text)
        Text(
            text = stringResource(R.string.editor_time_separator),
            modifier = Modifier.clearAndSetSemantics { },
            color = OpenBlurTheme.colors.textMuted,
            style = MaterialTheme.typography.bodySmall.tabularFigures(),
        )
        TimeText(timeUs = durationUs)
    }
}

private val ScrubberEdge = 14.dp

/**
 * The timeline of the whole video. Above the track, bars show where the selected person or area is present:
 * solid amber where blurred, hollow grey where left visible. Below it, diamonds mark an area's set positions;
 * a tap close to one lands exactly on it.
 */
@Composable
private fun Scrubber(
    position: State<Long>,
    durationUs: Long,
    presence: List<PresenceRange>,
    marks: List<Long>,
    enabled: Boolean,
    onScrubStart: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OpenBlurTheme.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val interactions = remember { MutableInteractionSource() }
    val seek by rememberUpdatedState(onSeek)
    val start by rememberUpdatedState(onScrubStart)
    val end by rememberUpdatedState(onScrubEnd)
    val currentMarks by rememberUpdatedState(marks)

    val seconds by remember { derivedStateOf { position.value / 1_000_000L } }
    val label = stringResource(R.string.editor_scrubber)
    val spoken = stringResource(R.string.editor_time_of_total, formatTimeUs(seconds * 1_000_000L), formatTimeUs(durationUs))
    val span = durationUs.coerceAtLeast(1L)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(OpenBlurDimens.MinTouchTarget)
            .onKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val forward = if (rtl) Key.DirectionLeft else Key.DirectionRight
                val back = if (rtl) Key.DirectionRight else Key.DirectionLeft
                when (event.key) {
                    forward -> seek(position.value + KEY_STEP_US)
                    back -> seek(position.value - KEY_STEP_US)
                    Key.MoveHome -> seek(0L)
                    Key.MoveEnd -> seek(durationUs)
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusable(enabled = enabled, interactionSource = interactions)
            .indication(interactions, openBlurIndication(OpenBlurShapes.Control))
            .semantics {
                contentDescription = label
                stateDescription = spoken
                progressBarRangeInfo = ProgressBarRangeInfo((seconds * 1_000_000f / span).coerceIn(0f, 1f), 0f..1f)
                if (enabled) {
                    setProgress { fraction ->
                        seek((fraction.coerceIn(0f, 1f) * span).toLong())
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled, durationUs, rtl) {
                if (!enabled) return@pointerInput
                val edge = ScrubberEdge.toPx()
                fun timeAt(x: Float): Long {
                    val track = (size.width - 2 * edge).coerceAtLeast(1f)
                    val fraction = ((x - edge) / track).coerceIn(0f, 1f)
                    return ((if (rtl) 1f - fraction else fraction) * span).toLong()
                }
                // Only the first touch snaps to a position mark; a drag stays free to reach the moments around it.
                fun snapped(x: Float): Long {
                    val track = (size.width - 2 * edge).coerceAtLeast(1f)
                    val reachUs = (MARK_SNAP.toPx() / track * span).toLong()
                    val raw = timeAt(x)
                    val near = currentMarks.minByOrNull { abs(it - raw) }
                    return if (near != null && abs(near - raw) <= reachUs) near else raw
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    start()
                    try {
                        seek(snapped(down.position.x))
                        drag(down.id) { change ->
                            change.consume()
                            seek(timeAt(change.position.x))
                        }
                    } finally {
                        end()
                    }
                }
            }
            .drawBehind {
                val edge = ScrubberEdge.toPx()
                val track = (size.width - 2 * edge).coerceAtLeast(1f)
                val mid = size.height / 2
                fun x(timeUs: Long): Float {
                    val fraction = (timeUs.toFloat() / span).coerceIn(0f, 1f)
                    return edge + (if (rtl) 1f - fraction else fraction) * track
                }
                val restColor = if (enabled) colors.outlineStrong else colors.outline
                val onColor = if (enabled) colors.text else colors.textDisabled
                val thumbX = x(position.value)

                drawLine(restColor, Offset(edge, mid), Offset(edge + track, mid), 2.dp.toPx(), StrokeCap.Round)
                drawLine(onColor, Offset(x(0L), mid), Offset(thumbX, mid), 4.dp.toPx(), StrokeCap.Round)

                val barTop = mid - 13.dp.toPx()
                val barHeight = 5.dp.toPx()
                for (range in presence) {
                    val a = x(range.startUs)
                    val b = x(range.endUs)
                    val left = minOf(a, b)
                    val width = (abs(b - a)).coerceAtLeast(3.dp.toPx())
                    if (range.blurred) {
                        drawRect(colors.amber, Offset(left, barTop), Size(width, barHeight))
                    } else {
                        val line = 1.dp.toPx()
                        drawRect(
                            color = colors.outlineStrong,
                            topLeft = Offset(left + line / 2, barTop + line / 2),
                            size = Size((width - line).coerceAtLeast(line), barHeight - line),
                            style = Stroke(line),
                        )
                    }
                }

                val markY = mid + 11.dp.toPx()
                val half = 4.dp.toPx()
                for (mark in marks) {
                    val mx = x(mark)
                    val diamond = Path().apply {
                        moveTo(mx, markY - half)
                        lineTo(mx + half, markY)
                        lineTo(mx, markY + half)
                        lineTo(mx - half, markY)
                        close()
                    }
                    drawPath(diamond, colors.text)
                }

                val thumbWidth = 8.dp.toPx()
                val thumbHeight = 24.dp.toPx()
                val thumbTopLeft = Offset(thumbX - thumbWidth / 2, mid - thumbHeight / 2)
                val pill = CornerRadius(thumbWidth / 2)
                drawRoundRect(onColor, thumbTopLeft, Size(thumbWidth, thumbHeight), pill)
                // A dark rim keeps the thumb apart from the presence bars and marks it passes over.
                drawRoundRect(colors.surface, thumbTopLeft, Size(thumbWidth, thumbHeight), pill, style = Stroke(1.5.dp.toPx()))
            },
    )
}

private const val KEY_STEP_US = 1_000_000L
private val MARK_SNAP = 12.dp
