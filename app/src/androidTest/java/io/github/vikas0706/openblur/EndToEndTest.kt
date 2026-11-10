package io.github.vikas0706.openblur

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vikas0706.openblur.api.AnalysisEvent
import io.github.vikas0706.openblur.api.AppServicesOwner
import io.github.vikas0706.openblur.api.ExportReport
import io.github.vikas0706.openblur.api.ExportRequest
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.core.AnalysisOptions
import io.github.vikas0706.openblur.core.AnalysisResult
import io.github.vikas0706.openblur.core.EditState
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import io.github.vikas0706.openblur.shell.MainActivity
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Scans and exports real videos with the real services, then checks the saved file against its source.
 *
 * Needs the test videos on the device first (bash _tools/make_test_videos.sh builds them):
 *   adb shell mkdir -p /data/local/tmp/openblur-tests
 *   adb push _dl/testvideos/. /data/local/tmp/openblur-tests/
 * Run: ./gradlew :app:connectedDebugAndroidTest
 * A summary is written to the app's external files folder as e2e-report.txt.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {

    private class Case(
        val file: String,
        val displayWidth: Int,
        val displayHeight: Int,
        val minPeople: Int,
        val maxPeople: Int,
        /** Null when the source has no audio. */
        val expectAudioCopied: Boolean?,
    )

    @Test fun cutsAndRegrouping(): Unit = run(Case("t1_h264_1080p30_cuts_aac.mp4", 1920, 1080, 10, 14, true))

    @Test fun portraitRotated(): Unit = run(Case("t2_hevc_portrait_rot90_aac.mp4", 1080, 1920, 6, 9, true))

    @Test fun variableFrameRate(): Unit = run(Case("t3_h264_720p60_vfr_aac.mp4", 1280, 720, 3, 4, true))

    @Test fun hdrHlg4k(): Unit = run(Case("t4_hevc_4k30_hlg10_aac.mp4", 3840, 2160, 6, 9, true))

    @Test fun noFacesNoAudio(): Unit = run(Case("t5_h264_360p25_nofaces_noaudio.mp4", 640, 360, 0, 0, null))

    @Test fun h264At4k60(): Unit = run(Case("t6_h264_4k60_aac.mp4", 3840, 2160, 3, 4, true))

    @Test fun smallFaces(): Unit = run(Case("t7_h264_1080p30_smallfaces_aac.mp4", 1920, 1080, 4, 9, true))

    @Test fun ac3AudioIsReencoded(): Unit = run(Case("t8_h264_720p30_ac3.mp4", 1280, 720, 3, 4, false))

    private fun run(case: Case): Unit = runBlocking {
        val source = copyFromDevice(case.file)
        assumeTrue("Push ${case.file} to $DEVICE_DIR first", source != null)
        val uri = Uri.fromFile(source!!)
        val log = StringBuilder("== ${case.file}\n")

        val info = services.inspector.inspect(uri)
        log.append("source: ${describe(info)}\n")
        assertEquals("display width", case.displayWidth, info.displayWidth)
        assertEquals("display height", case.displayHeight, info.displayHeight)

        val projectId = services.projects.newProjectId()
        try {
            val scanStart = System.nanoTime()
            val result = withTimeout(SCAN_TIMEOUT_MS) {
                services.analyzer.analyze(uri, info, AnalysisOptions.STANDARD, services.projects.thumbnails(projectId))
                    .filterIsInstance<AnalysisEvent.Finished>()
                    .first()
                    .result
            }
            val scanMs = (System.nanoTime() - scanStart) / 1_000_000
            log.append(
                "scan: ${scanMs} ms, frames seen ${result.framesSeen}, detected ${result.framesDetected}, " +
                    "tracks ${result.tracks.size}, people ${result.people.size}, cuts ${result.cutsUs.size}\n",
            )
            assertEquals("scan frame size", info.displayWidth, result.frameWidth)
            assertEquals("scan frame size", info.displayHeight, result.frameHeight)
            if (info.frameCount > 0) {
                assertTrue("every frame is scanned", abs(result.framesSeen - info.frameCount) <= 1)
            }
            assertTrue(
                "people ${result.people.size} not in ${case.minPeople}..${case.maxPeople}",
                result.people.size in case.minPeople..case.maxPeople,
            )
            checkTracks(result)

            val timeline = services.timelines.build(result, EditState())
            val report = export(uri, info, timeline, case.file)
            log.append("plan: ${report.plan}\n")
            log.append("output: ${describe(report.output)}\n")
            log.append("notices: ${report.notices}, audio copied ${report.audioCopied}, ${report.elapsedMs} ms\n")

            try {
                checkOutput(case, info, report)
                if (!info.isHdr && result.tracks.isNotEmpty()) {
                    log.append(comparePixels(source, report.outputUri, info, timeline))
                }
                context.contentResolver.delete(report.outputUri, null, null)
            } catch (t: Throwable) {
                log.append("kept output for inspection: Movies/OpenBlur/${report.displayName}\n")
                throw t
            }
        } catch (t: Throwable) {
            log.append("FAILED: $t\n")
            throw t
        } finally {
            services.projects.delete(projectId)
            source.delete()
            appendReport(log.toString())
            Log.i(TAG, log.toString())
        }
    }

    private fun checkTracks(result: AnalysisResult) {
        for (track in result.tracks) {
            assertTrue("track ${track.id} has two samples", track.sampleCount >= 2)
            for (i in 1 until track.sampleCount) {
                assertTrue("track ${track.id} times increase", track.timesUs[i] > track.timesUs[i - 1])
            }
            assertTrue("track ${track.id} inside the video", track.startUs >= 0 && track.endUs <= result.durationUs + 1)
            assertTrue("track ${track.id} has a person", result.person(track.personId) != null)
        }
        val owned = result.people.flatMap { it.trackIds.toList() }
        assertEquals("every track belongs to exactly one person", result.tracks.map { it.id }.sorted(), owned.sorted())
    }

    private fun checkOutput(case: Case, source: SourceVideoInfo, report: ExportReport) {
        val out = report.output
        assertEquals("coded width", source.codedWidth, out.codedWidth)
        assertEquals("coded height", source.codedHeight, out.codedHeight)
        assertEquals("rotation", source.rotationDegrees, out.rotationDegrees)
        if (PlanNotice.CODEC_CHANGED_TO_H264 !in report.notices && PlanNotice.CODEC_CHANGED_TO_HEVC !in report.notices) {
            assertEquals("codec", source.mimeType, out.mimeType)
        }
        if (source.frameCount > 0) {
            assertTrue("frame count ${out.frameCount} vs ${source.frameCount}", abs(out.frameCount - source.frameCount) <= 1)
        }
        assertTrue("duration", abs(out.durationUs - source.durationUs) <= 70_000)
        if (PlanNotice.HDR_TONE_MAPPED_TO_SDR !in report.notices) {
            assertEquals("transfer", source.transfer, out.transfer)
        }
        if (case.expectAudioCopied != null) {
            assertEquals("audio copied", case.expectAudioCopied, report.audioCopied)
            assertTrue("audio present", out.audioMimeType != null)
            if (!case.expectAudioCopied) assertTrue(PlanNotice.AUDIO_REENCODED in report.notices)
        } else {
            assertEquals("no audio track invented", null, out.audioMimeType)
        }
        assertTrue("plan bitrate ${report.plan.bitrate} below source ${source.bitrate}", report.plan.bitrate >= source.bitrate)
    }

    /** Compares the same frames in source and output: faces must lose their detail, everything else must stay. */
    private fun comparePixels(source: File, output: Uri, info: SourceVideoInfo, timeline: MaskTimeline): String {
        val batch = MaskBatch()
        val sb = StringBuilder()
        val src = ExactFrames(context, Uri.fromFile(source))
        val out = ExactFrames(context, output)
        var checked = 0
        var worst = 99.0
        val psnrs = ArrayList<Double>()
        for (fraction in floatArrayOf(0.1f, 0.2f, 0.3f, 0.45f, 0.6f, 0.7f, 0.8f, 0.9f)) {
            val t = (info.durationUs * fraction).toLong()
            val a = src.lumaAt(t) ?: continue
            val b = out.lumaAt(a.timeUs) ?: continue
            assertTrue("same frame time ${a.timeUs} vs ${b.timeUs}", abs(a.timeUs - b.timeUs) <= 1)
            timeline.sample(a.timeUs, batch)
            if (batch.count == 0) continue
            val (w, h) = if (info.rotationDegrees % 180 == 0) a.width to a.height else a.height to a.width
            val ya = upright(a, info.rotationDegrees)
            val yb = upright(b, info.rotationDegrees)
            toFullRange(ya, a.fullRange)
            toFullRange(yb, b.fullRange)
            val inside = sharpnessRatio(ya, yb, w, h, batch)
            val psnr = psnrOutside(ya, yb, w, h, batch)
            worst = min(worst, psnr)
            psnrs += psnr
            sb.append("frame at ${a.timeUs / 1000} ms: ${batch.count} masks, detail kept inside ${"%.3f".format(inside)}, ")
            sb.append("PSNR outside ${"%.1f".format(psnr)} dB\n")
            if (psnr < 36.0) {
                sb.append("  PSNR by 8x8 region, top row first: $lastErrorMap\n")
                dumpFrame("e2e-${a.timeUs}", w, h, ya, yb, batch)
            }
            assertTrue("faces still sharp at ${a.timeUs} us (ratio $inside)", inside < 0.25f)
            checked++
        }
        assertTrue("no frame with masks could be compared", checked > 0)
        val mean = psnrs.average()
        sb.append("PSNR outside the masks: mean ${"%.1f".format(mean)} dB, worst ${"%.1f".format(worst)} dB\n")
        // The hardware encoder's first frames are softer while its rate control settles, so the floor is lower than the mean.
        assertTrue("picture outside the masks changed (mean $mean dB)\n$sb", mean >= 40.0)
        assertTrue("picture outside the masks changed (worst $worst dB)\n$sb", worst >= 32.0)
        return sb.toString()
    }

    /** Limited-range luma (16..235) stretched to 0..255 so files with different ranges compare fairly. */
    private fun toFullRange(y: FloatArray, fullRange: Boolean) {
        if (fullRange) return
        for (i in y.indices) y[i] = ((y[i] - 16f) * 255f / 219f).coerceIn(0f, 255f)
    }

    /** Luma turned to display orientation, as floats. */
    private fun upright(l: ExactFrames.Luma, rotation: Int): FloatArray {
        val w = l.width
        val h = l.height
        if (rotation % 360 == 0) return FloatArray(w * h) { (l.y[it].toInt() and 0xFF).toFloat() }
        val dw = if (rotation % 180 == 0) w else h
        val dh = if (rotation % 180 == 0) h else w
        val out = FloatArray(dw * dh)
        for (row in 0 until dh) for (col in 0 until dw) {
            val (sx, sy) = when ((rotation % 360 + 360) % 360) {
                90 -> row to (h - 1 - col)
                180 -> (w - 1 - col) to (h - 1 - row)
                else -> (w - 1 - row) to col
            }
            out[row * dw + col] = (l.y[sy * w + sx].toInt() and 0xFF).toFloat()
        }
        return out
    }

    /** Mask bounding half-extents in normalized frame units, x then y. */
    private fun extents(batch: MaskBatch, i: Int, aspect: Float): Pair<Float, Float> {
        val g = batch.geometry
        val o = i * MaskBatch.GEOMETRY_STRIDE
        val hw = g[o + 2]
        val hh = g[o + 3]
        val r = g[o + 4]
        val ex = abs(cos(r)) * hw + abs(sin(r)) * hh
        val ey = abs(sin(r)) * hw + abs(cos(r)) * hh
        return ex / aspect to ey
    }

    private fun sharpnessRatio(ya: FloatArray, yb: FloatArray, w: Int, h: Int, batch: MaskBatch): Float {
        var before = 0.0
        var after = 0.0
        for (i in 0 until batch.count) {
            val (ex, ey) = extents(batch, i, w.toFloat() / h)
            val cx = batch.geometry[i * MaskBatch.GEOMETRY_STRIDE]
            val cy = batch.geometry[i * MaskBatch.GEOMETRY_STRIDE + 1]
            val x0 = ((cx - ex * 0.45f) * w).toInt().coerceIn(1, w - 2)
            val x1 = ((cx + ex * 0.45f) * w).toInt().coerceIn(1, w - 2)
            val y0 = ((cy - ey * 0.45f) * h).toInt().coerceIn(1, h - 2)
            val y1 = ((cy + ey * 0.45f) * h).toInt().coerceIn(1, h - 2)
            if (x1 - x0 < 4 || y1 - y0 < 4) continue
            before += laplacianVariance(ya, w, x0, x1, y0, y1)
            after += laplacianVariance(yb, w, x0, x1, y0, y1)
        }
        return if (before <= 0.0) 0f else (after / before).toFloat()
    }

    private fun laplacianVariance(y: FloatArray, w: Int, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (row in y0..y1) for (col in x0..x1) {
            val i = row * w + col
            val l = 4 * y[i] - y[i - 1] - y[i + 1] - y[i - w] - y[i + w]
            sum += l
            sumSq += l * l
            n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Writes both luma frames as PGM and the masks as text, for inspection with adb pull. */
    private fun dumpFrame(name: String, w: Int, h: Int, ya: FloatArray, yb: FloatArray, batch: MaskBatch) {
        val dir = context.getExternalFilesDir(null) ?: return
        for ((suffix, y) in listOf("src" to ya, "out" to yb)) {
            File(dir, "$name-$suffix.pgm").outputStream().buffered().use { o ->
                o.write("P5\n$w $h\n255\n".toByteArray())
                for (v in y) o.write(v.toInt().coerceIn(0, 255))
            }
        }
        File(dir, "$name-masks.txt").writeText(
            (0 until batch.count).joinToString("\n") { i ->
                (0 until MaskBatch.GEOMETRY_STRIDE).joinToString(" ") { batch.geometry[i * MaskBatch.GEOMETRY_STRIDE + it].toString() } +
                    " shape=${batch.shape[i]} kind=${batch.kind[i]} source=${batch.sourceId[i]}"
            },
        )
    }

    private val cellError = DoubleArray(64)
    private val cellCount = IntArray(64)
    private var lastErrorMap = ""

    private fun psnrOutside(ya: FloatArray, yb: FloatArray, w: Int, h: Int, batch: MaskBatch): Double {
        var se = 0.0
        var n = 0
        for (row in 0 until h step 2) for (col in 0 until w step 2) {
            val nx = (col + 0.5f) / w
            val ny = (row + 0.5f) / h
            var near = false
            for (i in 0 until batch.count) {
                val (ex, ey) = extents(batch, i, w.toFloat() / h)
                val dx = (nx - batch.geometry[i * MaskBatch.GEOMETRY_STRIDE]) / (ex * 1.3f + 0.01f)
                val dy = (ny - batch.geometry[i * MaskBatch.GEOMETRY_STRIDE + 1]) / (ey * 1.3f + 0.01f)
                if (sqrt(dx * dx + dy * dy) < 1f) {
                    near = true
                    break
                }
            }
            if (near) continue
            val d = (ya[row * w + col] - yb[row * w + col]).toDouble()
            se += d * d
            n++
            val cell = (ny * 8).toInt().coerceIn(0, 7) * 8 + (nx * 8).toInt().coerceIn(0, 7)
            cellError[cell] += d * d
            cellCount[cell]++
        }
        lastErrorMap = (0 until 64).joinToString(" ") { c ->
            val e = if (cellCount[c] == 0) 0.0 else cellError[c] / cellCount[c]
            (if (e < 1e-9) 99 else (10 * log10(255.0 * 255.0 / e)).toInt()).toString() + if (c % 8 == 7) " |" else ""
        }
        cellError.fill(0.0)
        cellCount.fill(0)
        if (n == 0) return 99.0
        val mse = se / n
        return if (mse <= 1e-9) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    private suspend fun export(uri: Uri, info: SourceVideoInfo, timeline: MaskTimeline, name: String): ExportReport {
        val exporter = services.exporter
        instrumentation.runOnMainSync { exporter.reset() }
        val request = ExportRequest(
            sourceUri = uri,
            displayName = "e2e " + name.substringBeforeLast('.'),
            info = info,
            timeline = timeline,
            quality = ExportQuality.MATCH_SOURCE,
            removeLocation = false,
        )
        instrumentation.runOnMainSync { exporter.start(request) }
        val end = withTimeout(EXPORT_TIMEOUT_MS) {
            exporter.state.first { it is ExportState.Succeeded || it is ExportState.Failed || it is ExportState.Cancelled }
        }
        instrumentation.runOnMainSync { exporter.reset() }
        return when (end) {
            is ExportState.Succeeded -> end.report
            is ExportState.Failed -> throw AssertionError("export failed: ${end.failure} ${end.detail}")
            else -> throw AssertionError("export ended as $end")
        }
    }

    private fun describe(i: SourceVideoInfo) =
        "${i.mimeType} ${i.codedWidth}x${i.codedHeight} rot ${i.rotationDegrees} ${"%.2f".format(i.frameRate)} fps " +
            "${i.bitrate / 1000} kbps ${i.frameCount} frames ${i.transfer} ${i.bitDepth}-bit audio ${i.audioMimeType}"

    private fun copyFromDevice(name: String): File? {
        val dest = File(context.cacheDir, "e2e/$name").apply { parentFile?.mkdirs() }
        val pfd = instrumentation.uiAutomation.executeShellCommand("cat $DEVICE_DIR/$name")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> dest.outputStream().use { input.copyTo(it) } }
        return if (dest.length() > 0) dest else null.also { dest.delete() }
    }

    private fun appendReport(text: String) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, "e2e-report.txt").appendText(text + "\n")
    }

    companion object {
        private const val TAG = "OpenBlurE2E"
        private const val DEVICE_DIR = "/data/local/tmp/openblur-tests"
        private const val SCAN_TIMEOUT_MS = 10 * 60_000L
        private const val EXPORT_TIMEOUT_MS = 15 * 60_000L

        private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
        private val context get() = instrumentation.targetContext
        private val services get() = (context.applicationContext as AppServicesOwner).services

        private var scenario: ActivityScenario<MainActivity>? = null

        /** Keeps the app in the foreground so the export service may start. */
        @BeforeClass
        @JvmStatic
        fun launchApp() {
            scenario = ActivityScenario.launch(MainActivity::class.java)
        }

        @AfterClass
        @JvmStatic
        fun closeApp() {
            scenario?.close()
        }
    }
}
