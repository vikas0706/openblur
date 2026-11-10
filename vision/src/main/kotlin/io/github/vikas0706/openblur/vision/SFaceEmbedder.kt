package io.github.vikas0706.openblur.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import io.github.vikas0706.openblur.core.FaceEmbedder
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * SFace on ONNX Runtime's CPU provider. [modelBytes] is the content of face_recognition_sface_2021dec_int8.onnx
 * (the full-precision file has the same inputs and outputs and works too).
 *
 * Safe to share between threads: [embed] and [close] take turns. Failures to load or run the model are
 * reported as [ModelException].
 */
class SFaceEmbedder(modelBytes: ByteArray, threads: Int = 2) : FaceEmbedder {
    override val cropSize: Int get() = CROP
    override val embeddingSize: Int get() = EMBEDDING

    private val lock = Any()

    @Volatile
    private var closed = false
    private val resources = NativeResources()
    private val session: OrtSession
    private val input: FloatBuffer
    private val output: FloatBuffer
    private val feeds: Map<String, OnnxTensor>
    private val outputs: Map<String, OnnxTensor>
    private val planes = FloatArray(3 * PIXELS)
    private val raw = FloatArray(EMBEDDING)

    init {
        require(modelBytes.isNotEmpty()) { "modelBytes is empty" }
        require(threads >= 1) { "threads must be at least 1, was $threads" }
        try {
            session = resources.own(OrtRuntime.createSession(modelBytes, threads))
            val inputShape = (session.inputInfo[INPUT_NAME]?.info as? TensorInfo)?.shape
            val outputShape = (session.outputInfo[OUTPUT_NAME]?.info as? TensorInfo)?.shape
            if (inputShape == null || !inputShape.contentEquals(INPUT_SHAPE) ||
                outputShape == null || !outputShape.contentEquals(OUTPUT_SHAPE)
            ) {
                throw ModelException(
                    "Not an SFace model: $INPUT_NAME ${inputShape?.contentToString()}, " +
                        "$OUTPUT_NAME ${outputShape?.contentToString()}",
                )
            }
            input = OrtRuntime.directFloats(3 * PIXELS)
            output = OrtRuntime.directFloats(EMBEDDING)
            feeds = java.util.Collections.singletonMap(
                INPUT_NAME,
                resources.own(OrtRuntime.tensorOver(input, *INPUT_SHAPE)),
            )
            outputs = java.util.Collections.singletonMap(
                OUTPUT_NAME,
                resources.own(OrtRuntime.tensorOver(output, *OUTPUT_SHAPE)),
            )
        } catch (e: Throwable) {
            try {
                resources.closeAll()
            } catch (_: ModelException) {
                // The failure that brought us here is the one worth reporting.
            }
            if (e is OrtException) throw ModelException("The face embedder could not be created: ${e.message}", e)
            throw e
        }
    }

    /**
     * The corners are the outer corners of the crop square, not the centres of its corner pixels: output
     * pixel (i, j) belongs (i + 0.5) / 112 of the way across and (j + 0.5) / 112 of the way down.
     * Landmarks that fix no orientation give an upright crop; ones that are not finite are rejected.
     */
    override fun alignedCropCorners(landmarksPx: FloatArray, outCorners: FloatArray) {
        check(!closed) { "This SFaceEmbedder is closed" }
        ArcFaceAlignment.cropCorners(landmarksPx, outCorners)
    }

    /** The result is all zeros in the one case where it cannot have unit length: the model returned no direction. */
    override fun embed(rgb: ByteArray): FloatArray {
        synchronized(lock) {
            check(!closed) { "This SFaceEmbedder is closed" }
            require(rgb.size >= 3 * PIXELS) { "rgb holds ${rgb.size} bytes, a ${CROP}x$CROP crop needs ${3 * PIXELS}" }
            val values = planes
            var i = 0
            for (p in 0 until PIXELS) {
                values[p] = (rgb[i].toInt() and 0xFF).toFloat()
                values[PIXELS + p] = (rgb[i + 1].toInt() and 0xFF).toFloat()
                values[2 * PIXELS + p] = (rgb[i + 2].toInt() and 0xFF).toFloat()
                i += 3
            }
            OrtRuntime.rewind(input)
            input.put(values)
            try {
                session.run(feeds, outputs).close()
            } catch (e: OrtException) {
                throw ModelException("The face embedder failed on a crop: ${e.message}", e)
            }
            OrtRuntime.rewind(output)
            output.get(raw)

            var sum = 0.0
            for (v in raw) sum += v.toDouble() * v
            val norm = sqrt(sum)
            val embedding = FloatArray(EMBEDDING)
            // A NaN or zero norm fails this test, leaving zeros that match nothing rather than poisoning sums.
            if (norm > 0.0 && norm < Double.POSITIVE_INFINITY) {
                for (k in 0 until EMBEDDING) embedding[k] = (raw[k] / norm).toFloat()
            }
            return embedding
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            resources.closeAll()
        }
    }

    private companion object {
        const val CROP = ArcFaceAlignment.CROP_SIZE
        const val PIXELS = CROP * CROP
        const val EMBEDDING = 128
        const val INPUT_NAME = "data"
        const val OUTPUT_NAME = "fc1"
        val INPUT_SHAPE = longArrayOf(1, 3, CROP.toLong(), CROP.toLong())
        val OUTPUT_SHAPE = longArrayOf(1, EMBEDDING.toLong())
    }
}
