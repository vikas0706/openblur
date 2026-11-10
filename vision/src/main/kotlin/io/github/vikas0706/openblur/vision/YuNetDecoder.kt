package io.github.vikas0706.openblur.vision

import io.github.vikas0706.openblur.core.Detection
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns the twelve YuNet outputs for one tensor size into detections, following OpenCV's FaceDetectorYN.
 * All working memory is allocated here once; [decode] allocates only the list it returns.
 */
internal class YuNetDecoder(inputWidth: Int, inputHeight: Int) {
    private val cols = IntArray(LEVELS) { inputWidth / STRIDES[it] }
    private val rows = IntArray(LEVELS) { inputHeight / STRIDES[it] }

    /** Anchors per stride level: the element count of that level's cls and obj outputs. */
    val anchorCounts = IntArray(LEVELS) { cols[it] * rows[it] }

    private val capacity = anchorCounts.sum()
    private val scores = FloatArray(capacity)
    private val boxes = FloatArray(capacity * 4)
    private val anchors = IntArray(capacity)
    private val order = LongArray(capacity)
    private val kept = IntArray(minOf(capacity, TOP_K))

    /**
     * [cls] and [obj] hold one array per level; [bbox] and [kps] are read by absolute index, four and ten
     * floats per anchor. Anchors are row-major within a level.
     */
    fun decode(
        cls: Array<FloatArray>,
        obj: Array<FloatArray>,
        bbox: Array<FloatBuffer>,
        kps: Array<FloatBuffer>,
        contentWidth: Int,
        contentHeight: Int,
    ): List<Detection> {
        val count = collectCandidates(cls, obj, bbox, kps, contentWidth.toFloat(), contentHeight.toFloat())
        if (count == 0) return emptyList()
        sortAscending(order, count)
        val keptCount = suppress(count)

        val result = ArrayList<Detection>(keptCount)
        val contentW = contentWidth.toFloat()
        val contentH = contentHeight.toFloat()
        for (k in 0 until keptCount) {
            val i = kept[k]
            val level = anchors[i] ushr LEVEL_SHIFT
            val index = anchors[i] and INDEX_MASK
            val stride = STRIDES[level]
            val col = index % cols[level]
            val row = index / cols[level]
            val points = kps[level]
            val landmarks = FloatArray(10)
            var finite = true
            for (n in 0 until 5) {
                val x = (points.get(index * 10 + 2 * n) + col) * stride / contentW
                val y = (points.get(index * 10 + 2 * n + 1) + row) * stride / contentH
                if (!x.isFinite() || !y.isFinite()) finite = false
                landmarks[2 * n] = x
                landmarks[2 * n + 1] = y
            }
            if (!finite) continue
            val x1 = boxes[4 * i]
            val y1 = boxes[4 * i + 1]
            val x2 = boxes[4 * i + 2]
            val y2 = boxes[4 * i + 3]
            result.add(
                Detection(
                    cx = (x1 + x2) * 0.5f / contentW,
                    cy = (y1 + y2) * 0.5f / contentH,
                    w = (x2 - x1) / contentW,
                    h = (y2 - y1) / contentH,
                    score = scores[i],
                    landmarks = landmarks,
                ),
            )
        }
        return result
    }

