package io.github.vikas0706.openblur.tracking

import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.Detection
import io.github.vikas0706.openblur.core.FrameCropper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.Random
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PerformanceTest {
    @Test
    fun oneHourOfVideoWithADozenFaces() {
        val frames = 108_000
        val count = 12
        val detectors = ScriptedDetectors()
        val thumbnails = FakeThumbnails()
        val embedder = FakeEmbedder()
        val analyzer = DefaultFaceAnalyzerFactory().create(1920, 1080, AnalysisOptions.STANDARD, detectors, embedder, thumbnails)
        val pixels = Pixels.scene(0, analyzer.inputWidth, analyzer.inputHeight)
        val random = Random(1)
        val faces = ArrayList<Face>()
        val cropper = FakeCropper(faces, 1920, 1080, CropLog())

        var frameNanos = 0L
        var slowestFrameNanos = 0L
        for (i in 0 until frames) {
            faces.clear()
            val detections = ArrayList<Detection>(count)
            for (k in 0 until count) {
                // Twelve people on a four by three grid, each swaying, sometimes missed and sometimes weak.
                val x = 240f + (k % 4) * 480f + 90f * sin(i * 0.011f * (1 + k % 3) + k)
                val y = 180f + (k / 4) * 360f + 60f * sin(i * 0.007f * (1 + k % 2) + 2f * k)
                val size = 130f + 30f * sin(i * 0.003f + k) + random.nextInt(5)
                val roll = random.nextFloat()
                if (roll < 0.04f) continue
                val face = Face(k + 1, x + random.nextInt(5) - 2, y + random.nextInt(5) - 2, size, score = if (roll < 0.1f) 0.35f else 0.9f)
                faces += face
                detections += face.detection(1920, 1080)
            }
            detectors.next = detections
            val started = System.nanoTime()
            analyzer.onFrame(at30(i), pixels, cropper)
            val took = System.nanoTime() - started
            frameNanos += took
            if (took > slowestFrameNanos) slowestFrameNanos = took
        }
        val started = System.nanoTime()
        val result = analyzer.finish(3_600_000_000L)
        val finishMillis = (System.nanoTime() - started) / 1e6

        val samples = result.tracks.sumOf { it.sampleCount }
        println(
            "one hour, $count faces: onFrame %.1f us mean, %.1f ms worst; finish %.0f ms for %d tracks, %d samples".format(
                frameNanos / 1e3 / frames, slowestFrameNanos / 1e6, finishMillis, result.tracks.size, samples,
            ),
        )
        assertEquals(count, result.tracks.size)
        assertEquals(count, result.people.size)
        assertTrue("every run of every face is a sample: $samples", samples >= frames * count)
        assertEquals(frames, result.framesDetected)
        assertTrue("finish took $finishMillis ms", finishMillis < 1000)

        val codec = BinaryAnalysisResultCodec()
        val out = ByteArrayOutputStream(samples * 41 + 4096)
        val writeStarted = System.nanoTime()
        codec.write(result, out)
        val writeMillis = (System.nanoTime() - writeStarted) / 1e6
        val bytes = out.toByteArray()
        val readStarted = System.nanoTime()
        val back = codec.read(ByteArrayInputStream(bytes))
        val readMillis = (System.nanoTime() - readStarted) / 1e6
        println("codec: %d bytes, write %.0f ms, read %.0f ms".format(bytes.size, writeMillis, readMillis))
        assertEquals(samples, back.tracks.sumOf { it.sampleCount })
    }

    @Test
    fun crowdOfTwoHundredFacesAcrossACut() {
        val scan = Scan()
        val random = Random(9)
        val crowd = List(200) { Face(1 + it % 100, 48f + (it % 20) * 96f, 54f + (it / 20) * 108f, 44f) }
        var nanos = 0L
        for (i in 0 until 120) {
            val faces = crowd.map {
                Face(it.identity, it.x + random.nextInt(5) - 2, it.y + random.nextInt(5) - 2, it.size, score = 0.55f + 0.4f * random.nextFloat())
            }.filter { random.nextFloat() > 0.05f }
            val started = System.nanoTime()
            scan.frame(at30(i), faces, scene = if (i < 60) 1 else 2)
            nanos += System.nanoTime() - started
        }
        val result = scan.finish(at30(120))
        println("crowd: %.2f ms per frame with 200 faces, %d tracks, %d people".format(nanos / 1e6 / 120, result.tracks.size, result.people.size))
        assertEquals(1, result.cutsUs.size)
        assertEquals("every face is one track on each side of the cut", 400, result.tracks.size)
        assertEquals(emptyList<String>(), scan.thumbnails.errors)
        assertEquals(result.tracks.map { it.thumbnailHandle }.toSet(), scan.thumbnails.live.keys)
        assertTrue("a crowded frame took ${nanos / 1e6 / 120} ms", nanos / 1e6 / 120 < 50)
    }

    @Test
    fun manyShortTracksAreGroupedQuickly() {
        // 1500 tracks of 30 people, one after another: grouping compares every pair of tracks.
        val size = 128
        val random = Random(2)
        val identities = Array(30) { FloatArray(size) { random.nextGaussian().toFloat() }.also(::normalize) }
        val tracks = ArrayList<BuiltTrack>()
        for (i in 0 until 1500) {
            val identity = identities[random.nextInt(30)]
            val centroid = FloatArray(size) { identity[it] + random.nextGaussian().toFloat() * 0.03f }.also(::normalize)
            val start = i * 1_000_000L
            tracks += BuiltTrack(
                serial = i,
                timesUs = longArrayOf(start, start + 900_000),
                boxes = FloatArray(8) { 0.5f },
                eyes = FloatArray(8) { Float.NaN },
                kinds = ByteArray(2),
                thumbHandle = i,
                thumbQuality = random.nextFloat(),
                meanScore = 0.9f,
                detectedStartUs = start,
                detectedEndUs = start + 900_000,
                centroid = centroid,
                centroidWeight = 5f,
            )
        }
        val started = System.nanoTime()
        val grouping = PeopleGrouper.group(tracks, size)
        val millis = (System.nanoTime() - started) / 1e6
        println("grouping 1500 tracks: %.0f ms, %d people".format(millis, grouping.people.size))
        assertEquals(30, grouping.people.size)
        assertTrue("grouping took $millis ms", millis < 1500)
    }

    @Test
    fun steadyStateFramesDoNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("needs per-thread allocation counters", bean != null && bean.isThreadAllocatedMemorySupported)
        bean!!

        val detectors = ScriptedDetectors()
        // Every track is compared with its own embedding about once a second; what the embedder returns is not counted.
        val embedder = FakeEmbedder().also { it.shareVectors = true }
        val analyzer = DefaultFaceAnalyzerFactory().create(1920, 1080, AnalysisOptions.STANDARD, detectors, embedder, FakeThumbnails())
        val faces = List(8) { Face(it + 1, 200f + it * 210f, 300f + (it % 3) * 200f, 150f) }
        // The same list every time, so whatever is allocated is the analyzer's.
        detectors.next = faces.map { it.detection(1920, 1080) }
        val pixels = Pixels.scene(0, analyzer.inputWidth, analyzer.inputHeight)
        // Unlike the bookkeeping cropper of the other tests, this one allocates nothing itself.
        val cropper = object : FrameCropper {
            override fun crop(corners: FloatArray, outWidth: Int, outHeight: Int, outRgb: ByteArray) = outRgb.fill(1)
        }
        val thread = Thread.currentThread().id

        // Long enough for the growing arrays to pass their last doubling before the measured stretch.
        for (i in 0 until 5000) analyzer.onFrame(at30(i), pixels, cropper)
        val before = bean.getThreadAllocatedBytes(thread)
        for (i in 5000 until 7000) analyzer.onFrame(at30(i), pixels, cropper)
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        println("allocated over 2000 steady frames with 8 faces: $allocated bytes")
        assertEquals(8, analyzer.facesSoFar)
        assertTrue("identity checks ran during the measured stretch: ${embedder.embedCalls}", embedder.embedCalls > 8 * 60)
        assertTrue("$allocated bytes allocated over 2000 frames", allocated < 2000 * 8)
        analyzer.close()
    }

    private fun normalize(v: FloatArray) {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val scale = (1.0 / Math.sqrt(sum)).toFloat()
        for (i in v.indices) v[i] *= scale
    }
}
