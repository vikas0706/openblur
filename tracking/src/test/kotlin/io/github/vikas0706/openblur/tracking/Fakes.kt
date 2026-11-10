package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.Detection
import io.github.vikas0706.openblur.core.FaceAnalyzer
import io.github.vikas0706.openblur.core.FaceDetector
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import io.github.vikas0706.openblur.core.FaceEmbedder
import io.github.vikas0706.openblur.core.FaceTrack
import io.github.vikas0706.openblur.core.FrameCropper
import io.github.vikas0706.openblur.core.ThumbnailStore
import java.nio.ByteBuffer
import kotlin.math.hypot
import org.junit.Assert.assertEquals

/** A face as a test places it: who it is, where it is in frame pixels, and how sure the detector is. */
internal class Face(
    val identity: Int,
    val x: Float,
    val y: Float,
    val size: Float,
    val score: Float = 0.9f,
    val landmarks: Boolean = true,
    /** Nose offset as a fraction of the eye distance; 0 is frontal. */
    val turn: Float = 0f,
) {
    fun detection(frameWidth: Int, frameHeight: Int): Detection {
        val eye = 0.42f * size
        val eyeY = y - 0.12f * size
        val mouthY = eyeY + 1.15f * eye
        val px = floatArrayOf(
            x - eye / 2, eyeY,
            x + eye / 2, eyeY,
            x + turn * eye, y + 0.08f * size,
            x - 0.17f * size, mouthY,
            x + 0.17f * size, mouthY,
        )
        val normalized = if (landmarks) FloatArray(10) { if (it % 2 == 0) px[it] / frameWidth else px[it] / frameHeight } else FloatArray(0)
        return Detection(x / frameWidth, y / frameHeight, size / frameWidth, size / frameHeight, score, normalized)
    }
}

/** Returns whatever the test staged for the next call, or asks [script] by call index. */
internal class ScriptedDetectors : FaceDetectorFactory {
    var next: List<Detection> = emptyList()
    var script: ((Int) -> List<Detection>)? = null
    var calls = 0
    var created = 0
    var closed = 0
    var inputWidth = 0
    var inputHeight = 0
    var lastContentWidth = 0
    var lastContentHeight = 0
    var failOnCall = -1

    override fun create(inputWidth: Int, inputHeight: Int): FaceDetector {
        created++
        this.inputWidth = inputWidth
        this.inputHeight = inputHeight
        return object : FaceDetector {
            override val inputWidth: Int = inputWidth
            override val inputHeight: Int = inputHeight

            override fun detect(rgba: ByteBuffer, contentWidth: Int, contentHeight: Int): List<Detection> {
                check(closed == 0) { "detect after close" }
                if (calls == failOnCall) throw IllegalStateException("scripted detector failure")
                lastContentWidth = contentWidth
                lastContentHeight = contentHeight
                val index = calls++
                return script?.invoke(index) ?: next
            }

            override fun close() {
                closed++
            }
        }
    }
}

/** Counts what was cropped, by output size. */
internal class CropLog {
    val sizes = HashMap<Int, Int>()
    var total = 0

    fun record(width: Int) {
        sizes[width] = (sizes[width] ?: 0) + 1
        total++
    }
}

/** Fills a crop with the identity of the face nearest to the middle of the requested area. */
internal class FakeCropper(
    private val faces: List<Face>,
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val log: CropLog,
) : FrameCropper {
    override fun crop(corners: FloatArray, outWidth: Int, outHeight: Int, outRgb: ByteArray) {
        assertEquals("crop buffer size", outWidth * outHeight * 3, outRgb.size)
        log.record(outWidth)
        val cx = (corners[2] + corners[4]) / 2 * frameWidth
        val cy = (corners[3] + corners[5]) / 2 * frameHeight
        val nearest = faces.minByOrNull { hypot(it.x - cx, it.y - cy) }
        outRgb.fill((nearest?.identity ?: 0).toByte())
    }
}

/** Maps a crop back to the identity the cropper wrote into it and returns that identity's vector. */
internal class FakeEmbedder(
    override val cropSize: Int = 16,
    override val embeddingSize: Int = 16,
) : FaceEmbedder {
    val vectors = HashMap<Int, FloatArray>()
    var embedCalls = 0
    var alignCalls = 0
    var failEmbedding = false

    /** Hands out the stored vector itself, for tests that count what the analyzer allocates. */
    var shareVectors = false

    fun vectorOf(identity: Int): FloatArray = vectors.getOrPut(identity) {
        FloatArray(embeddingSize).also { it[identity % embeddingSize] = 1f }
    }

    override fun alignedCropCorners(landmarksPx: FloatArray, outCorners: FloatArray) {
        alignCalls++
        var cx = 0f
        var cy = 0f
        for (i in 0 until 5) {
            cx += landmarksPx[i * 2] / 5
            cy += landmarksPx[i * 2 + 1] / 5
        }
        val half = hypot(landmarksPx[2] - landmarksPx[0], landmarksPx[3] - landmarksPx[1])
        outCorners[0] = cx - half
        outCorners[1] = cy - half
        outCorners[2] = cx + half
        outCorners[3] = cy - half
        outCorners[4] = cx - half
        outCorners[5] = cy + half
    }

    override fun embed(rgb: ByteArray): FloatArray {
        // Compared by hand: assertEquals would box both sizes on every call.
        if (rgb.size != cropSize * cropSize * 3) throw AssertionError("embedder crop of ${rgb.size} bytes")
        embedCalls++
        if (failEmbedding) throw IllegalStateException("scripted embedder failure")
        val vector = vectorOf(rgb[0].toInt())
        return if (shareVectors) vector else vector.copyOf()
    }

    override fun close() = Unit
}

