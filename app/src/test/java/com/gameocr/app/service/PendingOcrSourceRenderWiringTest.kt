package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingOcrSourceRenderWiringTest {

    @Test
    fun `recognized source waiting flow is prepared once before translation table driven`() {
        val capture = source("app/src/main/java/com/gameocr/app/service/CaptureService.kt")
        val manga = source("app/src/main/java/com/gameocr/app/ocr/MangaDelayedMaskDebugSession.kt")

        data class Case(val name: String, val content: String, val marker: String)
        listOf(
            Case(
                "prepare every OCR block before translation",
                capture,
                "blockIndices = (0 until batch.blockCount).toSet()",
            ),
            Case(
                "recognized source replaces ellipsis after successful preparation",
                capture,
                "block to if (showRecognizedSource) block.text else \"…\"",
            ),
            Case(
                "recognized source keeps pending layout semantics",
                capture,
                "recognizedSourcePending = showRecognizedSource",
            ),
            Case(
                "prepared backgrounds publish before translation planning",
                capture,
                "patches = preparedDelayedMask.backgroundPatches",
            ),
            Case(
                "final layout consumes prepared repair",
                capture,
                "finishPreparedDelayedMaskDebugBatch",
            ),
            Case(
                "manager exposes separate prepare stage",
                manga,
                "suspend fun prepare(",
            ),
            Case(
                "final stage reuses prepared bubble repair",
                manga,
                "localRepairResult = prepared.localRepairResult",
            ),
            Case(
                "translation cancellation retains prepared source fallback",
                capture,
                "prepared background retained with OCR source after translation cancellation",
            ),
        ).forEach { case ->
            assertTrue(case.name, case.content.contains(case.marker))
        }

        val prepareIndex = capture.indexOf("prepareDelayedMaskBeforeTranslation(")
        val showIndex = capture.indexOf("overlay?.showBlocks(", startIndex = prepareIndex)
        val translateIndex = capture.indexOf(
            "val pagePlan = preparePageTranslationPlan",
            startIndex = showIndex,
        )
        assertTrue("preparation should precede waiting source display", prepareIndex in 0 until showIndex)
        assertTrue("waiting source display should precede translation", showIndex in 0 until translateIndex)
    }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
