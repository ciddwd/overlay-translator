package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureContentOrientationChipUiTest {
    @Test
    fun captureContentOrientation_usesExistingChipStyle_tableDrivenContract() {
        val source = moduleFile(
            "src/main/java/com/gameocr/app/ui/SettingsScreen.kt"
        ).readText()
        val start = source.indexOf("val captureOrientationOptions =")
        val end = source.indexOf(
            "R.string.settings_capture_content_orientation_summary",
            start,
        )
        val block = source.substring(start, end)

        data class Case(val name: String, val marker: String)

        listOf(
            Case("auto option", "CaptureContentOrientation.AUTO"),
            Case("landscape option", "CaptureContentOrientation.LANDSCAPE"),
            Case("portrait option", "CaptureContentOrientation.PORTRAIT"),
            Case("shared chip component", "EngineChip("),
            Case("selection value", "current = captureContentOrientation"),
            Case("skip redundant save", "if (captureContentOrientation != selected)"),
            Case("persist selection", "viewModel.saveCaptureContentOrientation(selected)"),
        ).forEach { case ->
            assertTrue("${case.name}: missing ${case.marker}", block.contains(case.marker))
        }
        assertFalse("must not use a segmented row", block.contains("SingleChoiceSegmentedButtonRow"))
        assertFalse("must not use a segmented button", block.contains("SegmentedButton("))
    }

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Module file not found: $path")
}
