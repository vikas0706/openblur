package io.github.vikas0706.openblur.vision

import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Prints what the hot paths cost on the machine running the tests. The assertions are only ceilings,
 * loose enough for a busy build machine.
 */
class TimingTest {
    private fun meanMillis(warmUp: Int, runs: Int, block: () -> Unit): Double {
        repeat(warmUp) { block() }
        val start = System.nanoTime()
        repeat(runs) { block() }
        return (System.nanoTime() - start) / (runs * 1e6)
    }

    /** The photo scaled to fill the height of a [tensorWidth] x [tensorHeight] detector input. */
    private fun photoFor(tensorWidth: Int, tensorHeight: Int): Picture {
        val photo = ApolloPhoto.picture
        return photo.scaledTo(minOf(tensorWidth, photo.width * tensorHeight / photo.height), tensorHeight)
    }

    @Test
    fun detectAndEmbedTimes() {
        val factory = OrtFaceDetectorFactory(TestModels.yunet)
        for ((width, height) in listOf(640 to 384, 1280 to 736)) {
            val picture = photoFor(width, height)
            val rgba = picture.toRgba(width, height)
            factory.create(width, height).use { detector ->
                var faces = 0
                val mean = meanMillis(warmUp = 10, runs = 30) {
                    faces = detector.detect(rgba, picture.width, picture.height).count { it.score > 0.6f }
                }
                println("detect() at ${width}x$height, 4 threads: mean %.2f ms over 30 runs".format(mean))
                assertEquals(3, faces)
                assertTrue("detect() at ${width}x$height took $mean ms", mean < 2000.0)
            }
        }

        SFaceEmbedder(TestModels.sface).use { embedder ->
            val crop = ByteArray(112 * 112 * 3) { (it * 31).toByte() }
            val mean = meanMillis(warmUp = 5, runs = 30) { embedder.embed(crop) }
            println("embed() int8, 2 threads: mean %.2f ms over 30 runs".format(mean))
            assertTrue("embed() took $mean ms", mean < 3000.0)
        }
        TestModels.sfaceReferenceFile?.let { reference ->
            SFaceEmbedder(reference.readBytes()).use { embedder ->
                val crop = ByteArray(112 * 112 * 3) { (it * 31).toByte() }
                val mean = meanMillis(warmUp = 5, runs = 30) { embedder.embed(crop) }
                println("embed() full precision, 2 threads: mean %.2f ms over 30 runs".format(mean))
            }
        }
    }

    @Test
    @Suppress("DEPRECATION") // Thread.threadId() needs Java 19 and the tests also run on 17.
    fun detectAllocatesNothingThatGrowsWithTheFrame() {
        val threads = ManagementFactory.getThreadMXBean()
        assumeTrue(threads is com.sun.management.ThreadMXBean && threads.isThreadAllocatedMemorySupported)
        threads as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id

        OrtFaceDetectorFactory(TestModels.yunet).create(1280, 736).use { detector ->
            val empty = Picture.flat(1280, 736, 0x404040).toRgba(1280, 736)
            val photo = photoFor(1280, 736)
            val crowded = photo.toRgba(1280, 736)
            repeat(30) {
                detector.detect(empty, 1280, 736)
                detector.detect(crowded, photo.width, photo.height)
            }

            var before = threads.getThreadAllocatedBytes(thread)
            repeat(100) { detector.detect(empty, 1280, 736) }
            val perEmptyCall = (threads.getThreadAllocatedBytes(thread) - before) / 100

            var found = 0
            before = threads.getThreadAllocatedBytes(thread)
            repeat(100) { found = detector.detect(crowded, photo.width, photo.height).size }
            val perCrowdedCall = (threads.getThreadAllocatedBytes(thread) - before) / 100

            println("detect() at 1280x736 allocates $perEmptyCall bytes per call with no detections, " +
                "$perCrowdedCall bytes with $found")
            // The twelve outputs alone are 1.2 MB; copying any of them per call would show up here.
            assertTrue("detect() allocated $perEmptyCall bytes on an empty frame", perEmptyCall < 4096)
            assertTrue(
                "detect() allocated $perCrowdedCall bytes for $found detections",
                perCrowdedCall < 4096 + 256 * found,
            )
        }
    }

