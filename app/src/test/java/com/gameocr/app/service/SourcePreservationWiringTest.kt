package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePreservationWiringTest {
    private val source by lazy {
        listOf(
            File("src/main/java/com/gameocr/app/service/CaptureService.kt"),
            File("app/src/main/java/com/gameocr/app/service/CaptureService.kt"),
        ).first(File::isFile).readText().replace("\r\n", "\n")
    }

    @Test
    fun normalCapture_filtersBeforeMaskAndBothRenderModes_tableDriven() {
        val plan = source.indexOf("val sourcePreservationPlan = sourcePreservationService.plan(blocks, settings)")
        val claim = source.indexOf("mangaOcrEngine.claimDelayedMaskDebugBatch(", plan)
        val renderEnd = source.indexOf("} finally {", claim)
        val snippet = source.substring(plan, renderEnd)
        data class Case(val name: String, val marker: String)
        listOf(
            Case("mask uses retained blocks", "blocks = translationBlocks"),
            Case("block overlay uses retained blocks", "renderBlocks(\n                        translationBlocks"),
            Case("floating window uses retained blocks", "renderFloatingWindow(translationBlocks"),
            Case("all-preserved frame skips translation", "if (translationBlocks.isEmpty())"),
        ).forEach { case -> assertTrue(case.name, case.marker in snippet) }
        assertTrue("preservation runs before mask claim", plan in 0 until claim)
    }

    @Test
    fun stableLoop_filtersBeforeRendering() {
        val start = source.indexOf("private suspend fun deliverStableLoopBlocks(")
        val end = source.indexOf("private fun commitLoopFrame(", start)
        val snippet = source.substring(start, end)
        assertTrue("stable loop resolves preservation", "sourcePreservationService.plan(blocks, settings)" in snippet)
        assertTrue("stable loop keeps style indexes aligned", "retainedIndexes.mapNotNull(adaptiveStyles::getOrNull)" in snippet)
        assertTrue("stable loop renders retained blocks", "sourcePreservationPlan.retainedBlocks" in snippet)
    }
}
