package io.github.vikas0706.openblur.core

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** How hard the scan looks. The defaults are the standard scan. */
data class AnalysisOptions(
    /** Long side of the detector input in pixels. Never larger than the frame's own long side. */
    val detectorLongSide: Int = 640,
    /** Upper bound on detector runs per second of video; frames in between are interpolated. */
    val maxDetectionsPerSecond: Float = 30f,
    /** When false, every track becomes its own person and no embedding model runs. */
    val groupPeople: Boolean = true,
) {
    companion object {
        val STANDARD = AnalysisOptions()
        val THOROUGH = AnalysisOptions(detectorLongSide = 1280, maxDetectionsPerSecond = 60f)
    }
}

/** Samples the current full-resolution frame. Valid only inside the [FaceAnalyzer.onFrame] call that received it. */
interface FrameCropper {
    /**
     * Fills [outRgb] (outWidth * outHeight * 3 bytes, row 0 at the top) with a bilinear resample of the frame.
     * [corners] is six normalized frame coordinates (x right, y down): the outer top-left, top-right and
     * bottom-left corners of the output square. Output pixel (i, j) samples at tl + ((i + 0.5) / outWidth) * (tr - tl)
     * + ((j + 0.5) / outHeight) * (bl - tl). Samples outside the frame clamp to its edge.
     */
    fun crop(corners: FloatArray, outWidth: Int, outHeight: Int, outRgb: ByteArray)
}

/** Keeps face thumbnails off the Java heap. Called on the analysis thread only. */
interface ThumbnailStore {
    /** Stores a square RGB thumbnail (row 0 at the top) and returns a handle that is never negative. */
    fun put(width: Int, height: Int, rgb: ByteArray): Int

    fun release(handle: Int)
}

/**
 * Consumes decoded frames in presentation order and builds face tracks. Single-threaded.
 * Call [onFrame] for every frame of the video, then [finish] exactly once; [close] may be called at any point.
 */
interface FaceAnalyzer : AutoCloseable {
    /** Size of the RGBA buffer [onFrame] expects, and the top-left part of it the frame must fill. */
    val inputWidth: Int
    val inputHeight: Int
    val contentWidth: Int
    val contentHeight: Int

    /** True when the frame at [ptsUs] will run the detector, so the caller must supply pixels for it. */
    fun wantsPixels(ptsUs: Long): Boolean

    /**
     * [ptsUs] is the frame's source presentation time and must increase from call to call.
     * [rgba] and [cropper] must be non-null when [wantsPixels] returned true for this time; [rgba] uses the
     * layout described on [FaceDetector.detect]. Both may be null otherwise, and are then ignored.
     */
    fun onFrame(ptsUs: Long, rgba: ByteBuffer?, cropper: FrameCropper?)

    /** Faces being followed right now plus faces already finished; for progress display only. */
    val facesSoFar: Int

    /** Closes open tracks, fills gaps, groups tracks into people. [durationUs] is the video's duration. */
    fun finish(durationUs: Long): AnalysisResult
}

interface FaceAnalyzerFactory {
    /**
     * [frameWidth] and [frameHeight] are the display-oriented frame size in pixels.
     * [embedder] may be null, which disables grouping the same way [AnalysisOptions.groupPeople] = false does.
     * The analyzer owns and closes the detector it creates; the caller keeps ownership of [embedder].
     */
    fun create(
        frameWidth: Int,
        frameHeight: Int,
        options: AnalysisOptions,
        detectors: FaceDetectorFactory,
        embedder: FaceEmbedder?,
        thumbnails: ThumbnailStore,
    ): FaceAnalyzer
}

/**
 * A face followed through one shot. Samples are sorted by time and there are at least two,
 * so the track covers the closed interval [startUs]..[endUs]; between samples values are interpolated linearly.
 */
class FaceTrack(
    val id: Int,
    val personId: Int,
    val timesUs: LongArray,
    /** Four floats per sample: cx, cy, w, h of the face itself (no padding), normalized, y down. */
    val boxes: FloatArray,
    /** Four floats per sample: right eye x, y, left eye x, y, normalized; NaN where unknown. */
    val eyes: FloatArray,
    /** One of the SAMPLE_ constants per sample. */
    val sampleKinds: ByteArray,
    /** Handle into the [ThumbnailStore] used for the scan, or -1. */
    val thumbnailHandle: Int,
    val meanScore: Float,
) {
    val sampleCount: Int get() = timesUs.size
    val startUs: Long get() = timesUs[0]
    val endUs: Long get() = timesUs[timesUs.size - 1]

    companion object {
        const val SAMPLE_DETECTED: Byte = 0
        const val SAMPLE_INTERPOLATED: Byte = 1
        const val SAMPLE_EXTRAPOLATED: Byte = 2
    }
}

/** Tracks judged to be the same person. Every track belongs to exactly one person. */
class Person(
    val id: Int,
    val trackIds: IntArray,
    /** The best face picture among the person's tracks, or -1. */
    val thumbnailHandle: Int,
    val firstSeenUs: Long,
    /** Sum of the durations of the person's tracks. */
    val visibleUs: Long,
)

class AnalysisResult(
    /** Display-oriented frame size the scan ran on, in pixels. */
    val frameWidth: Int,
    val frameHeight: Int,
    val durationUs: Long,
    val tracks: List<FaceTrack>,
    /** Sorted by [Person.firstSeenUs]. */
    val people: List<Person>,
    /** Times of the first frame after each detected scene cut, sorted. */
    val cutsUs: LongArray,
    val framesSeen: Int,
    val framesDetected: Int,
) {
    fun track(id: Int): FaceTrack? = tracks.firstOrNull { it.id == id }
    fun person(id: Int): Person? = people.firstOrNull { it.id == id }
}

/** Versioned binary form of a scan, so a project can be reopened without scanning again. */
interface AnalysisResultCodec {
    fun write(result: AnalysisResult, out: OutputStream)

    /** Throws [java.io.IOException] when the data is truncated, corrupt or from an unknown version. */
    fun read(input: InputStream): AnalysisResult
}
