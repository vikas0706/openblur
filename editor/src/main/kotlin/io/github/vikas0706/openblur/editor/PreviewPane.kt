package io.github.vikas0706.openblur.editor

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.keepScreenOn
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.designsystem.MosaicProgress
import io.github.vikas0706.openblur.designsystem.Notice
import io.github.vikas0706.openblur.designsystem.NoticeTone
import io.github.vikas0706.openblur.designsystem.OpenBlurDimens
import io.github.vikas0706.openblur.designsystem.OpenBlurTheme
import kotlin.math.roundToInt

/** What the preview's gesture handlers need, re-read on every event so a drag survives recomposition. */
private class PreviewInput(
    val vm: EditorViewModel,
    val state: EditorState,
    val timeline: MaskTimeline?,
    val ready: Boolean,
    val video: PxRect,
    val safeBounds: PxRect,
    val touchPx: Float,
    val slopPx: Float,
    val frameAspect: Float,
    val position: State<Long>,
) {
    private val hits = MaskBatch()

    fun selectedRegionId(): Int? = (state.selection as? Selection.OfRegion)?.regionId

    /** The handle of the selected area under the point. With [orBody], anywhere inside the area counts as the move handle. */
    fun handleAt(x: Float, y: Float, orBody: Boolean): RegionHandle? {
        val id = selectedRegionId() ?: return null
        val now = vm.areaNow(id, state.edit, position.value) ?: return null
        val rect = regionRectPx(now, video)
        return hitHandle(handleCenters(rect, safeBounds, touchPx), x, y, touchPx)
            ?: if (orBody && rect.contains(x, y)) RegionHandle.MOVE else null
    }

    /** Index into [hits] of the mask under the point, or -1. */
    private fun maskAt(x: Float, y: Float): Int {
        val masks = timeline ?: return -1
        if (!ready || video.isEmpty || !video.contains(x, y)) return -1
        masks.sample(position.value, hits, includeHidden = true)
        val nx = (x - video.left) / video.width
        val ny = (y - video.top) / video.height
        return hitTestMask(hits, nx, ny, frameAspect, slop = slopPx / video.height)
    }

    fun tap(x: Float, y: Float) {
        // A face inside the selected area stays tappable; only the handles themselves swallow the tap.
        if (handleAt(x, y, orBody = false) != null) return
        val i = maskAt(x, y)
        if (i < 0) return
        if (hits.kind[i] == MaskBatch.KIND_FACE) vm.faceTapped(hits.sourceId[i]) else vm.selectRegion(hits.sourceId[i])
    }

    /** Returns true when the press selected something, so the caller can confirm it with a haptic. */
    fun longPress(x: Float, y: Float): Boolean {
        if (handleAt(x, y, orBody = false) != null) return false
        val i = maskAt(x, y)
        if (i < 0) return false
        if (hits.kind[i] == MaskBatch.KIND_FACE) vm.faceLongPressed(hits.sourceId[i]) else vm.selectRegion(hits.sourceId[i])
        return true
    }
}

