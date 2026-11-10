package io.github.vikas0706.openblur.vision

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The decode and the thresholds in this module were written and measured against exactly these files. */
class ModelFilesTest {
    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    @Test
    fun yunetIsTheFileTheDecoderWasWrittenFor() {
        assertEquals(
            "104ee26c71d5c270ce79a6b3dced91c91013260d29a6bf18f9296c5b8c4c2b12",
            sha256(TestModels.yunetFile),
        )
    }

    @Test
    fun sfaceIsTheQuantizedFileTheThresholdsWereMeasuredOn() {
        assertEquals(
            "2b0e941e6f16cc048c20aee0c8e31f569118f65d702914540f7bfdc14048d78a",
            sha256(TestModels.sfaceFile),
        )
    }
}
