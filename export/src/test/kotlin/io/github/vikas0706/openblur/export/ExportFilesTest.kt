package io.github.vikas0706.openblur.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFilesTest {
    @Test
    fun tempFilesAndMarkersCountAsAnUnfinishedExport() {
        assertTrue(ExportFiles.isExportLeftover("export-3f2a9c1e-0b7d-4e8a-9f1c-2d3e4f5a6b7c.mp4"))
        assertTrue(ExportFiles.isExportLeftover("export-3f2a9c1e-0b7d-4e8a-9f1c-2d3e4f5a6b7c.pending"))
    }

    @Test
    fun otherFilesDoNot() {
        assertFalse(ExportFiles.isExportLeftover("notes.txt"))
        assertFalse(ExportFiles.isExportLeftover("holiday.mp4"))
        assertFalse(ExportFiles.isExportLeftover("export-3f2a9c1e.mp4.tmp"))
    }
}