/**
 * The video with its blur, the face and area outlines on top of it, and the gestures that edit them.
 * Outlines are sampled from [timeline] at the playhead inside the draw pass, so they move without recomposition.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun PreviewPane(
    vm: EditorViewModel,
    state: EditorState,
    preview: PreviewPlayer,
    timeline: MaskTimeline?,
    previewStale: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = OpenBlurTheme.colors
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val haptics = LocalHapticFeedback.current
    val position = vm.positionUs.collectAsState()
    val status = preview.status
    val ready = status == PreviewStatus.READY

    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    var paneOrigin by remember { mutableStateOf(Offset.Zero) }
    val window = LocalWindowInfo.current.containerSize
    val gestures = WindowInsets.systemGestures
    val safeBounds = gestureSafeBounds(
        paneLeft = paneOrigin.x,
        paneTop = paneOrigin.y,
        paneWidth = paneSize.width.toFloat(),
        paneHeight = paneSize.height.toFloat(),
        windowWidth = window.width.toFloat(),
        windowHeight = window.height.toFloat(),
        insetLeft = gestures.getLeft(density, layoutDirection).toFloat(),
        insetTop = gestures.getTop(density).toFloat(),
        insetRight = gestures.getRight(density, layoutDirection).toFloat(),
        insetBottom = gestures.getBottom(density).toFloat(),
    )
    val video = fitVideoRect(paneSize.width.toFloat(), paneSize.height.toFloat(), videoWidth, videoHeight)
    val touchPx = with(density) { OpenBlurDimens.MinTouchTarget.toPx() }
    val slopPx = with(density) { 16.dp.toPx() }
    val personOfTrack = remember(vm) { vm.result.tracks.associate { it.id to it.personId } }

    val input by rememberUpdatedState(
        PreviewInput(
            vm = vm,
            state = state,
            timeline = timeline,
            ready = ready,
            video = video,
            safeBounds = safeBounds,
            touchPx = touchPx,
            slopPx = slopPx,
            frameAspect = if (videoHeight > 0) videoWidth.toFloat() / videoHeight else 1f,
            position = position,
        ),
    )
    val drawn = remember { MaskBatch() }
    val previewLabel = stringResource(R.string.editor_preview)

    Box(
        modifier = modifier
            .background(colors.surface)
            .then(if (preview.isPlaying) Modifier.keepScreenOn() else Modifier)
            .onGloballyPositioned {
                paneSize = it.size
                paneOrigin = it.positionInWindow()
            }
            .clipToBounds(),
    ) {
        PlayerSurface(
            player = preview.player,
            // Placed by absolute position: the picture must not mirror in right-to-left layouts.
            modifier = Modifier.layout { measurable, constraints ->
                val w = video.width.roundToInt().coerceAtLeast(0)
                val h = video.height.roundToInt().coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(video.left.roundToInt(), video.top.roundToInt())
                }
            },
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { contentDescription = previewLabel }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { input.tap(it.x, it.y) },
                        onLongPress = {
                            if (input.longPress(it.x, it.y)) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                    )
                }
                .pointerInput(Unit) {
                    // Only a press on the selected area or its handles starts a drag; any other drag is left
                    // for a scrolling parent.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pressed = input
                        val id = pressed.selectedRegionId() ?: return@awaitEachGesture
                        val handle = pressed.handleAt(down.position.x, down.position.y, orBody = true) ?: return@awaitEachGesture
                        var moved = Offset.Zero
                        val first = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                            change.consume()
                            moved = overSlop
                        } ?: return@awaitEachGesture
                        val from = pressed.vm.beginAreaGesture(id) ?: return@awaitEachGesture

                        fun place() {
                            val video = input.video
                            if (video.isEmpty) return
                            val to = dragRegion(from, handle, moved.x / video.width, moved.y / video.height)
                            input.vm.placeArea(id, to.cx, to.cy, to.w, to.h)
                        }
                        try {
                            place()
                            drag(first.id) { change ->
                                moved += change.positionChange()
                                change.consume()
                                place()
                            }
                        } finally {
                            input.vm.endGesture()
                        }
                    }
                },
        ) {
            if (video.isEmpty || !ready) return@Canvas
            val timeUs = position.value
            val selection = state.selection
            val selectedRegionId = (selection as? Selection.OfRegion)?.regionId
            val selectedPersonId = (selection as? Selection.OfPerson)?.personId
            if (timeline != null) {
                timeline.sample(timeUs, drawn, includeHidden = true)
                for (i in 0 until drawn.count) {
                    val isFace = drawn.kind[i] == MaskBatch.KIND_FACE
                    // The selected area is drawn from the edit itself below, so it follows a drag without waiting for a rebuild.
                    if (!isFace && drawn.sourceId[i] == selectedRegionId) continue
                    drawMaskOutline(
                        batch = drawn,
                        index = i,
                        video = video,
                        blurred = !drawn.hidden[i],
                        selected = isFace && selectedPersonId != null && personOfTrack[drawn.sourceId[i]] == selectedPersonId,
                    )
                }
            }
            if (selectedRegionId != null) {
                val region = regionOf(state.edit, selectedRegionId)
                val now = vm.areaNow(selectedRegionId, state.edit, timeUs)
                if (region != null && now != null) {
                    val rect = regionRectPx(now, video)
                    drawAreaFrame(rect, active = timeUs >= region.startUs && timeUs <= region.endUs)
                    drawAreaHandles(handleCenters(rect, safeBounds, touchPx))
                }
            }
        }

        when (status) {
            PreviewStatus.READY -> Unit
            PreviewStatus.PREPARING -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 240.dp)
                    .padding(OpenBlurDimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(OpenBlurDimens.ItemGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val preparing = stringResource(R.string.editor_preparing)
                MosaicProgress(progress = null, contentDescription = preparing, tileCount = 8)
                Text(
                    text = preparing,
                    // The progress tiles already say this to screen readers.
                    modifier = Modifier.clearAndSetSemantics { },
                    color = colors.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            PreviewStatus.FAILED, PreviewStatus.SOURCE_GONE -> {
                val gone = status == PreviewStatus.SOURCE_GONE
                Notice(
                    text = stringResource(if (gone) R.string.editor_source_gone_text else R.string.editor_player_error_text),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .widthIn(max = 420.dp)
                        .padding(OpenBlurDimens.ScreenPadding),
                    tone = NoticeTone.Error,
                    title = stringResource(if (gone) R.string.editor_source_gone_title else R.string.editor_player_error_title),
                    actionLabel = stringResource(R.string.editor_try_again),
                    onAction = onRetry,
                )
            }
        }

        if (previewStale && ready) {
            Notice(
                text = stringResource(R.string.editor_preview_stale),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 420.dp)
                    .padding(OpenBlurDimens.ItemGap),
                tone = NoticeTone.Warning,
            )
        }
    }
}

private val Scrim = OpenBlurTheme.colors.surface

/**
 * Blurred masks get a solid amber line, faces left visible a dashed grey one, so the two differ by more
 * than colour. A dark line under each keeps it readable on bright footage.
 */
