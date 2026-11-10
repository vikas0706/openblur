package io.github.vikas0706.openblur.editor

import io.github.vikas0706.openblur.core.EditOps
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskShape
import io.github.vikas0706.openblur.core.RegionKeyframe
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A rectangle in pixels, x right and y down. Kept free of Compose types so the maths runs in JVM tests. */
internal data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** The largest rectangle with the video's aspect ratio that fits the container, centred (letterboxed). */
internal fun fitVideoRect(containerWidth: Float, containerHeight: Float, videoWidth: Int, videoHeight: Int): PxRect {
    if (containerWidth <= 0f || containerHeight <= 0f || videoWidth <= 0 || videoHeight <= 0) {
        return PxRect(0f, 0f, 0f, 0f)
    }
    val scale = min(containerWidth / videoWidth, containerHeight / videoHeight)
    val w = videoWidth * scale
    val h = videoHeight * scale
    val left = (containerWidth - w) / 2f
    val top = (containerHeight - h) / 2f
    return PxRect(left, top, left + w, top + h)
}

/**
 * The part of a pane that system edge gestures leave alone, in the pane's own coordinates.
 * The pane sits at ([paneLeft], [paneTop]) in a window of [windowWidth] x [windowHeight] whose gesture
 * insets are the four inset values. Falls back to the whole pane when the insets leave nothing.
 */
internal fun gestureSafeBounds(
    paneLeft: Float,
    paneTop: Float,
    paneWidth: Float,
    paneHeight: Float,
    windowWidth: Float,
    windowHeight: Float,
    insetLeft: Float,
    insetTop: Float,
    insetRight: Float,
    insetBottom: Float,
): PxRect {
    val left = max(0f, insetLeft - paneLeft)
    val top = max(0f, insetTop - paneTop)
    val right = min(paneWidth, windowWidth - insetRight - paneLeft)
    val bottom = min(paneHeight, windowHeight - insetBottom - paneTop)
    return if (right - left <= 0f || bottom - top <= 0f) PxRect(0f, 0f, paneWidth, paneHeight) else PxRect(left, top, right, bottom)
}

/**
 * Index of the mask under the normalized frame point ([nx], [ny]), or -1. [aspect] is frame width over height.
 * When several masks contain the point the smallest wins, so a face inside a large area stays tappable.
 * [slop] widens every mask by that fraction of the frame height, and is used only when nothing is hit exactly.
 */
internal fun hitTestMask(batch: MaskBatch, nx: Float, ny: Float, aspect: Float, slop: Float = 0f): Int {
    val exact = smallestMaskAt(batch, nx, ny, aspect, 0f)
    return if (exact >= 0 || slop <= 0f) exact else smallestMaskAt(batch, nx, ny, aspect, slop)
}

private fun smallestMaskAt(batch: MaskBatch, nx: Float, ny: Float, aspect: Float, grow: Float): Int {
    var best = -1
    var bestArea = Float.MAX_VALUE
    val g = batch.geometry
    for (i in 0 until batch.count) {
        val o = i * MaskBatch.GEOMETRY_STRIDE
        val halfW = g[o + 2]
        val halfH = g[o + 3]
        if (halfW <= 0f || halfH <= 0f) continue
        val dx = (nx - g[o]) * aspect
        val dy = ny - g[o + 1]
        val c = cos(g[o + 4])
        val s = sin(g[o + 4])
        val qx = (dx * c + dy * s) / (halfW + grow)
        val qy = (-dx * s + dy * c) / (halfH + grow)
        val oval = batch.shape[i] == MaskShape.OVAL.ordinal
        val inside = if (oval) qx * qx + qy * qy <= 1f else qx in -1f..1f && qy in -1f..1f
        if (!inside) continue
        val area = halfW * halfH * (if (oval) Math.PI.toFloat() else 4f)
        if (area < bestArea) {
            bestArea = area
            best = i
        }
    }
    return best
}

