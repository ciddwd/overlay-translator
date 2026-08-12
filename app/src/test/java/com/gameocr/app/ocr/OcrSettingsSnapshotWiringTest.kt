package com.gameocr.app.ocr

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSettingsSnapshotWiringTest {

    @Test
    fun captureSettingsSnapshot_isForwardedThroughEveryLocalOcrPath_tableDriven() {
        val contract = source("app/src/main/java/com/gameocr/app/ocr/OcrEngine.kt")
        val routing = source("app/src/main/java/com/gameocr/app/ocr/RoutingOcrEngine.kt")
        val manga = source("app/src/main/java/com/gameocr/app/ocr/MangaOcrEngine.kt")
        val paddle = source("app/src/main/java/com/gameocr/app/ocr/PaddleOcrEngine.kt")
        val capture = source("app/src/main/java/com/gameocr/app/service/CaptureService.kt")

        data class Case(val name: String, val content: String, val marker: String)
        listOf(
            Case("OCR contract accepts a snapshot", contract, "settings: Settings,"),
            Case("routing overrides snapshot entry", routing, "settings: Settings,\n    ): List<TextBlock> = recognizeWithSettings"),
            Case("routing passes snapshot to Paddle", routing, "paddle.recognize(bitmap, kind, settings)"),
            Case("routing passes snapshot to Manga", routing, "manga.recognize(bitmap, kind, settings)"),
            Case("Manga compatibility path reads once", manga, "return recognize(bitmap, kind, settingsRepository.get())"),
            Case("Manga snapshot drives inference", manga, "runFull(bitmap, settings)"),
            Case("Paddle compatibility path reads once", paddle, "return recognize(bitmap, kind, settingsRepository.get())"),
            Case("Paddle snapshot selects model", paddle, "ensureReady(settings.paddleModelVersion)"),
            Case("word select forwards snapshot", capture, "ocrEngine.recognize(cropped, settings.ocrEngine, settings)"),
            Case("loop ROI forwards snapshot", capture, "ocrEngine.recognize(preprocessed, pending.effectiveEngine, settings)"),
            Case("first pass forwards snapshot", capture, "ocrEngine.recognize(preprocessed, effectiveEngine, settings)"),
            Case("orientation rerun forwards snapshot", capture, "ocrEngine.recognize(preprocessed, rerunEngine, settings)"),
            Case("rotated rerun forwards snapshot", capture, "ocrEngine.recognize(rotated, engine, settings)"),
        ).forEach { case ->
            assertTrue(case.name, case.content.contains(case.marker))
        }
    }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText()?.normalizeLineEndings()
        ?: error("Source not found: $path")

    private fun String.normalizeLineEndings(): String = replace("\r\n", "\n")
}
