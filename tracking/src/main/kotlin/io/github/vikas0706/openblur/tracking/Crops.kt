package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.FaceEmbedder
import io.github.vikas0706.openblur.core.FrameCropper
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Pictures of the detections of one run. The tracker asks only for the ones it will keep. */
internal interface CropSource {
    /** Fills [out] with the square thumbnail of detection [index]. False when no picture is available. */
    fun thumbnail(index: Int, out: ByteArray): Boolean

    /** Fills [out] with the crop the embedder expects. False when the face could not be aligned. */
    fun aligned(index: Int, out: ByteArray): Boolean
}

/** The embedder, switched off for the rest of the scan the first time it fails. */
internal class EmbeddingState(val embedder: FaceEmbedder?) {
    val cropSize: Int = embedder?.cropSize ?: 0
    val vectorSize: Int = embedder?.embeddingSize ?: 0
    val cropBytes: Int get() = cropSize * cropSize * 3
    var active: Boolean = embedder != null
        private set

    fun fail() {
        active = false
    }
}

/** Reusable byte arrays of one size. */
internal class BytePool(private val size: Int) {
    private var free = arrayOfNulls<ByteArray>(8)
    private var count = 0

    fun take(): ByteArray {
        if (count == 0) return ByteArray(size)
        val buffer = free[--count]!!
        free[count] = null
        return buffer
    }

    fun give(buffer: ByteArray) {
        if (count == MAX_KEPT) return
        if (count == free.size) free = free.copyOf(count * 2)
        free[count++] = buffer
    }

    private companion object {
        const val MAX_KEPT = 64
    }
}

/** Crops straight from the frame that is being analyzed. */
internal class LiveCropSource(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val embedding: EmbeddingState,
) : CropSource {
    private var cropper: FrameCropper? = null
    private var batch: DetectionBatch? = null
    private val corners = FloatArray(6)
    private val landmarksPx = FloatArray(10)

    fun bind(cropper: FrameCropper, batch: DetectionBatch) {
        this.cropper = cropper
        this.batch = batch
    }

    /** The cropper is only valid during the onFrame call that supplied it. */
    fun unbind() {
        cropper = null
        batch = null
    }

    override fun thumbnail(index: Int, out: ByteArray): Boolean {
        val cropper = cropper ?: return false
        val box = batch!!.box
        val size = Tuning.THUMBNAIL_SIZE
        val shortSide = min(frameWidth, frameHeight).toFloat()
        var side = Tuning.THUMBNAIL_FACE_SCALE * max(box[index * 4 + 2] * frameWidth, box[index * 4 + 3] * frameHeight)
        side = side.coerceIn(min(1f, shortSide), shortSide)
        val half = side * 0.5f
        val cx = (box[index * 4] * frameWidth).coerceIn(half, frameWidth - half)
        val cy = (box[index * 4 + 1] * frameHeight).coerceIn(half, frameHeight - half)
        val reach = half
        corners[0] = (cx - reach) / frameWidth
        corners[1] = (cy - reach) / frameHeight
        corners[2] = (cx + reach) / frameWidth
        corners[3] = corners[1]
        corners[4] = corners[0]
        corners[5] = (cy + reach) / frameHeight
        cropper.crop(corners, size, size, out)
        return true
    }

    override fun aligned(index: Int, out: ByteArray): Boolean {
        val cropper = cropper ?: return false
        val embedder = embedding.embedder
        if (embedder == null || !embedding.active) return false
        val landmarks = batch!!.landmarks
        for (k in 0 until 5) {
            landmarksPx[k * 2] = landmarks[index * 10 + k * 2] * frameWidth
            landmarksPx[k * 2 + 1] = landmarks[index * 10 + k * 2 + 1] * frameHeight
        }
        try {
            embedder.alignedCropCorners(landmarksPx, corners)
        } catch (e: RuntimeException) {
            embedding.fail()
            return false
        }
        for (k in 0 until 6) if (!corners[k].isFinite()) return false
        // A crop narrower than a pixel is a degenerate fit and would embed one flat color.
        if (hypot(corners[2] - corners[0], corners[3] - corners[1]) < 1f) return false
        if (hypot(corners[4] - corners[0], corners[5] - corners[1]) < 1f) return false
        for (k in 0 until 3) {
            corners[k * 2] /= frameWidth
            corners[k * 2 + 1] /= frameHeight
        }
        cropper.crop(corners, embedding.cropSize, embedding.cropSize, out)
        return true
    }
}

/**
 * A detector run held back until a possible scene cut before it is decided.
 * Its pictures are taken while the frame is still available, for the strongest detections only.
 */
internal class QueuedRun(frameWidth: Int, frameHeight: Int) : CropSource {
    val batch = DetectionBatch(frameWidth, frameHeight)
    var run = 0
    var ptsUs = 0L

    /** Earliest frame a cut seen at this run could have happened at: the first frame after the previous run. */
    var earliestUs = 0L
    var cutCandidate = false

    private val thumbs = arrayOfNulls<ByteArray>(MAX_PICTURES)
    private val aligned = arrayOfNulls<ByteArray>(MAX_PICTURES)
    private val slotOf = IntArray(Tuning.MAX_DETECTIONS)
    private var slots = 0

    fun capture(live: LiveCropSource, embedding: EmbeddingState, thumbPool: BytePool, cropPool: BytePool) {
        val n = batch.count
        slots = 0
        for (i in 0 until n) {
            slotOf[i] = -1
            if (slots == MAX_PICTURES || batch.score[i] < Tuning.STRONG_SCORE) continue
            val slot = slots++
            slotOf[i] = slot
            val thumb = thumbPool.take()
            if (live.thumbnail(i, thumb)) thumbs[slot] = thumb else thumbPool.give(thumb)
            if (embedding.active && batch.embeddable(i)) {
                val crop = cropPool.take()
                if (live.aligned(i, crop)) aligned[slot] = crop else cropPool.give(crop)
            }
        }
    }

    fun release(thumbPool: BytePool, cropPool: BytePool) {
        for (slot in 0 until slots) {
            thumbs[slot]?.let { thumbPool.give(it) }
            aligned[slot]?.let { cropPool.give(it) }
            thumbs[slot] = null
            aligned[slot] = null
        }
        slots = 0
    }

    override fun thumbnail(index: Int, out: ByteArray): Boolean = copy(thumbs, index, out)

    override fun aligned(index: Int, out: ByteArray): Boolean = copy(aligned, index, out)

    private fun copy(pictures: Array<ByteArray?>, index: Int, out: ByteArray): Boolean {
        val slot = slotOf[index]
        if (slot < 0 || slot >= slots) return false
        val picture = pictures[slot] ?: return false
        System.arraycopy(picture, 0, out, 0, picture.size)
        return true
    }

    private companion object {
        const val MAX_PICTURES = 32
    }
}
