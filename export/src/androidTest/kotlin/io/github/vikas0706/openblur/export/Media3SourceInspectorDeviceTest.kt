package io.github.vikas0706.openblur.export

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.vikas0706.openblur.api.UnreadableVideoException
import io.github.vikas0706.openblur.core.TransferKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Media3SourceInspectorDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val inspector = Media3SourceInspector(context)
    private val directory = File(context.cacheDir, "inspector-test")

    @Before
    fun setUp() {
        directory.deleteRecursively()
        check(directory.mkdirs())
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun describesAClipWithVideoAndAudio() = runBlocking {
        val file = File(directory, "clip.mp4")
        TestVideoFactory.create(file, TestVideoFactory.Spec(width = 320, height = 240, frames = 60, frameRate = 30))

        val info = inspector.inspect(Uri.fromFile(file))

        assertEquals("video/avc", info.mimeType)
        assertEquals(320, info.codedWidth)
        assertEquals(240, info.codedHeight)
        assertEquals(0, info.rotationDegrees)
        assertEquals(60, info.frameCount)
        assertEquals(30f, info.frameRate, 1.5f)
        assertTrue("duration ${info.durationUs}", info.durationUs in 1_900_000L..2_200_000L)
        assertTrue("bitrate ${info.bitrate}", info.bitrate > 0)
        // The clip asks for a keyframe every second, but an encoder is free to place them differently.
        assertTrue("keyframe interval ${info.keyframeIntervalSeconds}", info.keyframeIntervalSeconds > 0f && info.keyframeIntervalSeconds <= 2.1f)
        assertEquals(TransferKind.SDR, info.transfer)
        assertEquals(8, info.bitDepth)
        assertFalse(info.isDolbyVision)
        assertEquals("audio/mp4a-latm", info.audioMimeType)
        assertEquals(1, info.audioTrackCount)
        assertFalse(info.hasLocation)
        assertEquals(file.length(), info.fileSizeBytes)
    }

    @Test
    fun reportsRotationAndLocation() = runBlocking {
        val file = File(directory, "rotated.mp4")
        TestVideoFactory.create(
            file,
            TestVideoFactory.Spec(frames = 30, withAudio = false, orientationHint = 90, latitude = 12.5f, longitude = 45.25f),
        )

        val info = inspector.inspect(Uri.fromFile(file))

        assertEquals(90, info.rotationDegrees)
        assertEquals(320, info.codedWidth)
        assertEquals(240, info.codedHeight)
        assertEquals(240, info.displayWidth)
        assertEquals(320, info.displayHeight)
        assertTrue(info.hasLocation)
        assertNull(info.audioMimeType)
        assertEquals(0, info.audioTrackCount)
    }

    @Test
    fun rejectsAFileThatIsNotAVideo() = runBlocking {
        val file = File(directory, "notes.mp4")
        file.writeText("This is text, not a video. ".repeat(200))
        expectUnreadable(Uri.fromFile(file))
    }

    @Test
    fun rejectsAMissingFile() = runBlocking {
        expectUnreadable(Uri.fromFile(File(directory, "missing.mp4")))
    }

    @Test
    fun rejectsAddressesThatAreNotOnTheDevice() = runBlocking {
        // Refused by scheme before anything is opened, so no connection is attempted.
        expectUnreadable(Uri.parse("https://example.invalid/clip.mp4"))
    }

    @Test
    fun displayNameOfAFileIsItsLastPathSegment() = runBlocking {
        val file = File(directory, "holiday clip.mp4")
        file.writeText("x")
        assertEquals("holiday clip.mp4", inspector.displayName(Uri.fromFile(file)))
    }

    private suspend fun expectUnreadable(uri: Uri) {
        try {
            val info = inspector.inspect(uri)
            fail("Expected UnreadableVideoException, got $info")
        } catch (expected: UnreadableVideoException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }
}
