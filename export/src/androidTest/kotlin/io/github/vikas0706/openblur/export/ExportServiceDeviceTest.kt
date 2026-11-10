package io.github.vikas0706.openblur.export

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.media3.common.Effect
import androidx.media3.effect.RgbFilter
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.vikas0706.openblur.api.ExportPhase
import io.github.vikas0706.openblur.api.ExportRequest
import io.github.vikas0706.openblur.api.ExportState
import io.github.vikas0706.openblur.api.MaskEffectController
import io.github.vikas0706.openblur.api.MaskEffectFactory
import io.github.vikas0706.openblur.core.EncodePlan
import io.github.vikas0706.openblur.core.EncoderCandidate
import io.github.vikas0706.openblur.core.EncoderPolicy
import io.github.vikas0706.openblur.core.ExportQuality
import io.github.vikas0706.openblur.core.MaskBatch
import io.github.vikas0706.openblur.core.MaskTimeline
import io.github.vikas0706.openblur.core.PlanNotice
import io.github.vikas0706.openblur.core.SourceVideoInfo
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real controller, service, Transformer and MediaStore with a generated clip. The mask
 * effect is replaced by a stock Media3 filter because the render module is not part of this test.
 */
@RunWith(AndroidJUnit4::class)
class ExportServiceDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val inspector = Media3SourceInspector(context)
    private val directory = File(context.cacheDir, "export-test")
    private val exportsDirectory = File(context.cacheDir, "exports")
    private lateinit var scenario: ActivityScenario<TestHostActivity>
    private lateinit var controller: DefaultExportController

    @Before
    fun setUp() {
        // Android 9 and older need WRITE_EXTERNAL_STORAGE granted by hand before a save can succeed.
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        directory.deleteRecursively()
        check(directory.mkdirs())
        scenario = ActivityScenario.launch(TestHostActivity::class.java)
        onMain {
            controller = DefaultExportController(context, H264Policy, GrayscaleMaskEffects, inspector)
            controller.reset()
        }
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) {
            onMain { controller.cancel() }
            runBlocking { withTimeout(30_000) { controller.state.first { it !is ExportState.Running } } }
            onMain { controller.reset() }
        }
        if (::scenario.isInitialized) scenario.close()
        directory.deleteRecursively()
    }

    @Test
    fun exportsAClipAndSavesItWithTheSourceShapeAndUntouchedAudio() {
        val source = File(directory, "source.mp4")
        TestVideoFactory.create(source, TestVideoFactory.Spec(frames = 60, latitude = 12.5f, longitude = 45.25f))
        val info = runBlocking { inspector.inspect(Uri.fromFile(source)) }
        assertTrue(info.hasLocation)
        val name = "openblur-export-test-${System.currentTimeMillis()}"

        val plans = runBlocking { controller.plan(info, ExportQuality.MATCH_SOURCE) }
        assertTrue("This device lists no H.264 encoder", plans.isNotEmpty())

        onMain { controller.start(request(source, name, info, removeLocation = true)) }
        val end = awaitEnd()

        assertTrue("Export ended as $end", end is ExportState.Succeeded)
        val report = (end as ExportState.Succeeded).report
        try {
            assertEquals("$name.mp4", report.displayName)
            assertEquals("video/avc", report.output.mimeType)
            assertEquals(info.codedWidth, report.output.codedWidth)
            assertEquals(info.codedHeight, report.output.codedHeight)
            assertEquals(info.rotationDegrees, report.output.rotationDegrees)
            assertTrue(
                "frames ${info.frameCount} -> ${report.output.frameCount}",
                abs(info.frameCount - report.output.frameCount) <= 1,
            )
            assertTrue(report.audioCopied)
            assertEquals(info.audioMimeType, report.output.audioMimeType)
            assertEquals(1, report.output.audioTrackCount)
            assertFalse("Location should have been removed", report.output.hasLocation)
            assertFalse(report.notices.contains(PlanNotice.SIZE_MAY_BE_ADJUSTED))
            assertFalse(report.notices.contains(PlanNotice.AUDIO_REENCODED))
            assertEquals(info, report.source)
            assertEquals(emptyList<String>(), leftoverTempFiles())
        } finally {
            context.contentResolver.delete(report.outputUri, null, null)
        }
    }

    @Test
    fun keepsLocationWhenNotAskedToRemoveIt() {
        val source = File(directory, "located.mp4")
        TestVideoFactory.create(source, TestVideoFactory.Spec(frames = 30, withAudio = false, latitude = 12.5f, longitude = 45.25f))
        val info = runBlocking { inspector.inspect(Uri.fromFile(source)) }
        val name = "openblur-export-test-${System.currentTimeMillis()}"

        onMain { controller.start(request(source, name, info, removeLocation = false)) }
        val end = awaitEnd()

        assertTrue("Export ended as $end", end is ExportState.Succeeded)
        val report = (end as ExportState.Succeeded).report
        try {
            assertTrue(report.output.hasLocation)
            assertEquals(0, report.output.audioTrackCount)
        } finally {
            context.contentResolver.delete(report.outputUri, null, null)
        }
    }

    @Test
    fun cancelEndsInCancelledAndLeavesNoFiles() {
        val source = File(directory, "long.mp4")
        TestVideoFactory.create(source, TestVideoFactory.Spec(width = 640, height = 480, frames = 600, withAudio = false))
        val info = runBlocking { inspector.inspect(Uri.fromFile(source)) }
        val name = "openblur-export-test-${System.currentTimeMillis()}"

        onMain { controller.start(request(source, name, info, removeLocation = false)) }
        runBlocking {
            withTimeout(60_000) {
                controller.state.first { it is ExportState.Running && it.phase == ExportPhase.ENCODING || it !is ExportState.Running }
            }
        }
        controller.cancel()
        val end = awaitEnd()

        // A fast device can finish the clip before the cancel lands; that run proves nothing either way.
        if (end is ExportState.Succeeded) context.contentResolver.delete(end.report.outputUri, null, null)
        assumeTrue("The export finished before it could be cancelled", end !is ExportState.Succeeded)
        assertEquals(ExportState.Cancelled, end)
        assertEquals(emptyList<String>(), leftoverTempFiles())
    }

    @Test
    fun startIsIgnoredWhileAnExportRuns() {
        val source = File(directory, "busy.mp4")
        TestVideoFactory.create(source, TestVideoFactory.Spec(frames = 90, withAudio = false))
        val info = runBlocking { inspector.inspect(Uri.fromFile(source)) }
        val name = "openblur-export-test-${System.currentTimeMillis()}"

        onMain {
            controller.start(request(source, name, info, removeLocation = false))
            controller.start(request(source, "$name-second", info, removeLocation = false))
        }
        val end = awaitEnd()

        assertTrue("Export ended as $end", end is ExportState.Succeeded)
        val report = (end as ExportState.Succeeded).report
        context.contentResolver.delete(report.outputUri, null, null)
        assertEquals("$name.mp4", report.displayName)
    }

    private fun request(source: File, name: String, info: SourceVideoInfo, removeLocation: Boolean) = ExportRequest(
        sourceUri = Uri.fromFile(source),
        displayName = name,
        info = info,
        timeline = NoMasks(info.durationUs),
        quality = ExportQuality.MATCH_SOURCE,
        removeLocation = removeLocation,
    )

    private fun awaitEnd(): ExportState = runBlocking {
        withTimeout(180_000) { controller.state.first { it !is ExportState.Running && it !is ExportState.Idle } }
    }

    private fun leftoverTempFiles(): List<String> = exportsDirectory.list()?.toList() ?: emptyList()

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    /** One strict H.264 plan and one loose one, which every device and the emulator can encode. */
    private object H264Policy : EncoderPolicy {
        override fun plan(source: SourceVideoInfo, encoders: List<EncoderCandidate>, quality: ExportQuality): List<EncodePlan> {
            if (encoders.none { it.mimeType == "video/avc" }) return emptyList()
            val strict = EncodePlan("video/avc", 1_000_000, 1f, keepHdr = false, strictSize = true, notices = emptySet())
            return listOf(strict, strict.copy(strictSize = false, notices = setOf(PlanNotice.SIZE_MAY_BE_ADJUSTED)))
        }
    }

    private object GrayscaleMaskEffects : MaskEffectFactory {
        override fun create(): MaskEffectController = object : MaskEffectController {
            override val effect: Effect = RgbFilter.createGrayscaleFilter()

            override fun setTimeline(timeline: MaskTimeline?) = Unit

            override fun setTimeOffsetUs(offsetUs: Long) = Unit
        }
    }

    private class NoMasks(override val durationUs: Long) : MaskTimeline {
        override val isEmpty: Boolean = true

        override fun sample(timeUs: Long, out: MaskBatch, includeHidden: Boolean) {
            out.clear()
        }
    }
}