internal enum class RegionHandle { MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Where a manual area sits inside the displayed video rectangle. */
internal fun regionRectPx(k: RegionKeyframe, video: PxRect): PxRect {
    val cx = video.left + k.cx * video.width
    val cy = video.top + k.cy * video.height
    val hw = k.w * video.width / 2f
    val hh = k.h * video.height / 2f
    return PxRect(cx - hw, cy - hh, cx + hw, cy + hh)
}

/**
 * Centres of the five handles as x,y pairs in [RegionHandle] order. Corner handles sit on the corners of
 * the area grown to at least two touch targets per side, so they never cover each other or the move handle
 * however small the area is. Every centre is then pulled inside [bounds] by half a [touch] target.
 */
internal fun handleCenters(region: PxRect, bounds: PxRect, touch: Float): FloatArray {
    val growX = max(0f, (2f * touch - region.width) / 2f)
    val growY = max(0f, (2f * touch - region.height) / 2f)
    val left = region.left - growX
    val right = region.right + growX
    val top = region.top - growY
    val bottom = region.bottom + growY
    val half = touch / 2f
    fun fitX(x: Float) = if (bounds.width >= touch) x.coerceIn(bounds.left + half, bounds.right - half) else bounds.centerX
    fun fitY(y: Float) = if (bounds.height >= touch) y.coerceIn(bounds.top + half, bounds.bottom - half) else bounds.centerY
    return floatArrayOf(
        fitX(region.centerX), fitY(region.centerY),
        fitX(left), fitY(top),
        fitX(right), fitY(top),
        fitX(left), fitY(bottom),
        fitX(right), fitY(bottom),
    )
}

/** The handle whose [touch]-sized square contains the point. Corners win over the move handle. */
internal fun hitHandle(centers: FloatArray, x: Float, y: Float, touch: Float): RegionHandle? {
    val half = touch / 2f
    var best: RegionHandle? = null
    var bestDistance = Float.MAX_VALUE
    for (handle in RegionHandle.entries) {
        val dx = x - centers[handle.ordinal * 2]
        val dy = y - centers[handle.ordinal * 2 + 1]
        if (dx < -half || dx > half || dy < -half || dy > half) continue
        // The move handle only wins when no corner handle covers the point.
        val distance = dx * dx + dy * dy + if (handle == RegionHandle.MOVE) touch * touch else 0f
        if (distance < bestDistance) {
            bestDistance = distance
            best = handle
        }
    }
    return best
}

/**
 * The area after dragging [handle] by ([dx], [dy]), both fractions of the frame, starting from [start].
 * Moving keeps the size; a corner drag keeps the opposite corner where it is and never shrinks the area
 * below [EditOps.MIN_REGION_SIZE] or pulls the dragged corner outside the frame.
 */
internal fun dragRegion(start: RegionKeyframe, handle: RegionHandle, dx: Float, dy: Float): RegionKeyframe {
    if (handle == RegionHandle.MOVE) {
        return EditOps.clampKeyframe(start.copy(cx = start.cx + dx, cy = start.cy + dy))
    }
    var left = start.cx - start.w / 2f
    var right = start.cx + start.w / 2f
    var top = start.cy - start.h / 2f
    var bottom = start.cy + start.h / 2f
    val minSize = EditOps.MIN_REGION_SIZE
    if (handle == RegionHandle.TOP_LEFT || handle == RegionHandle.BOTTOM_LEFT) {
        left = min(max(left + dx, min(0f, left)), right - minSize)
    } else {
        right = max(min(right + dx, max(1f, right)), left + minSize)
    }
    if (handle == RegionHandle.TOP_LEFT || handle == RegionHandle.TOP_RIGHT) {
        top = min(max(top + dy, min(0f, top)), bottom - minSize)
    } else {
        bottom = max(min(bottom + dy, max(1f, bottom)), top + minSize)
    }
    return EditOps.clampKeyframe(
        RegionKeyframe(start.timeUs, (left + right) / 2f, (top + bottom) / 2f, right - left, bottom - top),
    )
}

/** Height in pixels for the preview's downscale effect, or 0 when the video's short side is within [maxShortSide]. */
internal fun previewDownscaleHeight(displayWidth: Int, displayHeight: Int, maxShortSide: Int = 1080): Int {
    val short = min(displayWidth, displayHeight)
    if (short <= maxShortSide || short <= 0) return 0
    val height = (displayHeight.toLong() * maxShortSide / short).toInt()
    // Kept even, as video frame sizes conventionally are.
    return max(2, height - height % 2)
}
