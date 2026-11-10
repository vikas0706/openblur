package io.github.vikas0706.openblur.vision

import io.github.vikas0706.openblur.core.Detection
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import io.github.vikas0706.openblur.core.FrameCropper
import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object TestModels {
    private val modelsDir = File(
        System.getProperty("openblur.modelsDir") ?: error("System property openblur.modelsDir is not set"),
    )
    val yunetFile = File(modelsDir, "yunet_n_dynamic.onnx")
    val sfaceFile = File(modelsDir, "face_recognition_sface_2021dec_int8.onnx")

    /** The full-precision SFace file, or null when the reference models were not downloaded. */
    val sfaceReferenceFile: File? =
        System.getProperty("openblur.referenceModelsDir")
            ?.let { File(it, "face_recognition_sface_2021dec.onnx") }
            ?.takeIf { it.isFile }

    val yunet: ByteArray by lazy { yunetFile.readBytes() }
    val sface: ByteArray by lazy { sfaceFile.readBytes() }

    /** Fails with the likely cause instead of a bare link error when the native library does not load. */
    fun requireOnnxRuntime() {
        try {
            OrtRuntime.environment()
        } catch (e: ModelException) {
            throw AssertionError(
                "ONNX Runtime did not load in the JVM at ${System.getProperty("java.home")}. On Windows it needs " +
                    "a JDK that bundles msvcp140.dll 14.40 or newer (or none): pass " +
                    "-Popenblur.testJavaHome=<jdk> to Gradle, see vision/build.gradle.kts.",
                e,
            )
        }
    }
}

/** The Apollo 11 crew portrait: three frontal faces, each about [FACE_WIDTH_PX] wide. */
internal object ApolloPhoto {
    const val FACE_WIDTH_PX = 170

    /** Face centres in pixels of the 1920x1509 original, left to right. */
    val FACE_CENTRES = arrayOf(floatArrayOf(470f, 640f), floatArrayOf(925f, 335f), floatArrayOf(1400f, 640f))

    val picture: Picture by lazy { Picture.load("/images/apollo11_crew_1920.jpg") }

    /** Normalized centre of face [index]. */
    fun centre(index: Int): FloatArray =
        floatArrayOf(FACE_CENTRES[index][0] / picture.width, FACE_CENTRES[index][1] / picture.height)

    /**
     * The detection closest to each face, in the order of [FACE_CENTRES], or null where none lies within
     * [tolerance] of the frame size in both directions.
     */
    fun matchFaces(detections: List<Detection>, tolerance: Float = 0.06f): List<Detection?> =
        FACE_CENTRES.indices.map { face ->
            val expected = centre(face)
            detections
                .filter { abs(it.cx - expected[0]) <= tolerance && abs(it.cy - expected[1]) <= tolerance }
                .maxByOrNull { it.score }
        }
}

/** An RGB image held as 0xRRGGBB ints, row 0 at the top. */
internal class Picture(val width: Int, val height: Int, val rgb: IntArray) {
    init {
        require(rgb.size == width * height)
    }

