package io.github.vikas0706.openblur.core

import java.nio.ByteBuffer

/**
 * One face found in one frame, normalized to the frame content: x right, y down, 0..1.
 * [landmarks] is five x,y pairs: right eye, left eye, nose tip, right mouth corner, left mouth corner,
 * named from the subject's side (the right eye is on the viewer's left for a frontal face).
 */
class Detection(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val score: Float,
    val landmarks: FloatArray,
)

/** Not thread-safe: one instance is used from one thread. */
interface FaceDetector : AutoCloseable {
    /** Tensor size this instance was built for. Both are multiples of 32. */
    val inputWidth: Int
    val inputHeight: Int

    /**
     * [rgba] holds inputWidth * inputHeight * 4 bytes starting at its position, row 0 at the top.
     * The frame fills the top-left [contentWidth] x [contentHeight] pixels and the rest is padding.
     * Results are normalized to the content, sorted by descending score, already de-duplicated,
     * and include weak candidates down to [FaceDetectorFactory.SCORE_FLOOR] so a tracker can use them.
     */
    fun detect(rgba: ByteBuffer, contentWidth: Int, contentHeight: Int): List<Detection>
}

interface FaceDetectorFactory {
    /** [inputWidth] and [inputHeight] must be positive multiples of 32. */
    fun create(inputWidth: Int, inputHeight: Int): FaceDetector

    companion object {
        const val SCORE_FLOOR = 0.15f
    }
}

/** Turns an aligned face crop into a unit-length vector; the dot product of two vectors is their similarity. */
interface FaceEmbedder : AutoCloseable {
    /** Side of the square RGB crop [embed] expects, in pixels. */
    val cropSize: Int

    val embeddingSize: Int

    /**
     * Writes into [outCorners] the frame positions, in pixels, that the crop's top-left, top-right and
     * bottom-left corners must sample from so the face lands where the model expects it.
     * [landmarksPx] uses the [Detection.landmarks] order, in frame pixels.
     */
    fun alignedCropCorners(landmarksPx: FloatArray, outCorners: FloatArray)

    /** [rgb] holds cropSize * cropSize * 3 bytes, row 0 at the top. Returns a new L2-normalized vector. */
    fun embed(rgb: ByteArray): FloatArray
}