/** Remembers which identity each stored thumbnail shows and flags every misuse of a handle. */
internal class FakeThumbnails : ThumbnailStore {
    val live = HashMap<Int, Int>()
    val errors = ArrayList<String>()
    var puts = 0
    var releases = 0
    var failPuts = false

    override fun put(width: Int, height: Int, rgb: ByteArray): Int {
        if (failPuts) throw IllegalStateException("scripted store failure")
        if (width != 128 || height != 128 || rgb.size != 128 * 128 * 3) errors += "put with ${width}x$height and ${rgb.size} bytes"
        val handle = puts++
        live[handle] = rgb[0].toInt()
        return handle
    }

    override fun release(handle: Int) {
        releases++
        if (live.remove(handle) == null) errors += "release of unknown or already released handle $handle"
    }
}

/** Detector input pictures. Each scene is a different pattern of flat blocks, so scenes differ everywhere. */
internal object Pixels {
    private val cache = HashMap<Long, ByteBuffer>()

    /** [shiftX] slides the pattern sideways by that many pixels, as a pan would. */
    fun scene(scene: Int, inputWidth: Int, inputHeight: Int, brighten: Int = 0, shiftX: Int = 0): ByteBuffer {
        val key = (shiftX.toLong() shl 48) or (scene.toLong() shl 36) or (brighten.toLong() shl 28) or
            (inputWidth.toLong() shl 14) or inputHeight.toLong()
        // Each picture is a megabyte or more and a long test walks through many scenes.
        if (cache.size > 24 && key !in cache) cache.clear()
        return cache.getOrPut(key) {
            val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * 4)
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val luma = (lumaOf(scene, (x + shiftX) / 40, y / 40) + brighten).coerceAtMost(255).toByte()
                    buffer.put(luma).put(luma).put(luma).put(0xFF.toByte())
                }
            }
            buffer.flip()
            buffer
        }
    }

    private fun lumaOf(scene: Int, bx: Int, by: Int): Int {
        if (scene == 0) return 60
        var h = scene * 73856093 xor bx * 19349663 xor by * 83492791
        h = h xor (h ushr 13)
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0x7FFFFFFF) % 200
    }
}

/** Runs one scan the way the analysis module would: asks before each frame whether pixels are wanted. */
internal class Scan(
    val frameWidth: Int = 1920,
    val frameHeight: Int = 1080,
    options: AnalysisOptions = AnalysisOptions.STANDARD,
    val embedder: FakeEmbedder? = FakeEmbedder(),
    val thumbnails: FakeThumbnails = FakeThumbnails(),
) {
    val detectors = ScriptedDetectors()
    val crops = CropLog()
    val analyzer: FaceAnalyzer = DefaultFaceAnalyzerFactory().create(frameWidth, frameHeight, options, detectors, embedder, thumbnails)

    /** Times of the frames that ran the detector and what it was told to report there. */
    val runTimes = ArrayList<Long>()
    val frameTimes = ArrayList<Long>()
    val reported = HashMap<Long, List<Detection>>()
    var scriptedCuts = 0

    fun frame(
        ptsUs: Long,
        faces: List<Face>,
        scene: Int = 0,
        brighten: Int = 0,
        extra: List<Detection> = emptyList(),
        shiftX: Int = 0,
    ) {
        frameTimes += ptsUs
        val before = detectors.calls
        if (analyzer.wantsPixels(ptsUs)) {
            val detections = (faces.map { it.detection(frameWidth, frameHeight) } + extra).sortedByDescending { it.score }
            detectors.next = detections
            analyzer.onFrame(
                ptsUs,
                Pixels.scene(scene, analyzer.inputWidth, analyzer.inputHeight, brighten, shiftX),
                FakeCropper(faces, frameWidth, frameHeight, crops),
            )
            assertEquals("a frame that wants pixels runs the detector once", before + 1, detectors.calls)
            runTimes += ptsUs
            reported[ptsUs] = detections
        } else {
            analyzer.onFrame(ptsUs, null, null)
            assertEquals("a frame that wants no pixels must not run the detector", before, detectors.calls)
        }
    }

    fun finish(durationUs: Long): AnalysisResult = analyzer.finish(durationUs)
}

internal fun at30(frame: Int): Long = frame * 1_000_000L / 30

internal fun FaceTrack.kindsList(): List<Byte> = sampleKinds.toList()

internal fun FaceTrack.detectedTimes(): List<Long> =
    (0 until sampleCount).filter { sampleKinds[it] == FaceTrack.SAMPLE_DETECTED }.map { timesUs[it] }
