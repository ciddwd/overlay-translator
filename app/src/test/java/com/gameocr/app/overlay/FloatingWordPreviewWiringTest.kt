package com.gameocr.app.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWordPreviewWiringTest {

    @Test
    fun compactPreview_smoke_hasGroupedEllipsizedLinesAndExplicitDetailsAction() {
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
            Case("speaker uses the shared card metrics", "translationCardSpeechButtonMetrics(density)"),
            Case("speaker follows the shared action color", "valueOf(accentColor)"),
            Case("whole card opens complete details", "setOnClickListener { onOpenDetails() }"),
            Case("visible text action opens complete details", "R.string.floating_word_view_details"),
            Case("action uses borderless button feedback", "applyBorderlessSelectableBackground()"),
            Case("preview follows translation typography", "applyOverlayTextStyle(style, typeface)"),
            Case("preview follows translation text size", "baseTextSizeSp = textSizeSp.coerceIn"),
            Case("one line is created for every grouped sense", "content.lines.forEachIndexed"),
        ).forEach { case -> assertTrue(case.name, card.contains(case.marker)) }
    }

    @Test
    fun translationCardSource_smoke_reusesTheCompactWordLookup() {
        val card = source("app/src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt")
        val service = source("app/src/main/java/com/gameocr/app/service/CaptureService.kt")

        listOf(
            "onEnglishWordTapped",
            "attachEnglishWordTapListener",
            "floatingEnglishWordAt(textView.text, offset)",
            "showEnglishWordPreview",
            "FloatingWordPreviewContent",
        ).forEach { marker -> assertTrue(marker, card.contains(marker)) }
        assertTrue(service.contains("lookupEnglishWordInTranslationCard"))
        assertTrue(service.contains("floatingWordLookupCoordinator.execute(word, settings)"))
        assertTrue(service.contains("showFloatingEnglishWordDetails(it)"))
    }

    @Test
    fun fullDetails_smoke_opensLoadingCardAndUsesFreshStructuredLookup() {
        val card = source("app/src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt")
        val service = source("app/src/main/java/com/gameocr/app/service/CaptureService.kt")
        val flow = service.substring(
            service.indexOf("private fun showFloatingEnglishWordDetails("),
            service.indexOf("private fun lookupEnglishWordInTranslationCard(", service.indexOf("private fun showFloatingEnglishWordDetails(")),
        )

        listOf(
            "floatingWordDetailsJob?.cancel()",
            "val requestId = ++floatingWordDetailsRequestId",
            "completed = false",
            "translation = loadingContent.translation",
            "wordResult = loadingContent.wordResult",
            "loading = loadingContent.loading",
            "floatingWordLookupCoordinator.executeFull(outcome.word, settings)",
            "completed = true",
            "updateWordDetailsForSource(outcome.word, finalContent)",
        ).forEach { marker -> assertTrue(marker, flow.contains(marker)) }
        assertFalse("compact content must not populate the full card", flow.contains("translation = outcome.translation"))
        assertFalse("compact dictionary must not populate the full card", flow.contains("wordResult = outcome.wordResult"))
        assertFalse("full details must not reuse compact API", flow.contains("translateWordCompact"))
        assertTrue(card.contains("fun updateWordDetailsForSource("))
        assertTrue(card.contains("updateTranslation(content.translation, final = false)"))
        assertTrue(card.contains("updateWordResult(content.wordResult)"))
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
        assertTrue(manager.contains("showFloatingWordHighlight(textView, hit)"))
        assertTrue(manager.contains("BackgroundColorSpan(translationActionMarkerColor(accent))"))
        assertTrue(manager.contains("onPreviewDismissed = ::clearFloatingWordHighlight"))
        assertTrue(service.contains("onFloatingWordLookupRequested = ::lookupFloatingEnglishWord"))
        assertTrue(service.contains("onFloatingWordDetailsRequested = ::showFloatingEnglishWordDetails"))
        assertEquals(
            "the tap entry is wired once and declared once; Blocks keep their existing path",
            2,
            manager.count("configureFloatingEnglishWordTap("),
        )
    }

    @Test
    fun lookupStatus_smoke_distinguishesMissFromErrorAtEveryPresentationEntry() {
        val manager = source("app/src/main/java/com/gameocr/app/overlay/OverlayManager.kt")
        val service = source("app/src/main/java/com/gameocr/app/service/CaptureService.kt")
        listOf("floating window" to manager, "translation card" to service).forEach { (name, code) ->
            assertTrue(name, code.contains("failed = outcome.error != null"))
            assertTrue(name, code.contains("R.string.floating_word_lookup_not_found"))
            assertFalse(name, code.contains("failed = !outcome.hasDetails"))
            assertFalse(name, code.contains("failed = !loading && !outcome.hasDetails"))
        }
        val fullFlow = service.substring(
            service.indexOf("private fun showFloatingEnglishWordDetails("),
            service.indexOf("private fun lookupEnglishWordInTranslationCard("),
        )
        assertTrue(fullFlow.contains("failed = fullOutcome.error != null"))
        assertTrue(fullFlow.contains("FloatingWordLookupOutcome(outcome.word, null, null, error)"))
        assertTrue(fullFlow.contains("throw cancellation"))
        assertFalse("errors must not be converted to a normal miss", fullFlow.contains("getOrNull()"))

        data class CopyCase(val directory: String, val notFound: String, val failed: String)
        listOf(
            CopyCase("values", "No definition found", "Lookup failed"),
            CopyCase("values-zh-rCN", "未找到释义", "查询失败"),
        ).forEach { case ->
            val strings = source("app/src/main/res/${case.directory}/strings.xml")
            assertTrue(case.directory, strings.contains("name=\"floating_word_lookup_not_found\">${case.notFound}</string>"))
            assertTrue(case.directory, strings.contains("name=\"floating_word_lookup_failed\">${case.failed}</string>"))
        }
    }

    private fun String.count(marker: String): Int = windowed(marker.length).count { it == marker }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
