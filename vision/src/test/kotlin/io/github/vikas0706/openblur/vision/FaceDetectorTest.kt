package io.github.vikas0706.openblur.vision

import io.github.vikas0706.openblur.core.Detection
import io.github.vikas0706.openblur.core.FaceDetectorFactory
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class FaceDetectorTest {
    private val factory = OrtFaceDetectorFactory(TestModels.yunet)

    private fun describe(d: Detection): String =
        "score %.3f centre (%.3f, %.3f) size %.3f x %.3f".format(d.score, d.cx, d.cy, d.w, d.h)

    private fun iou(a: Detection, b: Detection): Float {
        val w = min(a.cx + a.w / 2, b.cx + b.w / 2) - max(a.cx - a.w / 2, b.cx - b.w / 2)
        val h = min(a.cy + a.h / 2, b.cy + b.h / 2) - max(a.cy - a.h / 2, b.cy - b.h / 2)
        if (w <= 0f || h <= 0f) return 0f
        return w * h / (a.w * a.h + b.w * b.h - w * h)
    }

    /** What [io.github.vikas0706.openblur.core.FaceDetector.detect] promises about any result list. */
    private fun assertHonoursTheContract(detections: List<Detection>, contentWidth: Int, contentHeight: Int) {
        for ((i, d) in detections.withIndex()) {
            assertTrue("score of #$i: ${describe(d)}", d.score >= FaceDetectorFactory.SCORE_FLOOR && d.score <= 1f)
            assertTrue("centre of #$i inside the content: ${describe(d)}", d.cx in 0f..1f && d.cy in 0f..1f)
            assertTrue(
                "box of #$i inside the content: ${describe(d)}",
                d.w > 0f && d.h > 0f &&
                    d.cx - d.w / 2 >= -1e-4f && d.cx + d.w / 2 <= 1 + 1e-4f &&
                    d.cy - d.h / 2 >= -1e-4f && d.cy + d.h / 2 <= 1 + 1e-4f,
            )
            assertEquals(10, d.landmarks.size)
            assertTrue("landmarks of #$i are finite", d.landmarks.all { it.isFinite() })
            if (i > 0) assertTrue("sorted by descending score at #$i", detections[i - 1].score >= d.score)
            for (j in 0 until i) {
                // The suppression runs in tensor pixels; normalizing by a non-square content keeps the ratio.
                assertTrue("#$j and #$i are duplicates", iou(detections[j], d) <= YuNetDecoder.NMS_IOU + 1e-3f)
            }
        }
    }

    private fun assertLooksLikeAnUprightFace(d: Detection) {
        val lm = d.landmarks
        assertTrue("the subject's right eye is on the image left: ${lm.toList()}", lm[0] < lm[2])
        assertTrue("the right mouth corner is on the image left: ${lm.toList()}", lm[6] < lm[8])
        assertTrue("the nose is between the eyes: ${lm.toList()}", lm[4] > lm[0] && lm[4] < lm[2])
        assertTrue("eyes above the nose: ${lm.toList()}", lm[1] < lm[5] && lm[3] < lm[5])
        assertTrue("nose above the mouth: ${lm.toList()}", lm[5] < lm[7] && lm[5] < lm[9])
        for (n in 0 until 5) {
            assertTrue(
                "landmark $n inside the box: ${lm.toList()} ${describe(d)}",
                lm[2 * n] > d.cx - d.w / 2 && lm[2 * n] < d.cx + d.w / 2 &&
                    lm[2 * n + 1] > d.cy - d.h / 2 && lm[2 * n + 1] < d.cy + d.h / 2,
            )
        }
    }

    private fun assertFindsExactlyTheCrew(contentWidth: Int, contentHeight: Int) {
        val picture = ApolloPhoto.picture.scaledTo(contentWidth, contentHeight)
        val detections = factory.detectOnce(picture)
        assertHonoursTheContract(detections, contentWidth, contentHeight)

        val strong = detections.filter { it.score > 0.6f }
        println("detect ${contentWidth}x$contentHeight in ${tensorSize(contentWidth)}x${tensorSize(contentHeight)}: " +
            "${detections.size} candidates, ${strong.size} above 0.6")
        strong.forEach { println("  ${describe(it)}") }
        detections.filter { it.score <= 0.6f }.forEach { println("  weak: ${describe(it)}") }
        assertEquals("faces above 0.6", 3, strong.size)

        val faces = ApolloPhoto.matchFaces(strong)
        for ((i, face) in faces.withIndex()) {
            assertNotNull("no detection within 6 percent of face $i", face)
            // Each face is about 170 px wide and 230 px tall in the 1920x1509 original.
            val widthPx = face!!.w * ApolloPhoto.picture.width
            val heightPx = face.h * ApolloPhoto.picture.height
            assertTrue("face $i is $widthPx px wide", widthPx in 120f..230f)
            assertTrue("face $i is $heightPx px tall", heightPx in 160f..310f)
            assertLooksLikeAnUprightFace(face)
        }
        assertEquals("each face matched by its own detection", 3, faces.toSet().size)
    }

    @Test
    fun findsTheCrewAt640() = assertFindsExactlyTheCrew(640, 503)

    @Test
    fun findsTheCrewAt320() = assertFindsExactlyTheCrew(320, 252)

    @Test
    fun findsTheCrewAt1280() = assertFindsExactlyTheCrew(1280, 1006)

    @Test
    fun whatTheCallerLeavesInThePaddingChangesNothing() {
        // The padding of the 640x512 buffer holds three more copies of the picture: nine faces that must stay unseen.
        val picture = ApolloPhoto.picture.scaledTo(320, 252)
        val black = picture.toRgba(640, 512)
        val busy = picture.toRgba(640, 512) { x, y -> picture.rgb[min(y % 256, 251) * 320 + x % 320] }

        factory.create(640, 512).use { detector ->
            val expected = detector.detect(black, 320, 252)
            val found = detector.detect(busy, 320, 252)

            assertEquals(3, expected.count { it.score > 0.6f })
            assertSameDetections(expected, found)
            assertHonoursTheContract(found, 320, 252)
        }
    }

    @Test
    fun framesWithoutFacesGiveNothingStrong() {
        val frames = listOf(
            "black" to Picture.flat(640, 384, 0x000000),
            "white" to Picture.flat(640, 384, 0xFFFFFF),
            "grey" to Picture.flat(640, 384, 0x808080),
            "skin tone" to Picture.flat(640, 384, 0xC68E6F),
            "noise" to Picture.noise(640, 384, seed = 1),
            "noise, partial content" to Picture.noise(500, 300, seed = 2),
            "coarse noise" to Picture.noise(80, 48, seed = 3).let { small ->
                Picture(640, 384, IntArray(640 * 384) { small.rgb[(it / 640 / 8) * 80 + (it % 640) / 8] })
            },
        )
        factory.create(640, 384).use { detector ->
            for ((name, frame) in frames) {
                val detections = detector.detect(frame.toRgba(640, 384), frame.width, frame.height)
                val strongest = detections.firstOrNull()?.score ?: 0f
                println("face-free $name: ${detections.size} candidates, strongest %.3f".format(strongest))
                assertHonoursTheContract(detections, frame.width, frame.height)
                assertTrue("$name gave a detection scoring $strongest", strongest <= 0.5f)
            }
        }
    }

    @Test
    fun findsFacesTwelvePixelsWide() {
        val detections = factory.detectOnce(ApolloPhoto.picture.scaledBy(12.0, ApolloPhoto.FACE_WIDTH_PX.toDouble()))
        val faces = ApolloPhoto.matchFaces(detections)
        println("12 px faces: " + faces.joinToString { it?.let(::describe) ?: "missed" })
        for ((i, face) in faces.withIndex()) {
            assertNotNull("face $i was missed at 12 px", face)
        }
        assertEquals(3, faces.toSet().size)
    }

    @Test
    fun smallFacesScoreWellFromEightPixelsUp() {
        for (facePx in listOf(4, 5, 6, 7, 8, 10, 12, 14, 16, 20, 24)) {
            val picture = ApolloPhoto.picture.scaledBy(facePx.toDouble(), ApolloPhoto.FACE_WIDTH_PX.toDouble())
            val faces = ApolloPhoto.matchFaces(factory.detectOnce(picture))
            println(
                "faces $facePx px wide (content ${picture.width}x${picture.height}): " +
                    faces.joinToString { it?.let { d -> "%.3f".format(d.score) } ?: "missed" },
            )
            // Below 8 px the scores are for the record only: the model's stated minimum face is about 10 px.
            if (facePx >= 8) {
                assertTrue("a face $facePx px wide scored 0.6 or less", faces.all { it != null && it.score > 0.6f })
            }
        }
    }

    @Test
    fun oneDetectorGivesTheSameAnswerEveryTimeItSeesAFrame() {
        val photo = ApolloPhoto.picture.scaledTo(640, 503)
        val smaller = ApolloPhoto.picture.scaledTo(480, 377)
        factory.create(640, 512).use { detector ->
            val first = detector.detect(photo.toRgba(640, 512), 640, 503)
            assertEquals(3, first.count { it.score > 0.6f })

            val flat = Picture.flat(640, 512, 0x202020).toRgba(640, 512)
            assertTrue(detector.detect(flat, 640, 512).none { it.score > 0.5f })
            // A smaller picture afterwards must not see the edges of the larger one that came before it.
            val alone = factory.detectOnce(smaller, 640, 512)
            assertSameDetections(alone, detector.detect(smaller.toRgba(640, 512), 480, 377))

            assertSameDetections(first, detector.detect(photo.toRgba(640, 512), 640, 503))
        }
    }

    @Test
    fun detectorsOfDifferentSizesWorkSideBySideOnTheirOwnThreads() {
        val large = ApolloPhoto.picture.scaledTo(640, 503)
        val small = ApolloPhoto.picture.scaledTo(320, 252)
        val pool = Executors.newFixedThreadPool(2)
        try {
            factory.create(640, 512).use { first ->
                factory.create(320, 256).use { second ->
                    val largeRuns = pool.submit(
                        Callable { (0 until 6).map { first.detect(large.toRgba(640, 512), 640, 503) } },
                    )
                    val smallRuns = pool.submit(
                        Callable { (0 until 6).map { second.detect(small.toRgba(320, 256), 320, 252) } },
                    )
                    for (runs in listOf(largeRuns.get(), smallRuns.get())) {
                        assertEquals(3, runs[0].count { it.score > 0.6f })
                        runs.forEach { assertSameDetections(runs[0], it) }
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun readsFromTheBufferPositionOfHeapAndDirectBuffersAlike() {
        val photo = ApolloPhoto.picture.scaledTo(320, 252)
        val direct = photo.toRgba(320, 256)
        val heap = ByteBuffer.allocate(7 + direct.remaining())
        heap.position(7)
        heap.put(direct.duplicate())
        heap.position(7)

        factory.create(320, 256).use { detector ->
            val expected = detector.detect(direct, 320, 252)
            val found = detector.detect(heap, 320, 252)

            assertEquals(3, expected.count { it.score > 0.6f })
            assertSameDetections(expected, found)
            assertEquals(0, direct.position())
            assertEquals(7, heap.position())
        }
    }

    @Test
    fun rejectsSizesAndBuffersThatDoNotFit() {
        for ((w, h) in listOf(100 to 64, 64 to 100, 0 to 64, 64 to 0, -32 to 64, (1 shl 15) to (1 shl 15))) {
            assertThrows("${w}x$h", IllegalArgumentException::class.java) { factory.create(w, h) }
        }
        factory.create(64, 64).use { detector ->
            assertEquals(64, detector.inputWidth)
            assertEquals(64, detector.inputHeight)
            val frame = ByteBuffer.allocateDirect(64 * 64 * 4)
            for ((w, h) in listOf(0 to 10, 10 to 0, 65 to 10, 10 to 65, -1 to -1)) {
                assertThrows("${w}x$h", IllegalArgumentException::class.java) { detector.detect(frame, w, h) }
            }
            frame.position(1)
            assertThrows(IllegalArgumentException::class.java) { detector.detect(frame, 64, 64) }
        }
    }

    @Test
    fun closingTwiceIsHarmlessAndAClosedDetectorRefusesWork() {
        val detector = factory.create(64, 64)
        val frame = ByteBuffer.allocateDirect(64 * 64 * 4)
        assertTrue(detector.detect(frame, 64, 64).none { it.score > 0.5f })

        detector.close()
        detector.close()

        assertThrows(IllegalStateException::class.java) { detector.detect(frame, 64, 64) }
        // The factory holds no native state of its own, so it keeps working.
        factory.create(64, 64).use { assertTrue(it.detect(frame, 64, 64).none { d -> d.score > 0.5f }) }
    }

    @Test
    fun aFactoryRejectsWhatIsNotTheDetectorModel() {
        assertThrows(IllegalArgumentException::class.java) { OrtFaceDetectorFactory(ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { OrtFaceDetectorFactory(TestModels.yunet, threads = 0) }
        val junk = ByteArray(64) { it.toByte() }
        assertThrows(ModelException::class.java) { OrtFaceDetectorFactory(junk).create(64, 64) }
        assertThrows(ModelException::class.java) { OrtFaceDetectorFactory(TestModels.sface).create(64, 64) }
    }

    private fun assertSameDetections(expected: List<Detection>, found: List<Detection>) {
        assertEquals(expected.size, found.size)
        for (i in expected.indices) {
            assertEquals("score of #$i", expected[i].score, found[i].score, 0f)
            assertEquals("cx of #$i", expected[i].cx, found[i].cx, 0f)
            assertEquals("cy of #$i", expected[i].cy, found[i].cy, 0f)
            assertEquals("w of #$i", expected[i].w, found[i].w, 0f)
            assertEquals("h of #$i", expected[i].h, found[i].h, 0f)
            assertTrue("landmarks of #$i", expected[i].landmarks.contentEquals(found[i].landmarks))
        }
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun loadOnnxRuntime() = TestModels.requireOnnxRuntime()
    }
}
