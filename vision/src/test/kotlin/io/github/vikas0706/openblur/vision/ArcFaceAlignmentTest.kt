package io.github.vikas0706.openblur.vision

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcFaceAlignmentTest {
    /** The template in crop coordinates measured from the outer edge, mapped into a frame by a similarity. */
    private fun templateIn(scale: Double, angle: Double, shiftX: Double, shiftY: Double): FloatArray =
        FloatArray(10).also { out ->
            for (i in 0 until 5) {
                val u = ArcFaceAlignment.TEMPLATE[2 * i] + 0.5
                val v = ArcFaceAlignment.TEMPLATE[2 * i + 1] + 0.5
                val p = toFrame(u, v, scale, angle, shiftX, shiftY)
                out[2 * i] = p[0]
                out[2 * i + 1] = p[1]
            }
        }

    private fun toFrame(
        u: Double,
        v: Double,
        scale: Double,
        angle: Double,
        shiftX: Double,
        shiftY: Double,
    ): FloatArray =
        floatArrayOf(
            (scale * (cos(angle) * u - sin(angle) * v) + shiftX).toFloat(),
            (scale * (sin(angle) * u + cos(angle) * v) + shiftY).toFloat(),
        )

    private fun corners(landmarks: FloatArray): FloatArray =
        FloatArray(6).also { ArcFaceAlignment.cropCorners(landmarks, it) }

    /** Where a frame point lands in the crop, in pixels from its outer top-left corner. */
    private fun toCrop(corners: FloatArray, x: Float, y: Float): DoubleArray {
        val ax = (corners[2] - corners[0]).toDouble()
        val ay = (corners[3] - corners[1]).toDouble()
        val bx = (corners[4] - corners[0]).toDouble()
        val by = (corners[5] - corners[1]).toDouble()
        val px = (x - corners[0]).toDouble()
        val py = (y - corners[1]).toDouble()
        val det = ax * by - ay * bx
        return doubleArrayOf(112 * (px * by - py * bx) / det, 112 * (ax * py - ay * px) / det)
    }

    /** Sum of squared distances between the landmarks, mapped into the crop, and the template. */
    private fun misfit(corners: FloatArray, landmarks: FloatArray): Double {
        var sum = 0.0
        for (i in 0 until 5) {
            val p = toCrop(corners, landmarks[2 * i], landmarks[2 * i + 1])
            val dx = p[0] - (ArcFaceAlignment.TEMPLATE[2 * i] + 0.5)
            val dy = p[1] - (ArcFaceAlignment.TEMPLATE[2 * i + 1] + 0.5)
            sum += dx * dx + dy * dy
        }
        return sum
    }

    @Test
    fun templateLandmarksGiveTheWholeCropSquare() {
        val found = corners(templateIn(1.0, 0.0, 0.0, 0.0))

        assertArrayEquals(floatArrayOf(0f, 0f, 112f, 0f, 0f, 112f), found, 1e-3f)
    }

    @Test
    fun recoversAKnownScaleRotationAndShift() {
        val cases = listOf(
            doubleArrayOf(1.7, 0.0, 300.0, 200.0),
            doubleArrayOf(0.4, 0.5, 50.0, 900.0),
            doubleArrayOf(3.2, -1.2, 1500.0, 40.0),
            doubleArrayOf(2.0, Math.PI, 800.0, 800.0),
            doubleArrayOf(1.0, Math.PI / 2, 10.0, 10.0),
        )
        for ((scale, angle, shiftX, shiftY) in cases.map { it.toList() }) {
            val found = corners(templateIn(scale, angle, shiftX, shiftY))

            val expected = toFrame(0.0, 0.0, scale, angle, shiftX, shiftY) +
                toFrame(112.0, 0.0, scale, angle, shiftX, shiftY) +
                toFrame(0.0, 112.0, scale, angle, shiftX, shiftY)
            assertArrayEquals("scale $scale angle $angle", expected, found, 2e-2f)
        }
    }

    @Test
    fun cropsTheTemplateFaceWithoutMovingAPixel() {
        // With the landmarks already on the template, the outer-corner convention must reproduce the frame exactly.
        val frame = Picture.noise(112, 112, seed = 5)
        val found = corners(templateIn(1.0, 0.0, 0.0, 0.0))
        val normalized = FloatArray(6) { found[it] / 112f }
        val crop = ByteArray(112 * 112 * 3)

        BilinearCropper(frame).crop(normalized, 112, 112, crop)

        for (i in 0 until 112 * 112) {
            val p = frame.rgb[i]
            assertEquals("red at $i", (p shr 16) and 0xFF, crop[3 * i].toInt() and 0xFF)
            assertEquals("green at $i", (p shr 8) and 0xFF, crop[3 * i + 1].toInt() and 0xFF)
            assertEquals("blue at $i", p and 0xFF, crop[3 * i + 2].toInt() and 0xFF)
        }
    }

    @Test
    fun theCropIsAnUprightHandedSquareAndTheBestFit() {
        // A face that is not an exact copy of the template: wider eyes, a nose off centre, some tilt.
        val landmarks = templateIn(2.3, 0.3, 400.0, 250.0)
        landmarks[0] -= 9f
        landmarks[3] += 4f
        landmarks[4] += 7f
        landmarks[7] -= 5f
        val found = corners(landmarks)

        val ax = found[2] - found[0]
        val ay = found[3] - found[1]
        val bx = found[4] - found[0]
        val by = found[5] - found[1]
        assertEquals(hypot(ax, ay), hypot(bx, by), 1e-2f)
        assertEquals(0f, (ax * bx + ay * by) / (ax * ax + ay * ay), 1e-4f)
        assertTrue("a mirrored crop would flip the face", ax * by - ay * bx > 0f)

        // No nearby square explains the landmarks better than the fitted one.
        val best = misfit(found, landmarks)
        val centreX = found[2] / 2 + found[4] / 2
        val centreY = found[3] / 2 + found[5] / 2
        for (scale in doubleArrayOf(0.99, 1.0, 1.01)) {
            for (angle in doubleArrayOf(-0.01, 0.0, 0.01)) {
                for (shift in listOf(0f to 0f, 0.5f to 0f, -0.5f to 0f, 0f to 0.5f, 0f to -0.5f)) {
                    val moved = FloatArray(6)
                    for (k in 0 until 3) {
                        val dx = (found[2 * k] - centreX).toDouble()
                        val dy = (found[2 * k + 1] - centreY).toDouble()
                        val turnedX = scale * (cos(angle) * dx - sin(angle) * dy)
                        val turnedY = scale * (sin(angle) * dx + cos(angle) * dy)
                        moved[2 * k] = (centreX + turnedX).toFloat() + shift.first
                        moved[2 * k + 1] = (centreY + turnedY).toFloat() + shift.second
                    }
                    assertTrue(
                        "scale $scale angle $angle shift $shift fits better than the result",
                        misfit(moved, landmarks) >= best - 1e-3,
                    )
                }
            }
        }
    }

    @Test
    fun landmarksThatFixNoOrientationStillGiveAFiniteUprightCrop() {
        val coincident = FloatArray(10) { if (it % 2 == 0) 320f else 240f }
        val collapsed = corners(coincident)
        assertArrayEquals(floatArrayOf(320f, 240f, 320f, 240f, 320f, 240f), collapsed, 1e-3f)

        // Treating points as complex numbers, the fit is the sum of conj(landmark) * template. Moving only the
        // eyes and nose, by 1, w and -(1 + w) with conj(w) = -(eyeR - nose) / (eyeL - nose), makes that sum zero.
        val t = ArcFaceAlignment.TEMPLATE
        val aRe = t[0] - t[4]
        val aIm = t[1] - t[5]
        val bRe = t[2] - t[4]
        val bIm = t[3] - t[5]
        val wRe = -(aRe * bRe + aIm * bIm) / (bRe * bRe + bIm * bIm)
        val wIm = (aIm * bRe - aRe * bIm) / (bRe * bRe + bIm * bIm)
        val contradictory = floatArrayOf(
            (200 + 30 * 1.0).toFloat(), 200f,
            (200 + 30 * wRe).toFloat(), (200 + 30 * wIm).toFloat(),
            (200 - 30 * (1.0 + wRe)).toFloat(), (200 - 30 * wIm).toFloat(),
            200f, 200f,
            200f, 200f,
        )
        val upright = corners(contradictory)
        assertTrue(upright.all { it.isFinite() })
        assertTrue(abs(upright[3] - upright[1]) < 1e-3f && upright[2] > upright[0])
        assertTrue(abs(upright[4] - upright[0]) < 1e-3f && upright[5] > upright[1])
    }

    @Test
    fun rejectsLandmarksThatAreNotFiniteOrArraysThatAreTooShort() {
        val good = templateIn(1.0, 0.0, 0.0, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            ArcFaceAlignment.cropCorners(good.copyOf().also { it[4] = Float.NaN }, FloatArray(6))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArcFaceAlignment.cropCorners(good.copyOf().also { it[9] = Float.POSITIVE_INFINITY }, FloatArray(6))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ArcFaceAlignment.cropCorners(FloatArray(8), FloatArray(6))
        }
        assertThrows(IllegalArgumentException::class.java) { ArcFaceAlignment.cropCorners(good, FloatArray(4)) }
    }
}
