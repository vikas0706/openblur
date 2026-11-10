package io.github.vikas0706.openblur.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetModelsTest {
    // A library's test APK instruments itself, so the models packaged with these tests are its app assets.
    private val testContext: Context = ApplicationProvider.getApplicationContext()

    private fun plainRead(name: String): ByteArray = testContext.assets.open(name).use { it.readBytes() }

    @Test
    fun detectorModelIsReadCompletely() {
        val bytes = AssetModels(testContext).detectorModel()
        assertTrue("size ${bytes.size}", bytes.size > 100_000)
        // An ONNX file is a protobuf whose first field is the IR version, tag byte 0x08.
        assertEquals(0x08, bytes[0].toInt())
        assertArrayEquals(plainRead(AssetModels.DETECTOR_ASSET), bytes)
    }

    @Test
    fun embedderModelIsReadCompletely() {
        val bytes = AssetModels(testContext).embedderModel()
        assertTrue("size ${bytes.size}", bytes.size > 1_000_000)
        assertEquals(0x08, bytes[0].toInt())
        assertArrayEquals(plainRead(AssetModels.EMBEDDER_ASSET), bytes)
    }

    @Test
    fun missingModelIsAnIoErrorThatNamesTheAsset() {
        val error = assertThrows(IOException::class.java) { AssetModels(testContext).read("no-such-model.onnx") }
        assertTrue(error.message!!.contains("no-such-model.onnx"))
    }

    @Test
    fun instrumentationContextWorksToo() {
        val bytes = AssetModels(InstrumentationRegistry.getInstrumentation().context).detectorModel()
        assertArrayEquals(plainRead(AssetModels.DETECTOR_ASSET), bytes)
    }
}
