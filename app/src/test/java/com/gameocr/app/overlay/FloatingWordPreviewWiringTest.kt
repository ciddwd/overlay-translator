package com.gameocr.app.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWordPreviewWiringTest {

    @Test
    fun compactPreview_smoke_hasThreeEllipsizedLinesAndIndependentSpeaker() {
        val source = source("app/src/main/java/com/gameocr/app/overlay/DraggableOverlayWindow.kt")
        val card = source.substring(
            source.indexOf("private fun buildWordPreviewCard("),
            source.indexOf("private fun lockIconRes", source.indexOf("private fun buildWordPreviewCard(")),
        )

        data class Case(val name: String, val marker: String)
        listOf(
            Case("every text field is one line", "maxLines = 1"),
            Case("overflow is ellipsized", "ellipsize = TextUtils.TruncateAt.END"),
            Case("word row carries the optional speaker", "R.drawable.ic_volume_up"),
            Case("speaker owns a separate click action", "setOnClickListener { speak() }"),
            Case("whole card opens complete details", "setOnClickListener { onOpenDetails() }"),
        ).forEach { case -> assertTrue(case.name, card.contains(case.marker)) }

        assertEquals("three preview text fields", 3, card.count("oneLineText(") - 1)
    }

    @Test
    fun floatingWordEntry_smoke_isLimitedToFloatingPresentation() {
        val manager = source("app/src/main/java/com/gameocr/app/overlay/OverlayManager.kt")
        val service = source("app/src/main/java/com/gameocr/app/service/CaptureService.kt")
        val floatingBuilder = manager.substring(
            manager.indexOf("private fun buildFloatingContent("),
            manager.indexOf("private fun buildFloatingWindowText(", manager.indexOf("private fun buildFloatingContent(")),
        )

        assertTrue(floatingBuilder.contains("configureFloatingEnglishWordTap(contentView)"))
        assertTrue(manager.contains("textView.getOffsetForPosition(x, y)"))
        assertTrue(manager.contains("floatingEnglishWordAt(textView.text, offset)"))
        assertTrue(service.contains("onFloatingWordLookupRequested = ::lookupFloatingEnglishWord"))
        assertTrue(service.contains("onFloatingWordDetailsRequested = ::showFloatingEnglishWordDetails"))
        assertEquals(
            "the tap entry is wired once and declared once; Blocks keep their existing path",
            2,
            manager.count("configureFloatingEnglishWordTap("),
        )
    }

    private fun String.count(marker: String): Int = windowed(marker.length).count { it == marker }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
