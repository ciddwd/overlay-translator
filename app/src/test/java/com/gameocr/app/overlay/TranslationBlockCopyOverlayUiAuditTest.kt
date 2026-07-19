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
            Case("service opens dedicated overlay", serviceSource, "copyOverlay.show("),
            Case("source selection speech callback", overlaySource, "onSpeakSourceSelection"),
            Case("translation selection speech callback", overlaySource, "onSpeakTranslationSelection"),
            Case("selection speech follows TTS enabled state", serviceSource, "if (!settings.ttsEnabled) return null"),
            Case("source selection uses source language", serviceSource, "settings.copy(targetLang = settings.sourceLang)"),
            Case("translation selection uses target language", serviceSource, "role = \"block_translation_selection\""),
            Case("source title speaker reads the full source", overlaySource, "speechText = sourceText"),
            Case("translation title speaker reads the full translation", overlaySource, "speechText = translation"),
            Case("title speakers use the compact shared metrics", overlaySource, "translationCardSpeechButtonMetrics(density)"),
            Case("title speakers use the verified Material icon", overlaySource, "R.drawable.ic_volume_up"),
            Case("title speakers bind live playback state", overlaySource, "bindTtsPlaybackState(action"),
        ).forEach { case ->
            assertTrue(case.name, case.source.contains(case.marker))
        }

        assertTrue(
            "source and translation are independently selectable",
            overlaySource.countOccurrences("setTextIsSelectable(true)") >= 2,
        )
        assertTrue(
            "source and translation each use a section header",
            overlaySource.countOccurrences("sectionHeader(") >= 3,
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
