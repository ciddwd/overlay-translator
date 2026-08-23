package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationVisualPipelineWiringTest {
    @Test
    fun visualContext_tableDrivenReachesEveryImageBackedTranslationPipeline() {
        data class Case(
            val name: String,
            val path: String,
            val requiredMarkers: List<String>,
        )

        listOf(
            Case(
                name = "realtime capture",
                path = "app/src/main/java/com/gameocr/app/service/CaptureService.kt",
                requiredMarkers = listOf(
                    "origin = \"realtime-${'$'}diagId\"",
                    "settings = contextualSettings",
                ),
            ),
            Case(
                name = "word select",
                path = "app/src/main/java/com/gameocr/app/service/CaptureService.kt",
                requiredMarkers = listOf(
                    "origin = \"word-select-${'$'}diagId\"",
                    "settings = translationRequestSettings",
                ),
            ),
            Case(
                name = "gallery batch",
                path = "app/src/main/java/com/gameocr/app/gallery/GalleryTranslationWorker.kt",
                requiredMarkers = listOf(
                    "origin = \"gallery-${'$'}{item.id}\"",
                    "visualPreparation.settings",
                ),
            ),
            Case(
                name = "OpenAI dictionary",
                path = "app/src/main/java/com/gameocr/app/translate/OpenAiTranslator.kt",
                requiredMarkers = listOf(
                    "buildOpenAiUserContent(",
                    "visualContext = settings.runtimeTranslationVisualContext",
                ),
            ),
            Case(
                name = "OpenAI page visual order contract",
                path = "app/src/main/java/com/gameocr/app/translate/OpenAiTranslator.kt",
                requiredMarkers = listOf(
                    "VisualTranslationPromptPolicy.buildSystemSuffix(",
                    "VisualTranslationResponsePolicy.validateAndNormalize(",
                    "result.orderedIds",
                ),
            ),
            Case(
                name = "Anthropic dictionary",
                path = "app/src/main/java/com/gameocr/app/translate/AnthropicTranslator.kt",
                requiredMarkers = listOf(
                    "override suspend fun translateWord",
                    "visualContext = settings.runtimeTranslationVisualContext",
                ),
            ),
            Case(
                name = "Anthropic page visual order contract",
                path = "app/src/main/java/com/gameocr/app/translate/AnthropicTranslator.kt",
                requiredMarkers = listOf(
                    "VisualTranslationPromptPolicy.buildSystemSuffix(",
                    "VisualTranslationResponsePolicy.validateAndNormalize(",
                    "result.orderedIds",
                ),
            ),
        ).forEach { case ->
            val source = source(case.path)
            case.requiredMarkers.forEach { marker ->
                assertTrue("${case.name}: $marker", source.contains(marker))
            }
        }
    }

    @Test
    fun mergeAll_ordersByRecognizedGeometryWithoutChangingOrdinaryPresentations() {
        val source = source("app/src/main/java/com/gameocr/app/service/CaptureService.kt")
        val mergeAllGate = source.indexOf("val mergeAllFloating = PageTranslationGroupingPolicy.shouldMergeAll(")
        val pageSorter = source.indexOf("sortTextBlocksForMergedPage(", startIndex = mergeAllGate)
        val visualPreparation = source.indexOf("prepareVisualTranslationSettings(", startIndex = pageSorter)

        assertTrue("merge-all gate exists", mergeAllGate >= 0)
        assertTrue("page sorter is inside the gated branch", pageSorter > mergeAllGate)
        assertTrue("visual request receives the canonical order", visualPreparation > pageSorter)
        assertTrue(
            "source order is independent from translation render layout",
            source.contains("sortTextBlocksForReading(rawBlocks, recognizedReadingOrientation)"),
        )
    }

    @Test
    fun wordSelect_preparesExactCropBeforeItIsRecycled() {
        val source = source("app/src/main/java/com/gameocr/app/service/CaptureService.kt")
        val start = source.indexOf("origin = \"word-select-${'$'}diagId\"")
        val recycled = source.indexOf("cropped.recycle()", startIndex = start)
        val translated = source.indexOf("settings = translationRequestSettings", startIndex = recycled)

        assertTrue(start >= 0)
        assertTrue(recycled > start)
        assertTrue(translated > recycled)
    }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
