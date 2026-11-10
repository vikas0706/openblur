package io.github.vikas0706.openblur.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Press, hover and keyboard-focus visuals for a custom clickable of another shape.
 * Inside [OpenBlurTheme], `Modifier.clickable` already gets the 8 dp version.
 */
fun openBlurIndication(shape: Shape): Indication = StateIndication(shape)

/** Near-white press overlay on dark fills. PaletteContrastTest holds every text and border pair to WCAG at this value. */
internal const val PRESSED_ALPHA = 0.08f

/** Dark press overlay on the amber fill. */
internal const val AMBER_PRESSED_ALPHA = 0.16f

internal val ControlIndication = StateIndication(OpenBlurShapes.Control)

internal val AmberIndication = StateIndication(
    shape = OpenBlurShapes.Control,
    overlay = Color(Palette.ON_AMBER),
    pressedAlpha = AMBER_PRESSED_ALPHA,
)

/** For full-width rows: the highlight reaches 8 dp into the parent's side padding so text is not flush with its edge. */
internal val RowIndication = StateIndication(OpenBlurShapes.Control, sideOutset = 8.dp)

internal class StateIndication(
    private val shape: Shape,
    private val overlay: Color = Color(Palette.TEXT),
    private val pressedAlpha: Float = PRESSED_ALPHA,
    private val sideOutset: Dp = 0.dp,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        StateIndicationNode(interactionSource, shape, overlay, pressedAlpha, sideOutset)

    override fun equals(other: Any?): Boolean =
        other is StateIndication &&
            shape == other.shape &&
            overlay == other.overlay &&
            pressedAlpha == other.pressedAlpha &&
            sideOutset == other.sideOutset

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + overlay.hashCode()
        result = 31 * result + pressedAlpha.hashCode()
        result = 31 * result + sideOutset.hashCode()
        return result
    }
}

private class StateIndicationNode(
    private val interactions: InteractionSource,
    private val shape: Shape,
    private val overlay: Color,
    private val pressedAlpha: Float,
    private val sideOutset: Dp,
) : Modifier.Node(), DrawModifierNode {
    private val press = Animatable(0f)
    private var pressedAtNanos = 0L
    private var releaseJob: Job? = null
    private var hovered = false
    private var focused = false
    private val shapeCache = ShapeCache()
    private val focusRing = FocusRing()

    override fun onAttach() {
        coroutineScope.launch {
            // A node detached while pressed or focused never saw the matching release, so start clean.
            press.snapTo(0f)
            hovered = false
            focused = false
            var presses = 0
            var hovers = 0
            var focuses = 0
            interactions.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        presses++
                        showPress()
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        presses = (presses - 1).coerceAtLeast(0)
                        if (presses == 0) hidePress()
                    }
                    is HoverInteraction.Enter -> hovers++
                    is HoverInteraction.Exit -> hovers = (hovers - 1).coerceAtLeast(0)
                    is FocusInteraction.Focus -> focuses++
                    is FocusInteraction.Unfocus -> focuses = (focuses - 1).coerceAtLeast(0)
                }
                if (hovered != hovers > 0 || focused != focuses > 0) {
                    hovered = hovers > 0
                    focused = focuses > 0
                    invalidateDraw()
                }
            }
        }
    }

    private fun showPress() {
        releaseJob?.cancel()
        pressedAtNanos = System.nanoTime()
        coroutineScope.launch { press.snapTo(1f) }
    }

    private fun hidePress() {
        releaseJob = coroutineScope.launch {
            // A quick tap releases within a frame or two; hold the highlight long enough to be seen.
            val shownMs = (System.nanoTime() - pressedAtNanos) / 1_000_000
            if (shownMs < MIN_PRESS_MS) delay(MIN_PRESS_MS - shownMs)
            press.animateTo(0f, tween(durationMillis = 150))
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = maxOf(press.value * pressedAlpha, if (hovered) pressedAlpha / 2 else 0f)
        if (alpha <= 0f && !focused) return
        inset(horizontal = -sideOutset.toPx(), vertical = 0f) {
            if (alpha > 0f) drawOutline(shapeCache.outline(this, shape), overlay, alpha)
            if (focused) with(focusRing) { draw(shape) }
        }
    }

    private companion object {
        const val MIN_PRESS_MS = 100L
    }
}

private class ShapeCache {
    private var size = Size.Unspecified
    private var direction: LayoutDirection? = null
    private var density = 0f
    private var outline: Outline? = null

    fun outline(scope: DrawScope, shape: Shape): Outline {
        val cached = outline
        if (cached != null && size == scope.size && direction == scope.layoutDirection && density == scope.density) {
            return cached
        }
        size = scope.size
        direction = scope.layoutDirection
        density = scope.density
        return shape.createOutline(scope.size, scope.layoutDirection, scope).also { outline = it }
    }
}

/**
 * The keyboard-focus mark: a dashed ring just inside the bounds, near-white dashes over a dark line.
 * Dashed so it is never mistaken for a selection border, two-tone so it shows on dark and amber fills alike.
 */
internal class FocusRing {
    private var builtSize = Size.Unspecified
    private var builtDirection: LayoutDirection? = null
    private var builtDensity = 0f
    private val path = Path()
    private var line: Stroke? = null
    private var dashes: Stroke? = null

    fun DrawScope.draw(shape: Shape) {
        if (builtSize != size || builtDirection != layoutDirection || builtDensity != density) rebuild(shape)
        val line = line ?: return
        val dashes = dashes ?: return
        drawPath(path, Color(Palette.SURFACE), style = line)
        drawPath(path, Color(Palette.TEXT), style = dashes)
    }

    private fun DrawScope.rebuild(shape: Shape) {
        builtSize = size
        builtDirection = layoutDirection
        builtDensity = density
        line = null
        dashes = null
        path.reset()

        val width = RING_WIDTH.toPx()
        val inner = Size(size.width - width, size.height - width)
        if (inner.width <= 0f || inner.height <= 0f) return
        val offset = Offset(width / 2, width / 2)
        when (val outline = shape.createOutline(inner, layoutDirection, this)) {
            is Outline.Rectangle -> path.addRect(outline.rect.translate(offset))
            is Outline.Rounded -> path.addRoundRect(outline.roundRect.translate(offset))
            is Outline.Generic -> path.addPath(outline.path, offset)
        }

        // A whole number of dash periods around the ring, so there is no short dash where the path closes.
        val length = PathMeasure().apply { setPath(path, forceClosed = true) }.length
        if (length <= 0f) return
        val periods = (length / DASH_PERIOD.toPx()).roundToInt().coerceAtLeast(4)
        val period = length / periods
        val dash = period * 2 / 3
        line = Stroke(width)
        dashes = Stroke(
            width = width,
            cap = StrokeCap.Butt,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, period - dash)),
        )
    }

    private companion object {
        val RING_WIDTH = 3.dp
        val DASH_PERIOD = 12.dp
    }
}