    /** Fills the candidate arrays and sort keys; returns how many anchors passed. Boxes are in tensor pixels. */
    private fun collectCandidates(
        cls: Array<FloatArray>,
        obj: Array<FloatArray>,
        bbox: Array<FloatBuffer>,
        kps: Array<FloatBuffer>,
        contentW: Float,
        contentH: Float,
    ): Int {
        var count = 0
        for (level in 0 until LEVELS) {
            val stride = STRIDES[level]
            val levelCols = cols[level]
            val levelCls = cls[level]
            val levelObj = obj[level]
            val levelBox = bbox[level]
            for (index in 0 until anchorCounts[level]) {
                var c = levelCls[index]
                if (c < 0f) c = 0f else if (c > 1f) c = 1f
                var o = levelObj[index]
                if (o < 0f) o = 0f else if (o > 1f) o = 1f
                // Written as a negated >= so a NaN score is rejected too.
                if (!(c * o >= FLOOR_SQUARED)) continue
                val score = sqrt(c * o)
                if (!(score >= FaceDetectorFactory.SCORE_FLOOR)) continue

                val cx = (index % levelCols + levelBox.get(index * 4)) * stride
                val cy = (index / levelCols + levelBox.get(index * 4 + 1)) * stride
                // A centre in the padding belongs to no part of the picture.
                if (!(cx >= 0f && cx <= contentW && cy >= 0f && cy <= contentH)) continue
                val halfW = exp(levelBox.get(index * 4 + 2)) * stride * 0.5f
                val halfH = exp(levelBox.get(index * 4 + 3)) * stride * 0.5f
                val x1 = maxOf(cx - halfW, 0f)
                val y1 = maxOf(cy - halfH, 0f)
                val x2 = minOf(cx + halfW, contentW)
                val y2 = minOf(cy + halfH, contentH)
                if (!(x2 > x1 && y2 > y1)) continue
                // Rejected here, not after suppression, so an anchor that is dropped cannot hide a real face.
                if (!landmarksFinite(kps[level], index)) continue

                scores[count] = score
                boxes[4 * count] = x1
                boxes[4 * count + 1] = y1
                boxes[4 * count + 2] = x2
                boxes[4 * count + 3] = y2
                anchors[count] = (level shl LEVEL_SHIFT) or index
                // Scores are positive, so their bit patterns sort like the floats. The inverted index in the
                // low half makes the earlier anchor win a tie, as OpenCV's stable sort does.
                order[count] = (score.toRawBits().toLong() shl 32) or (INDEX_BITS - count)
                count++
            }
        }
        return count
    }

    private fun landmarksFinite(points: FloatBuffer, index: Int): Boolean {
        for (k in index * 10 until index * 10 + 10) {
            if (!points.get(k).isFinite()) return false
        }
        return true
    }

    /** Greedy suppression over the best [TOP_K] candidates, strongest first. Returns the number kept. */
    private fun suppress(count: Int): Int {
        var keptCount = 0
        val considered = minOf(count, TOP_K)
        for (rank in 0 until considered) {
            val i = (INDEX_BITS - (order[count - 1 - rank] and INDEX_BITS)).toInt()
            val x1 = boxes[4 * i]
            val y1 = boxes[4 * i + 1]
            val x2 = boxes[4 * i + 2]
            val y2 = boxes[4 * i + 3]
            val area = (x2 - x1) * (y2 - y1)
            var duplicate = false
            for (k in 0 until keptCount) {
                val j = kept[k]
                val overlapW = minOf(x2, boxes[4 * j + 2]) - maxOf(x1, boxes[4 * j])
                if (overlapW <= 0f) continue
                val overlapH = minOf(y2, boxes[4 * j + 3]) - maxOf(y1, boxes[4 * j + 1])
                if (overlapH <= 0f) continue
                val intersection = overlapW * overlapH
                val otherArea = (boxes[4 * j + 2] - boxes[4 * j]) * (boxes[4 * j + 3] - boxes[4 * j + 1])
                if (intersection > NMS_IOU * (area + otherArea - intersection)) {
                    duplicate = true
                    break
                }
            }
            if (!duplicate) kept[keptCount++] = i
        }
        return keptCount
    }

    companion object {
        const val LEVELS = 3
        val STRIDES = intArrayOf(8, 16, 32)

        /** OpenCV's defaults: boxes overlapping a stronger one by more than this are duplicates. */
        const val NMS_IOU = 0.3f
        const val TOP_K = 5000

        private const val FLOOR_SQUARED = FaceDetectorFactory.SCORE_FLOOR * FaceDetectorFactory.SCORE_FLOOR
        private const val LEVEL_SHIFT = 28
        private const val INDEX_MASK = (1 shl LEVEL_SHIFT) - 1
        private const val INDEX_BITS = 0xFFFFFFFFL

        /** Heapsort: in place and without the scratch array a library sort may allocate. */
        fun sortAscending(keys: LongArray, count: Int) {
            for (start in count / 2 - 1 downTo 0) siftDown(keys, start, count)
            for (end in count - 1 downTo 1) {
                val largest = keys[0]
                keys[0] = keys[end]
                keys[end] = largest
                siftDown(keys, 0, end)
            }
        }

        private fun siftDown(keys: LongArray, start: Int, end: Int) {
            val value = keys[start]
            var parent = start
            while (true) {
                var child = 2 * parent + 1
                if (child >= end) break
                if (child + 1 < end && keys[child + 1] > keys[child]) child++
                if (keys[child] <= value) break
                keys[parent] = keys[child]
                parent = child
            }
            keys[parent] = value
        }
    }
}
