package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureChromeOrderingTest {

    @Test
    fun capturePipelines_tableDriven_restoreLoadingOnlyAfterScreenshotFrame() {
        data class Case(
            val name: String,
            val signature: String,
            val expectedRestore: String,
            val allowsLoading: Boolean,
        )

        val source = captureServiceSource()
        val cases = listOf(
            Case(
                "full screen capture",
                "private suspend fun captureOnce(",
                "restoreCaptureChromeOnce(showLoading = showLoadingAfterScreenshot)",
                allowsLoading = true,
            ),
            Case(
                "word select capture",
                "private suspend fun runWordSelectPipeline(",
                "restoreCaptureChromeOnce(showLoading = false)",
                allowsLoading = false,
            ),
        )

        cases.forEach { case ->
            val snippet = functionSnippet(source, case.signature)
            val captureIndex = snippet.indexOf("val full = shotter.capture()")
            val restoreIndex = snippet.indexOf(case.expectedRestore, captureIndex)

            assertTrue("${case.name} should capture before restoring loading", captureIndex >= 0)
            assertTrue("${case.name} should restore capture chrome after capture", restoreIndex > captureIndex)
            assertFalse(
                "${case.name} must not show loading before MediaProjection capture",
                "showLoadingHint()" in snippet.substring(0, captureIndex)
            )
            if (!case.allowsLoading) {
                assertFalse(
                    "${case.name} must not expose the global rotating loading indicator",
                    "showLoadingAfterScreenshot" in snippet,
                )
            }
        }
    }

    @Test
    fun captureTriggers_tableDriven_prepareCleanFrameBeforePipeline() {
        data class Case(
            val name: String,
            val signature: String,
            val pipelineCall: String,
            val prepareCall: String
        )

        val source = captureServiceSource()
        val cases = listOf(
            Case(
                "full screen trigger",
                "private fun triggerOnce()",
                "captureOnce(",
                "prepareCleanCaptureFrame(hideFloatingButton = true)"
            ),
            Case(
                "word select trigger",
                "private fun triggerWordSelect()",
                "runWordSelectPipeline(",
                "prepareCleanCaptureFrame(hideFloatingButton = true)"
            )
        )

        cases.forEach { case ->
            val snippet = functionSnippet(source, case.signature)
            val prepareIndex = snippet.indexOf(case.prepareCall)
            val pipelineIndex = snippet.indexOf(case.pipelineCall, prepareIndex)

            assertTrue("${case.name} should prepare a clean frame", prepareIndex >= 0)
            assertTrue("${case.name} should prepare before starting capture pipeline", pipelineIndex > prepareIndex)
        }
    }

    @Test
    fun fullScreenTrigger_showsImmediateLoading_thenHidesAndRestoresItAroundScreenshot() {
        val snippet = functionSnippet(captureServiceSource(), "private fun triggerOnce()")

        val loadingIndex = snippet.indexOf("overlay?.showLoadingHint()")
        val prepareIndex = snippet.indexOf("prepareCleanCaptureFrame(hideFloatingButton = true)")
        val captureIndex = snippet.indexOf("captureOnce(", prepareIndex)

        assertTrue("full screen trigger should show loading immediately", loadingIndex >= 0)
        assertTrue("loading should be shown before hiding capture chrome", loadingIndex < prepareIndex)
        assertTrue("trigger should clear every overlay before capture", prepareIndex >= 0)
        assertTrue("trigger should start capture after clean-frame preparation", captureIndex > prepareIndex)
        assertTrue(
            "captureOnce should restore loading only after acquiring the screenshot",
            "showLoadingAfterScreenshot = true" in snippet
        )
        assertFalse("clean capture must not preserve loading", "keepLoading" in snippet)
    }

    @Test
    fun overlayClear_alwaysRemovesLoadingAndOtherCaptureChrome() {
        val snippet = functionSnippet(
            File("src/main/java/com/gameocr/app/overlay/OverlayManager.kt").readText(),
            "fun clear()"
        )

        assertTrue("clear should dismiss loading", "clearLoading()" in snippet)
        assertTrue("clear should still dismiss stale errors", "dismissError()" in snippet)
        assertTrue("clear should still hide floating translation window", "floatingWindow.hide()" in snippet)
        assertTrue("clear should still remove block overlays", "blocksView?.let" in snippet)
    }

    @Test
    fun fullScreenCapture_tableDriven_restoresChromeForSuccessFailureAndCancellation() {
        data class Case(
            val name: String,
            val marker: String,
            val expectedRestore: String,
        )

        val snippet = functionSnippet(captureServiceSource(), "private suspend fun captureOnce(")
        val cases = listOf(
            Case(
                name = "screenshot success",
                marker = "val full = shotter.capture()",
                expectedRestore = "restoreCaptureChromeOnce(showLoading = showLoadingAfterScreenshot)",
            ),
            Case(
                name = "missing screenshotter",
                marker = "val shotter = screenshotter ?: run",
                expectedRestore = "restoreCaptureChromeOnce(showLoading = false)",
            ),
            Case(
                name = "null screenshot",
                marker = "if (full == null)",
                expectedRestore = "restoreCaptureChromeOnce(showLoading = false)",
            ),
            Case(
                name = "exception or cancellation fallback",
                marker = "finally {",
                expectedRestore = "restoreCaptureChromeOnce(showLoading = false)",
            ),
        )

        cases.forEach { case ->
            val markerIndex = snippet.indexOf(case.marker)
            val restoreIndex = snippet.indexOf(case.expectedRestore, markerIndex)
            assertTrue("${case.name} marker should exist", markerIndex >= 0)
            assertTrue("${case.name} should restore capture chrome", restoreIndex > markerIndex)
        }
    }

    @Test
    fun applyOverlayConfig_tableDriven_keepsViewMutationsOnMainThread() {
        data class Case(
            val name: String,
            val uiMutation: String
        )

        val snippet = functionSnippet(captureServiceSource(), "private suspend fun applyOverlayConfig(")
        val mainIndex = snippet.indexOf("withContext(Dispatchers.Main)")
        val mainSnippet = snippet.substring(mainIndex.coerceAtLeast(0))
        val cases = listOf(
            Case("overlay properties", "overlay?.apply"),
            Case(
                "floating window resync uses the resolved fixed/adaptive style",
                "syncFloatingWindowFromSettings(effectiveOverlaySettings)",
            ),
            Case("floating button resize", "it.applyResize()"),
            Case("floating button snap animation", "it.applySnapPreference(settings.floatingButtonSnapToEdge)"),
            Case("floating button skill icon", "it.applySkillIcon()")
        )

        assertTrue("applyOverlayConfig should switch to the Android main thread", mainIndex >= 0)
        assertFalse(
            "applyOverlayConfig should not fire-and-forget UI updates from background threads",
            "mainScope.launch" in snippet
        )
        cases.forEach { case ->
            assertTrue(
                "${case.name} should run inside Dispatchers.Main",
                case.uiMutation in mainSnippet
            )
        }
    }

    @Test
    fun translatePartialUpdates_tableDriven_useSynchronousMainThreadSwitch() {
        data class Case(
            val name: String,
            val signature: String,
            val updateCall: String
        )

        val source = captureServiceSource()
        val translateSnippet = functionSnippet(source, "private suspend fun translateOne(")
        val cases = listOf(
            Case(
                "block overlay partial update",
                "private suspend fun renderBlocks(",
                "updateTranslationUnit("
            ),
            Case(
                "floating window partial update",
                "private suspend fun renderFloatingWindow(",
                "overlay?.updateFloatingWindowText(idx, partial, phase)"
            )
        )

        assertTrue(
            "translateOne should pass text and layout phase through synchronous callbacks",
            "onUpdate: suspend (String, AdaptiveTextLayoutPhase) -> Unit" in translateSnippet
        )
        assertTrue(
            "stream chunks should be marked STREAMING",
            "onUpdate(it, AdaptiveTextLayoutPhase.STREAMING)" in translateSnippet
        )
        assertTrue(
            "completed text should be marked FINAL",
            "onUpdate(display.text, AdaptiveTextLayoutPhase.FINAL)" in translateSnippet
        )
        cases.forEach { case ->
            val snippet = functionSnippet(source, case.signature)
            val updateIndex = snippet.indexOf(case.updateCall)
            val mainIndex = snippet.lastIndexOf("withContext(Dispatchers.Main)", updateIndex)

            assertTrue("${case.name} should update overlay text", updateIndex >= 0)
            assertTrue("${case.name} should switch to the Android main thread", mainIndex >= 0)
            assertTrue("${case.name} should switch before touching overlay views", mainIndex < updateIndex)
            assertFalse(
                "${case.name} should not fire-and-forget partial UI updates",
                "mainScope.launch { ${case.updateCall} }" in snippet
            )
        }
        val blockUpdateSnippet = functionSnippet(source, "private fun updateTranslationUnit(")
        assertTrue(
            "cross-line reflow should still update each original overlay block with its layout phase",
            "overlay?.updateBlockText(blockIndex, chunk, phase)" in blockUpdateSnippet,
        )
    }

    @Test
    fun floatingWindowTranslation_usesCrossLineUnitsForBatchAndStreaming() {
        val source = captureServiceSource()
        val renderSnippet = functionSnippet(source, "private suspend fun renderFloatingWindow(")
        val batchSnippet = functionSnippet(source, "private suspend fun batchTranslateFloatingWindow(")

        assertTrue(
            "floating window should plan context units from OCR blocks",
            "planCrossLineTranslationUnits(blocks, settings.sourceLang)" in renderSnippet,
        )
        assertTrue(
            "floating window placeholders should use complete context-unit sources",
            "overlay?.prepareFloatingWindow(translationUnits.map { it.sourceText })" in renderSnippet,
        )
        assertTrue(
            "streaming translation should translate each complete context unit",
            "translateOne(unit.sourceText, settings, diagId, idx)" in renderSnippet,
        )
        assertTrue(
            "batch translation should submit the same complete context-unit sources",
            "val sources = translationUnits.map { it.sourceText }" in batchSnippet,
        )
    }

    private fun captureServiceSource(): String =
        File("src/main/java/com/gameocr/app/service/CaptureService.kt").readText()

    private fun functionSnippet(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing signature: $signature" }
        val firstBrace = source.indexOf('{', start)
        require(firstBrace >= 0) { "Missing function body: $signature" }

        var depth = 0
        for (index in firstBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(start, index + 1)
                    }
                }
            }
        }
        error("Unclosed function body: $signature")
    }
}