    /** Area-averaged downscale, the way a camera pipeline shrinks a frame without aliasing. */
    fun scaledTo(targetWidth: Int, targetHeight: Int): Picture {
        require(targetWidth in 1..width && targetHeight in 1..height) { "scaledTo only shrinks" }
        val horizontal = FloatArray(targetWidth * height * 3)
        val stepX = width.toDouble() / targetWidth
        for (x in 0 until targetWidth) {
            val from = x * stepX
            val to = (x + 1) * stepX
            val first = floor(from).toInt()
            val last = min(ceil(to).toInt(), width) - 1
            for (y in 0 until height) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (s in first..last) {
                    val weight = min(to, s + 1.0) - max(from, s.toDouble())
                    val p = rgb[y * width + s]
                    r += weight * ((p shr 16) and 0xFF)
                    g += weight * ((p shr 8) and 0xFF)
                    b += weight * (p and 0xFF)
                }
                val o = (y * targetWidth + x) * 3
                horizontal[o] = (r / stepX).toFloat()
                horizontal[o + 1] = (g / stepX).toFloat()
                horizontal[o + 2] = (b / stepX).toFloat()
            }
        }
        val out = IntArray(targetWidth * targetHeight)
        val stepY = height.toDouble() / targetHeight
        for (y in 0 until targetHeight) {
            val from = y * stepY
            val to = (y + 1) * stepY
            val first = floor(from).toInt()
            val last = min(ceil(to).toInt(), height) - 1
            for (x in 0 until targetWidth) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (s in first..last) {
                    val weight = min(to, s + 1.0) - max(from, s.toDouble())
                    val o = (s * targetWidth + x) * 3
                    r += weight * horizontal[o]
                    g += weight * horizontal[o + 1]
                    b += weight * horizontal[o + 2]
                }
                out[y * targetWidth + x] =
                    (channel(r / stepY) shl 16) or (channel(g / stepY) shl 8) or channel(b / stepY)
            }
        }
        return Picture(targetWidth, targetHeight, out)
    }

    /** Scales so that a feature [sourcePx] wide in this picture becomes [targetPx] wide. */
    fun scaledBy(targetPx: Double, sourcePx: Double): Picture =
        scaledTo((width * targetPx / sourcePx).roundToInt(), (height * targetPx / sourcePx).roundToInt())

    /**
     * A direct RGBA buffer of [tensorWidth] x [tensorHeight] with this picture at the top left.
     * [padding] supplies 0xRRGGBB for every pixel outside it.
     */
    fun toRgba(tensorWidth: Int, tensorHeight: Int, padding: (x: Int, y: Int) -> Int = { _, _ -> 0 }): ByteBuffer {
        require(width <= tensorWidth && height <= tensorHeight)
        val buffer = ByteBuffer.allocateDirect(tensorWidth * tensorHeight * 4)
        for (y in 0 until tensorHeight) {
            for (x in 0 until tensorWidth) {
                val p = if (x < width && y < height) rgb[y * width + x] else padding(x, y)
                buffer.put((p shr 16).toByte()).put((p shr 8).toByte()).put(p.toByte()).put(0xFF.toByte())
            }
        }
        buffer.flip()
        return buffer
    }

    companion object {
        fun load(resource: String): Picture {
            val stream = Picture::class.java.getResourceAsStream(resource) ?: error("Missing test resource $resource")
            val image = stream.use { ImageIO.read(it) } ?: error("Unreadable test resource $resource")
            val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
            return Picture(image.width, image.height, IntArray(argb.size) { argb[it] and 0xFFFFFF })
        }

        fun flat(width: Int, height: Int, colour: Int) = Picture(width, height, IntArray(width * height) { colour })

        fun noise(width: Int, height: Int, seed: Long): Picture {
            val random = Random(seed)
            return Picture(width, height, IntArray(width * height) { random.nextInt(0x1000000) })
        }

        private fun channel(value: Double): Int = value.roundToInt().coerceIn(0, 255)
    }
}

/** The smallest multiple of 32 that holds [size]. */
internal fun tensorSize(size: Int): Int = (size + 31) / 32 * 32

/** Runs [picture] through a detector sized to fit it and closes the detector. */
internal fun FaceDetectorFactory.detectOnce(
    picture: Picture,
    tensorWidth: Int = tensorSize(picture.width),
    tensorHeight: Int = tensorSize(picture.height),
): List<Detection> =
    create(tensorWidth, tensorHeight).use {
        it.detect(picture.toRgba(tensorWidth, tensorHeight), picture.width, picture.height)
    }

/**
 * A bilinear [FrameCropper] over a [Picture]. Output pixel (i, j) samples the frame (i + 0.5) / outWidth of the
 * way from the top-left to the top-right corner and (j + 0.5) / outHeight of the way down to the bottom-left one.
 */
internal class BilinearCropper(private val frame: Picture) : FrameCropper {
    override fun crop(corners: FloatArray, outWidth: Int, outHeight: Int, outRgb: ByteArray) {
        val originX = corners[0] * frame.width
        val originY = corners[1] * frame.height
        val acrossX = corners[2] * frame.width - originX
        val acrossY = corners[3] * frame.height - originY
        val downX = corners[4] * frame.width - originX
        val downY = corners[5] * frame.height - originY
        var o = 0
        for (j in 0 until outHeight) {
            val v = (j + 0.5f) / outHeight
            for (i in 0 until outWidth) {
                val u = (i + 0.5f) / outWidth
                // Pixel k of the frame covers k..k+1, so its centre is at k + 0.5.
                val fx = originX + u * acrossX + v * downX - 0.5f
                val fy = originY + u * acrossY + v * downY - 0.5f
                val x0 = floor(fx).toInt()
                val y0 = floor(fy).toInt()
                val wx = fx - x0
                val wy = fy - y0
                val p00 = pixel(x0, y0)
                val p10 = pixel(x0 + 1, y0)
                val p01 = pixel(x0, y0 + 1)
                val p11 = pixel(x0 + 1, y0 + 1)
                for (shift in intArrayOf(16, 8, 0)) {
                    val top = ((p00 shr shift) and 0xFF) * (1 - wx) + ((p10 shr shift) and 0xFF) * wx
                    val bottom = ((p01 shr shift) and 0xFF) * (1 - wx) + ((p11 shr shift) and 0xFF) * wx
                    outRgb[o++] = (top * (1 - wy) + bottom * wy).roundToInt().coerceIn(0, 255).toByte()
                }
            }
        }
    }

    private fun pixel(x: Int, y: Int): Int =
        frame.rgb[y.coerceIn(0, frame.height - 1) * frame.width + x.coerceIn(0, frame.width - 1)]
}

/** Dot product; for unit vectors this is the cosine similarity. */
internal fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0.0
    for (i in a.indices) sum += a[i].toDouble() * b[i]
    return sum.toFloat()
}
