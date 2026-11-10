package io.github.vikas0706.openblur.vision

import kotlin.math.sqrt

/**
 * Least-squares similarity fit of five face landmarks to the 112x112 ArcFace template that SFace was trained on.
 * The closed form gives the same transform as the Umeyama fit OpenCV runs, without a singular value decomposition.
 */
internal object ArcFaceAlignment {
    const val CROP_SIZE = 112

    /** Right eye, left eye, nose tip, right mouth corner, left mouth corner, with pixel centres on integers. */
    val TEMPLATE = doubleArrayOf(
        38.2946, 51.6963,
        73.5318, 51.5014,
        56.0252, 71.7366,
        41.5493, 92.3655,
        70.7299, 92.2041,
    )

    private val templateMeanX = (0 until 5).sumOf { TEMPLATE[2 * it] } / 5
    private val templateMeanY = (0 until 5).sumOf { TEMPLATE[2 * it + 1] } / 5
    private val templateSpread = (0 until 5).sumOf {
        val dx = TEMPLATE[2 * it] - templateMeanX
        val dy = TEMPLATE[2 * it + 1] - templateMeanY
        dx * dx + dy * dy
    }

    // Measured from the crop's outer edge, a pixel centre lies half a pixel further in than its index.
    private const val CENTRE = 0.5

    // Below this share of explained variance the landmarks say nothing about rotation, and the fitted
    // scale would blow the crop up without bound.
    private const val MIN_FIT = 1e-6

    /**
     * Writes the frame positions of the crop's outer top-left, top-right and bottom-left corners into
     * [outCorners], for [landmarksPx] given as five x,y pairs in frame pixels.
     */
    fun cropCorners(landmarksPx: FloatArray, outCorners: FloatArray) {
        require(landmarksPx.size >= 10) { "landmarksPx needs 10 values, has ${landmarksPx.size}" }
        require(outCorners.size >= 6) { "outCorners needs room for 6 values, has ${outCorners.size}" }
        var meanX = 0.0
        var meanY = 0.0
        for (i in 0 until 5) {
            meanX += landmarksPx[2 * i]
            meanY += landmarksPx[2 * i + 1]
        }
        meanX /= 5
        meanY /= 5
        require(meanX.isFinite() && meanY.isFinite()) { "landmarksPx holds a value that is not finite" }

        var dot = 0.0
        var cross = 0.0
        var spread = 0.0
        for (i in 0 until 5) {
            val sx = landmarksPx[2 * i] - meanX
            val sy = landmarksPx[2 * i + 1] - meanY
            val dx = TEMPLATE[2 * i] - templateMeanX
            val dy = TEMPLATE[2 * i + 1] - templateMeanY
            dot += sx * dx + sy * dy
            cross += sx * dy - sy * dx
            spread += sx * sx + sy * sy
        }

        // The crop-to-frame map is frame = mean + [[c, s], [-s, c]] * (crop - templateMean).
        val fit = dot * dot + cross * cross
        val c: Double
        val s: Double
        if (fit > 0.0 && fit >= MIN_FIT * spread * templateSpread) {
            c = dot * spread / fit
            s = cross * spread / fit
        } else {
            // Landmarks that coincide or contradict the template: an upright crop sized from their spread.
            c = sqrt(spread / templateSpread)
            s = 0.0
        }

        val left = -(templateMeanX + CENTRE)
        val top = -(templateMeanY + CENTRE)
        val right = left + CROP_SIZE
        val bottom = top + CROP_SIZE
        outCorners[0] = (meanX + c * left + s * top).toFloat()
        outCorners[1] = (meanY - s * left + c * top).toFloat()
        outCorners[2] = (meanX + c * right + s * top).toFloat()
        outCorners[3] = (meanY - s * right + c * top).toFloat()
        outCorners[4] = (meanX + c * left + s * bottom).toFloat()
        outCorners[5] = (meanY - s * left + c * bottom).toFloat()
    }
}
