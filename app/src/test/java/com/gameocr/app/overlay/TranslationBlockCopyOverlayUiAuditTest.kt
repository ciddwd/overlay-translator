package com.gameocr.app.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBlockCopyOverlayUiAuditTest {
    @Test
    fun copyOverlay_isIndependentSelectableAndKeepsTranslationStyle() {
        val overlaySource = sourceFile(
            "src/main/java/com/gameocr/app/overlay/TranslationBlockCopyOverlay.kt",
        ).readText()
        val cardSource = sourceFile(
            "src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt",
        ).readText()
        val serviceSource = sourceFile(
            "src/main/java/com/gameocr/app/service/CaptureService.kt",
        ).readText()
        data class Case(val name: String, val source: String, val marker: String)

        listOf(
            Case("dedicated copy overlay", overlaySource, "class TranslationBlockCopyOverlay"),
            Case("source section", overlaySource, "R.string.translation_block_copy_panel_source"),
            Case("translation section", overlaySource, "R.string.translation_block_copy_panel_translation"),
            Case("source copy action", overlaySource, "copyToClipboard(sourceText)"),
            Case("translation copy action", overlaySource, "copyToClipboard(translation)"),
            Case("translation keeps display style", overlaySource, "applyOverlayTextStyle(settings.overlayTextStyle"),
            Case("fixed action row", overlaySource, "panel.addView(actionRow)"),
            Case("focusable dialog selection host", overlaySource, "window.clearFlags("),
            Case("service owns dedicated overlay", serviceSource, "translationBlockCopyOverlay: TranslationBlockCopyOverlay?"),
            Case("service opens dedicated overlay", serviceSource, "copyOverlay.show(source, translation, settings)"),
        ).forEach { case ->
            assertTrue(case.name, case.source.contains(case.marker))
        }

        assertTrue(
            "source and translation are independently selectable",
            overlaySource.countOccurrences("setTextIsSelectable(true)") >= 2,
        )
        assertTrue(
            "copy actions remain below the scrolling text",
            overlaySource.indexOf("panel.addView(actionRow)") >
                overlaySource.indexOf("panel.addView(\n            scrollView"),
        )
        assertFalse(
            "word-selection result card must not own block-copy rendering",
            cardSource.contains("showCopyPanel"),
        )
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