    /**
     * Ways of turning RGBA bytes into the detector's float planes, timed side by side. Absolute buffer access
     * wins on a desktop JVM, yet BgrPlaneWriter moves rows in bulk: on Android every single buffer get or put
     * is a chain of checked calls, so the desktop ranking does not carry over to the phones this runs on.
     */
    @Test
    fun rgbaToPlanesAlternatives() {
        val width = 1280
        val height = 736
        val plane = width * height
        val rgba = Picture.noise(width, height, seed = 9).toRgba(width, height)

        val rowWise = OrtRuntime.directFloats(3 * plane)
        val writer = BgrPlaneWriter(width, height, rowWise)
        val rowWiseMs = meanMillis(warmUp = 20, runs = 40) { writer.write(rgba, width, height) }

        val absolute = OrtRuntime.directFloats(3 * plane)
        val absoluteMs = meanMillis(warmUp = 20, runs = 40) { absoluteAccess(rgba, width, height, absolute) }

        val bytes = ByteArray(plane * 4)
        val bulkGetAbsolutePut = OrtRuntime.directFloats(3 * plane)
        val bulkGetAbsolutePutMs = meanMillis(warmUp = 20, runs = 40) {
            bulkGetThenAbsolutePuts(rgba, bytes, plane, bulkGetAbsolutePut)
        }

        val floats = FloatArray(3 * plane)
        val wholeFrame = OrtRuntime.directFloats(3 * plane)
        val wholeFrameMs = meanMillis(warmUp = 20, runs = 40) {
            bulkGetThenArrayThenBulkPut(rgba, bytes, floats, plane, wholeFrame)
        }

        println("RGBA to BGR planes at ${width}x$height, mean over 40 runs:")
        println("  row-wise bulk get, array writes, bulk put (BgrPlaneWriter): %.2f ms".format(rowWiseMs))
        println("  absolute get and put on the buffers: %.2f ms".format(absoluteMs))
        println("  whole-frame bulk get, absolute puts: %.2f ms".format(bulkGetAbsolutePutMs))
        println("  whole-frame bulk get, array writes, one bulk put: %.2f ms".format(wholeFrameMs))

        val expected = FloatArray(3 * plane) { rowWise.get(it) }
        val others = listOf("absolute" to absolute, "bulk get" to bulkGetAbsolutePut, "whole frame" to wholeFrame)
        for ((name, tensor) in others) {
            assertArrayEquals(name, expected, FloatArray(3 * plane) { tensor.get(it) }, 0f)
        }
        assertTrue("the conversion took $rowWiseMs ms", rowWiseMs < 500.0)
    }

    private fun absoluteAccess(rgba: ByteBuffer, width: Int, height: Int, target: FloatBuffer) {
        val plane = width * height
        var source = rgba.position()
        for (i in 0 until plane) {
            target.put(i, (rgba.get(source + 2).toInt() and 0xFF).toFloat())
            target.put(plane + i, (rgba.get(source + 1).toInt() and 0xFF).toFloat())
            target.put(2 * plane + i, (rgba.get(source).toInt() and 0xFF).toFloat())
            source += 4
        }
    }

    private fun bulkGetThenAbsolutePuts(rgba: ByteBuffer, bytes: ByteArray, plane: Int, target: FloatBuffer) {
        rgba.duplicate().get(bytes)
        var source = 0
        for (i in 0 until plane) {
            target.put(i, (bytes[source + 2].toInt() and 0xFF).toFloat())
            target.put(plane + i, (bytes[source + 1].toInt() and 0xFF).toFloat())
            target.put(2 * plane + i, (bytes[source].toInt() and 0xFF).toFloat())
            source += 4
        }
    }

    private fun bulkGetThenArrayThenBulkPut(
        rgba: ByteBuffer,
        bytes: ByteArray,
        floats: FloatArray,
        plane: Int,
        target: FloatBuffer,
    ) {
        rgba.duplicate().get(bytes)
        var source = 0
        for (i in 0 until plane) {
            floats[i] = (bytes[source + 2].toInt() and 0xFF).toFloat()
            floats[plane + i] = (bytes[source + 1].toInt() and 0xFF).toFloat()
            floats[2 * plane + i] = (bytes[source].toInt() and 0xFF).toFloat()
            source += 4
        }
        target.clear()
        target.put(floats)
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOnnxRuntime() = TestModels.requireOnnxRuntime()
    }
}
