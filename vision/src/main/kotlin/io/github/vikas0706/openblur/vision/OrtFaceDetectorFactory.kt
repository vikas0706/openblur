package io.github.vikas0706.openblur.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import io.github.vikas0706.openblur.core.Detection
import io.github.vikas0706.openblur.core.FaceDetector
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Builds YuNet detectors on ONNX Runtime's CPU provider. [modelBytes] is the content of yunet_n_dynamic.onnx
 * and is kept, not copied; [threads] is the intra-op thread count of every detector created.
 *
 * Each detector owns a session with the tensor size fixed, so create one per size and close it when done.
 * Failures to load or run the model are reported as [ModelException].
 */
class OrtFaceDetectorFactory(private val modelBytes: ByteArray, private val threads: Int = 4) : FaceDetectorFactory {
    init {
        require(modelBytes.isNotEmpty()) { "modelBytes is empty" }
        require(threads >= 1) { "threads must be at least 1, was $threads" }
    }

    override fun create(inputWidth: Int, inputHeight: Int): FaceDetector {
        require(inputWidth > 0 && inputWidth % 32 == 0 && inputHeight > 0 && inputHeight % 32 == 0) {
            "The detector input must be positive multiples of 32, was ${inputWidth}x$inputHeight"
        }
        // The input tensor is one direct buffer of 12 bytes per pixel, and a buffer holds at most 2 GB.
        require(inputWidth.toLong() * inputHeight * 12 <= Int.MAX_VALUE) {
            "The detector input ${inputWidth}x$inputHeight is too large"
        }
        val resources = NativeResources()
        try {
            val session = resources.own(
                OrtRuntime.createSession(modelBytes, threads) { options ->
                    // Lets the runtime optimise for the one input size this detector will ever see.
                    options.setSymbolicDimensionValue("batch", 1)
                    options.setSymbolicDimensionValue("height", inputHeight.toLong())
                    options.setSymbolicDimensionValue("width", inputWidth.toLong())
                },
            )
            if (INPUT_NAME !in session.inputNames || !session.outputNames.containsAll(OUTPUT_NAMES)) {
                throw ModelException(
                    "Not a YuNet model: inputs ${session.inputNames}, outputs ${session.outputNames}",
                )
            }
            return OrtFaceDetector(inputWidth, inputHeight, session, resources)
        } catch (e: OrtException) {
            closeQuietly(resources)
            throw ModelException("The face detector could not be created: ${e.message}", e)
        } catch (e: Throwable) {
            closeQuietly(resources)
            throw e
        }
    }

    private fun closeQuietly(resources: NativeResources) {
        try {
            resources.closeAll()
        } catch (_: ModelException) {
            // The failure that brought us here is the one worth reporting.
        }
    }

    internal companion object {
        const val INPUT_NAME = "input"
        private val KINDS = arrayOf("cls", "obj", "bbox", "kps")
        val CHANNELS = intArrayOf(1, 1, 4, 10)

        fun outputName(kind: Int, level: Int): String = "${KINDS[kind]}_${YuNetDecoder.STRIDES[level]}"

        val OUTPUT_NAMES: List<String> =
            KINDS.indices.flatMap { kind -> (0 until YuNetDecoder.LEVELS).map { outputName(kind, it) } }
    }
}

private class OrtFaceDetector(
    override val inputWidth: Int,
    override val inputHeight: Int,
    private val session: OrtSession,
    private val resources: NativeResources,
) : FaceDetector {
    private val lock = Any()
    private var closed = false

    private val decoder = YuNetDecoder(inputWidth, inputHeight)
    private val feeds: Map<String, OnnxTensor>
    private val planes: BgrPlaneWriter

    // The runtime writes its results straight into these buffers instead of allocating new ones every run.
    private val outputs = LinkedHashMap<String, OnnxTensor>()
    private val clsBuffers: Array<FloatBuffer>
    private val objBuffers: Array<FloatBuffer>
    private val bboxBuffers: Array<FloatBuffer>
    private val kpsBuffers: Array<FloatBuffer>
    private val cls = Array(YuNetDecoder.LEVELS) { FloatArray(decoder.anchorCounts[it]) }
    private val obj = Array(YuNetDecoder.LEVELS) { FloatArray(decoder.anchorCounts[it]) }

    init {
        val input = OrtRuntime.directFloats(3 * inputWidth * inputHeight)
        val inputTensor = resources.own(
            OrtRuntime.tensorOver(input, 1, 3, inputHeight.toLong(), inputWidth.toLong()),
        )
        feeds = java.util.Collections.singletonMap(OrtFaceDetectorFactory.INPUT_NAME, inputTensor)
        planes = BgrPlaneWriter(inputWidth, inputHeight, input)

        val buffers = Array(OrtFaceDetectorFactory.CHANNELS.size) { kind ->
            Array(YuNetDecoder.LEVELS) { level ->
                val anchors = decoder.anchorCounts[level]
                val channels = OrtFaceDetectorFactory.CHANNELS[kind]
                val buffer = OrtRuntime.directFloats(anchors * channels)
                outputs[OrtFaceDetectorFactory.outputName(kind, level)] =
                    resources.own(OrtRuntime.tensorOver(buffer, 1, anchors.toLong(), channels.toLong()))
                buffer
            }
        }
        clsBuffers = buffers[0]
        objBuffers = buffers[1]
        bboxBuffers = buffers[2]
        kpsBuffers = buffers[3]
    }

    override fun detect(rgba: ByteBuffer, contentWidth: Int, contentHeight: Int): List<Detection> {
        // Uncontended in normal use. It keeps a close() on another thread from freeing memory mid-run.
        synchronized(lock) {
            check(!closed) { "This FaceDetector is closed" }
            require(contentWidth in 1..inputWidth && contentHeight in 1..inputHeight) {
                "Content ${contentWidth}x$contentHeight does not fit the ${inputWidth}x$inputHeight input"
            }
            require(rgba.remaining() >= inputWidth * inputHeight * 4) {
                "rgba holds ${rgba.remaining()} bytes, the ${inputWidth}x$inputHeight input needs " +
                    "${inputWidth * inputHeight * 4}"
            }
            planes.write(rgba, contentWidth, contentHeight)
            try {
                session.run(feeds, outputs).close()
            } catch (e: OrtException) {
                throw ModelException("The face detector failed on a frame: ${e.message}", e)
            }
            for (level in 0 until YuNetDecoder.LEVELS) {
                // One bulk copy per output, so the scan over every anchor reads plain arrays.
                OrtRuntime.rewind(clsBuffers[level])
                clsBuffers[level].get(cls[level])
                OrtRuntime.rewind(objBuffers[level])
                objBuffers[level].get(obj[level])
            }
            return decoder.decode(cls, obj, bboxBuffers, kpsBuffers, contentWidth, contentHeight)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            resources.closeAll()
        }
    }
}
