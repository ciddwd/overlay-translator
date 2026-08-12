package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTranslationPipelineWiringTest {

    @Test
    fun renderers_shareOnePlanningAndExecutionPipeline_tableDriven() {
        val source = source("app/src/main/java/com/gameocr/app/service/CaptureService.kt")

        data class Case(
            val name: String,
            val marker: String,
            val expectedOccurrences: Int,
        )

        listOf(
            Case("one page planner declaration", "private fun preparePageTranslationPlan(", 1),
            Case("both renderers consume the page planner", "preparePageTranslationPlan(", 3),
            Case("one shared page executor declaration", "private suspend fun launchPageTranslationExecution(", 1),
            Case("both renderers consume the shared page executor", "launchPageTranslationExecution(", 3),
            Case("one batch executor declaration", "private suspend fun batchTranslatePage(", 1),
            Case("shared page executor alone consumes batch executor", "batchTranslatePage(", 2),
            Case("one translator batch invocation", "translator.translateBatchIncremental(", 1),
            Case("one individual executor declaration", "private suspend fun translatePageIndividually(", 1),
            Case("shared page executor alone consumes individual executor", "translatePageIndividually(", 2),
        ).forEach { case ->
            assertEquals(case.name, case.expectedOccurrences, source.count(case.marker))
        }

        listOf(
            "batchTranslateBlocks(",
            "batchTranslateFloatingWindow(",
            "publishBatchTranslation(",
            "publishFloatingBatchTranslation(",
        ).forEach { legacyPath ->
            assertFalse("legacy renderer-specific path removed: $legacyPath", source.contains(legacyPath))
        }

        assertTrue(
            "batch floating rendering runs under the same translation lifecycle",
            source.contains("launchTranslationBatch(diagId)"),
        )
        assertTrue(
            "floating rows use the same OCR block list as block rendering",
            source.contains("overlay?.prepareFloatingWindow(blocks.map(TextBlock::text))"),
        )

        val sakura = source("app/src/main/java/com/gameocr/app/translate/SakuraGalTranslator.kt")
        val routing = source("app/src/main/java/com/gameocr/app/translate/RoutingTranslator.kt")
        listOf(
            "promptScope == BatchPromptScope.ISOLATED_ITEMS",
            "return super.translateBatchIncremental(sources, budgetedSettings, onUpdate)",
            "SakuraOutputPolicy.validateLineDetailed(",
        ).forEach { marker ->
            assertTrue("Sakura fast path is isolated and validated: $marker", sakura.contains(marker))
        }
        assertTrue(
            "translation memory preserves a full source list only for shared prompts",
            routing.contains("promptScope == BatchPromptScope.SHARED_PAGE"),
        )
    }

    private fun String.count(value: String): Int = windowed(value.length).count { it == value }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