private fun DrawScope.drawMaskOutline(batch: MaskBatch, index: Int, video: PxRect, blurred: Boolean, selected: Boolean) {
    val colors = OpenBlurTheme.colors
    val g = batch.geometry
    val o = index * MaskBatch.GEOMETRY_STRIDE
    val cx = video.left + g[o] * video.width
    val cy = video.top + g[o + 1] * video.height
    val halfW = g[o + 2] * video.height
    val halfH = g[o + 3] * video.height
    if (halfW <= 0f || halfH <= 0f) return
    val oval = batch.shape[index] == MaskShape.OVAL.ordinal
    val line = 2.dp.toPx()
    val dash = if (blurred) null else PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))

    fun outline(color: Color, width: Float, grow: Float, effect: PathEffect?, alpha: Float = 1f) {
        val topLeft = Offset(cx - halfW - grow, cy - halfH - grow)
        val size = Size(2 * (halfW + grow), 2 * (halfH + grow))
        val style = Stroke(width, pathEffect = effect)
        if (oval) {
            drawOval(color, topLeft, size, alpha = alpha, style = style)
        } else {
            drawRoundRect(color, topLeft, size, CornerRadius(4.dp.toPx() + grow), alpha = alpha, style = style)
        }
    }

    rotate(degrees = Math.toDegrees(g[o + 4].toDouble()).toFloat(), pivot = Offset(cx, cy)) {
        outline(Scrim, line * 2, 0f, null, alpha = 0.6f)
        outline(if (blurred) colors.amber else colors.outlineStrong, line, 0f, dash)
        if (selected) {
            outline(Scrim, line * 2, 5.dp.toPx(), null, alpha = 0.6f)
            outline(colors.text, line, 5.dp.toPx(), null)
        }
    }
}

/** The selected area's frame: solid while the area is shown at this moment, dashed outside its time range. */
private fun DrawScope.drawAreaFrame(rect: PxRect, active: Boolean) {
    val colors = OpenBlurTheme.colors
    val line = 2.dp.toPx()
    val topLeft = Offset(rect.left, rect.top)
    val size = Size(rect.width, rect.height)
    val dash = if (active) null else PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
    drawRect(Scrim, topLeft, size, alpha = 0.6f, style = Stroke(line * 2))
    drawRect(colors.amber, topLeft, size, style = Stroke(line, pathEffect = dash))
}

private fun DrawScope.drawAreaHandles(centers: FloatArray) {
    val colors = OpenBlurTheme.colors
    val border = 1.5.dp.toPx()

    fun tile(x: Float, y: Float, side: Float) {
        val topLeft = Offset(x - side / 2, y - side / 2)
        val corner = CornerRadius(4.dp.toPx())
        drawRoundRect(colors.text, topLeft, Size(side, side), corner)
        drawRoundRect(Scrim, topLeft, Size(side, side), corner, style = Stroke(border))
    }

    for (handle in RegionHandle.entries) {
        val x = centers[handle.ordinal * 2]
        val y = centers[handle.ordinal * 2 + 1]
        if (handle == RegionHandle.MOVE) {
            tile(x, y, 24.dp.toPx())
            // A cross on the centre tile sets the move handle apart from the plain corner tiles.
            val arm = 7.dp.toPx()
            drawLine(Scrim, Offset(x - arm, y), Offset(x + arm, y), strokeWidth = 2.dp.toPx())
            drawLine(Scrim, Offset(x, y - arm), Offset(x, y + arm), strokeWidth = 2.dp.toPx())
        } else {
            tile(x, y, 16.dp.toPx())
        }
    }
}
