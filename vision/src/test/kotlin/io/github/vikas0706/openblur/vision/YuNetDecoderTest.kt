package io.github.vikas0706.openblur.vision

import io.github.vikas0706.openblur.core.Detection
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.ln
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decode and suppression on hand-built network outputs, so no model or native library is involved. */
class YuNetDecoderTest {
    /** Network outputs for a [width] x [height] tensor in which every anchor starts out silent. */
    private class Outputs(val width: Int, val height: Int) {
        val decoder = YuNetDecoder(width, height)
        val cls = Array(3) { FloatArray(decoder.anchorCounts[it]) }
        val obj = Array(3) { FloatArray(decoder.anchorCounts[it]) }
        val bbox = Array(3) { FloatBuffer.allocate(decoder.anchorCounts[it] * 4) }
        val kps = Array(3) { FloatBuffer.allocate(decoder.anchorCounts[it] * 10) }

        fun index(level: Int, col: Int, row: Int): Int = row * (width / YuNetDecoder.STRIDES[level]) + col

        /** Makes the anchor at ([col], [row]) of [level] report a face centred at ([cx], [cy]) tensor pixels. */
        fun face(level: Int, col: Int, row: Int, score: Float, cx: Float, cy: Float, w: Float, h: Float) {
            val stride = YuNetDecoder.STRIDES[level]
            val i = index(level, col, row)
            cls[level][i] = score
            obj[level][i] = score
            bbox[level].put(i * 4, cx / stride - col)
            bbox[level].put(i * 4 + 1, cy / stride - row)
            bbox[level].put(i * 4 + 2, ln(w / stride))
            bbox[level].put(i * 4 + 3, ln(h / stride))
            for (n in 0 until 5) {
                kps[level].put(i * 10 + 2 * n, (cx + LANDMARK_X[n] * w) / stride - col)
                kps[level].put(i * 10 + 2 * n + 1, (cy + LANDMARK_Y[n] * h) / stride - row)
            }
        }

        fun decode(contentWidth: Int = width, contentHeight: Int = height): List<Detection> =
            decoder.decode(cls, obj, bbox, kps, contentWidth, contentHeight)
    }

    @Test
    fun decodesBoxScoreAndLandmarks() {
        val outputs = Outputs(128, 96)
        outputs.face(level = 1, col = 3, row = 2, score = 0.9f, cx = 52f, cy = 40f, w = 32f, h = 48f)

        val found = outputs.decode().single()

        assertEquals(52f / 128, found.cx, EPS)
        assertEquals(40f / 96, found.cy, EPS)
        assertEquals(32f / 128, found.w, EPS)
        assertEquals(48f / 96, found.h, EPS)
        assertEquals(0.9f, found.score, EPS)
        val expected = FloatArray(10) {
            val n = it / 2
            if (it % 2 == 0) (52f + LANDMARK_X[n] * 32f) / 128 else (40f + LANDMARK_Y[n] * 48f) / 96
        }
        assertArrayEquals(expected, found.landmarks, EPS)
    }

    @Test
    fun scoreIsTheGeometricMeanOfClampedClassAndObjectness() {
        val outputs = Outputs(64, 64)
        outputs.face(0, 1, 1, 0.9f, 12f, 12f, 8f, 8f)
        outputs.cls[0][outputs.index(0, 1, 1)] = 0.5f
        outputs.obj[0][outputs.index(0, 1, 1)] = 0.32f
        outputs.face(0, 5, 5, 0.9f, 44f, 44f, 8f, 8f)
        outputs.cls[0][outputs.index(0, 5, 5)] = 4f
        outputs.obj[0][outputs.index(0, 5, 5)] = 0.25f
        outputs.face(1, 1, 3, 0.9f, 24f, 56f, 8f, 8f)
        outputs.cls[1][outputs.index(1, 1, 3)] = -3f

        val found = outputs.decode()

        assertFloats(floatArrayOf(0.5f, 0.4f), found.map { it.score })
    }

    @Test
    fun keepsCandidatesDownToTheScoreFloor() {
        val outputs = Outputs(64, 64)
        outputs.face(0, 1, 1, FaceDetectorFactory.SCORE_FLOOR + 0.01f, 12f, 12f, 8f, 8f)
        outputs.face(0, 5, 5, FaceDetectorFactory.SCORE_FLOOR - 0.01f, 44f, 44f, 8f, 8f)

        val found = outputs.decode()

        assertEquals(1, found.size)
        assertEquals(12f / 64, found[0].cx, EPS)
    }

    @Test
    fun suppressesTheWeakerOfTwoOverlappingBoxesAndSortsTheRest() {
        val outputs = Outputs(128, 128)
        // Two anchors on one face (IoU about 0.75), one face well apart, one that overlaps them by under 0.3.
        outputs.face(0, 5, 5, 0.7f, 44f, 44f, 20f, 20f)
        outputs.face(0, 6, 5, 0.8f, 46f, 45f, 20f, 20f)
        outputs.face(1, 5, 5, 0.95f, 90f, 90f, 24f, 24f)
        outputs.face(0, 7, 7, 0.5f, 60f, 58f, 20f, 20f)

        val found = outputs.decode()

        assertFloats(floatArrayOf(0.95f, 0.8f, 0.5f), found.map { it.score })
        assertEquals(46f / 128, found[1].cx, EPS)
    }

    @Test
    fun equalScoresKeepAnchorOrder() {
        val outputs = Outputs(128, 128)
        outputs.face(1, 6, 6, 0.6f, 100f, 100f, 10f, 10f)
        outputs.face(0, 9, 2, 0.6f, 76f, 20f, 10f, 10f)
        outputs.face(0, 2, 2, 0.6f, 20f, 20f, 10f, 10f)

        val found = outputs.decode()

        assertFloats(floatArrayOf(20f / 128, 76f / 128, 100f / 128), found.map { it.cx })
    }

    @Test
    fun dropsCentresInThePaddingAndClampsBoxesToTheContent() {
        val outputs = Outputs(128, 96)
        outputs.face(0, 13, 5, 0.9f, 110f, 40f, 20f, 20f)
        outputs.face(0, 11, 7, 0.8f, 95f, 60f, 30f, 30f)
        outputs.face(0, 2, 10, 0.7f, 20f, 85f, 20f, 20f)

        val found = outputs.decode(contentWidth = 100, contentHeight = 70).single()

        assertEquals(0.8f, found.score, EPS)
        assertEquals(90f / 100, found.cx, EPS)
        assertEquals(20f / 100, found.w, EPS)
        assertEquals(57.5f / 70, found.cy, EPS)
        assertEquals(25f / 70, found.h, EPS)
        // Landmarks keep their true positions: clamping them would bend the face geometry used for alignment.
        assertEquals((95f + LANDMARK_X[1] * 30f) / 100, found.landmarks[2], EPS)
    }

    @Test
    fun ignoresAnchorsWithValuesThatAreNotFinite() {
        val outputs = Outputs(64, 64)
        outputs.face(0, 1, 1, 0.9f, 12f, 12f, 8f, 8f)
        outputs.cls[0][outputs.index(0, 1, 1)] = Float.NaN
        outputs.face(0, 5, 1, 0.9f, 44f, 12f, 8f, 8f)
        outputs.bbox[0].put(outputs.index(0, 5, 1) * 4, Float.NaN)
        outputs.face(0, 1, 5, 0.9f, 12f, 44f, 8f, 8f)
        outputs.kps[0].put(outputs.index(0, 1, 5) * 10 + 3, Float.POSITIVE_INFINITY)
        outputs.face(1, 2, 2, 0.8f, 40f, 40f, 8f, 8f)
        outputs.bbox[1].put(outputs.index(1, 2, 2) * 4 + 2, 200f)

        val found = outputs.decode().single()

        assertEquals(0.8f, found.score, EPS)
        // exp(200) overflows to infinity, and the box is then just the full width of the content.
        assertEquals(0.5f, found.cx, EPS)
        assertEquals(1f, found.w, EPS)
    }

    @Test
    fun anAnchorWithLandmarksThatAreNotFiniteSuppressesNothing() {
        val outputs = Outputs(128, 128)
        outputs.face(0, 5, 5, 0.9f, 44f, 44f, 20f, 20f)
        outputs.kps[0].put(outputs.index(0, 5, 5) * 10 + 4, Float.NaN)
        outputs.face(0, 6, 5, 0.8f, 46f, 45f, 20f, 20f)

        val found = outputs.decode().single()

        assertEquals(0.8f, found.score, EPS)
        assertEquals(46f / 128, found.cx, EPS)
    }

    @Test
    fun considersOnlyTheStrongestFiveThousandCandidates() {
        val outputs = Outputs(640, 640)
        val total = outputs.decoder.anchorCounts.sum()
        val ranks = (0 until total).shuffled(Random(7))
        var next = 0
        for (level in 0 until 3) {
            val stride = YuNetDecoder.STRIDES[level]
            for (row in 0 until 640 / stride) {
                for (col in 0 until 640 / stride) {
                    val score = 0.2f + 0.7f * ranks[next++] / total
                    outputs.face(level, col, row, score, (col + 0.5f) * stride, (row + 0.5f) * stride, 2f, 2f)
                }
            }
        }

        val found = outputs.decode()

        assertEquals(YuNetDecoder.TOP_K, found.size)
        val cutoff = 0.2f + 0.7f * (total - YuNetDecoder.TOP_K) / total
        assertTrue(found.all { it.score >= cutoff - EPS })
        assertTrue(found.zipWithNext().all { (a, b) -> a.score >= b.score })
    }

    @Test
    fun aDecoderCarriesNothingOverBetweenFrames() {
        val outputs = Outputs(64, 64)
        outputs.face(0, 1, 1, 0.9f, 12f, 12f, 8f, 8f)
        outputs.face(0, 5, 5, 0.8f, 44f, 44f, 8f, 8f)
        assertEquals(2, outputs.decode().size)

        outputs.cls[0].fill(0f)
        outputs.obj[0].fill(0f)
        assertTrue(outputs.decode().isEmpty())

        outputs.face(1, 2, 2, 0.7f, 40f, 40f, 8f, 8f)
        assertFloats(floatArrayOf(0.7f), outputs.decode().map { it.score })
    }

    @Test
    fun heapsortOrdersLikeTheLibrarySort() {
        val random = Random(3)
        for (size in intArrayOf(0, 1, 2, 3, 7, 64, 1000)) {
            val keys = LongArray(size + 5) { random.nextInt(50).toLong() - 25 }
            val expected = keys.copyOf()
            java.util.Arrays.sort(expected, 0, size)

            YuNetDecoder.sortAscending(keys, size)

            assertArrayEquals("size $size", expected, keys)
        }
    }

    private fun assertFloats(expected: FloatArray, actual: List<Float>) {
        assertArrayEquals(expected, actual.toFloatArray(), EPS)
    }

    private companion object {
        const val EPS = 1e-5f

        // Offsets from the box centre as a share of its size: right eye, left eye, nose, right and left mouth corner.
        val LANDMARK_X = floatArrayOf(-0.2f, 0.2f, 0f, -0.15f, 0.15f)
        val LANDMARK_Y = floatArrayOf(-0.15f, -0.15f, 0.05f, 0.25f, 0.25f)
    }
}
