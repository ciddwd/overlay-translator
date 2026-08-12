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
            val captureIndex = snippet.indexOf("shotter.capture()")
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

        assertTrue(
            "clear should remove loading, errors, and block overlays through the transient layer",
            "clearBlocksAndLoading()" in snippet,
        )
        assertTrue(
            "clear should still destroy the floating translation window",
            "clearFloatingWindow()" in snippet,
        )
    }

    @Test
    fun translationResultRendering_tableDriven_preservesTaskLoading() {
        data class Case(
            val name: String,
            val signature: String,
            val expectedResultClear: String,
        )

        val source = File("src/main/java/com/gameocr/app/overlay/OverlayManager.kt").readText()
        val cases = listOf(
            Case("block placeholders", "fun showBlocks(", "clearBlockResults()"),
            Case("floating batch results", "fun showFullScreen(", "clearBlockResults()"),
            Case("floating streaming placeholders", "fun prepareFloatingWindow(", "clearBlockResults()"),
        )

        cases.forEach { case ->
            val snippet = functionSnippet(source, case.signature)

            assertTrue(
                "${case.name} should clear stale translation results",
                case.expectedResultClear in snippet,
            )
            assertFalse(
                "${case.name} must not dismiss the task-owned loading indicator",
                "clearLoading()" in snippet || "clearBlocksAndLoading()" in snippet,
            )
            assertFalse(
                "${case.name} must not use the full clear path while translation is active",
                "\n        clear()\n" in snippet,
            )
        }
    }

    @Test
    fun translationProgress_tableDriven_hasOneTerminalOwnerForAsyncRendering() {
        data class Case(
            val name: String,
            val signature: String,
        )

        val source = captureServiceSource()
        val cases = listOf(
            Case("block batch and streaming translation", "private suspend fun renderBlocks("),
            Case("floating streaming translation", "private suspend fun renderFloatingWindow("),
        )

        cases.forEach { case ->
            val snippet = functionSnippet(source, case.signature)
            assertTrue(
                "${case.name} should delegate asynchronous work to the shared page executor",
                "launchPageTranslationExecution(" in snippet,
            )
        }

        val pageExecutorSnippet =
            functionSnippet(source, "private suspend fun launchPageTranslationExecution(")
        assertTrue(
            "the shared page executor should delegate progress ownership exactly once",
            "launchTranslationBatch(diagId)" in pageExecutorSnippet,
        )

        val ownerSnippet = functionSnippet(source, "private fun launchTranslationBatch(")
        assertTrue(
            "the asynchronous translation owner should dismiss loading in its terminal path",
            "finally" in ownerSnippet && "overlay?.dismissLoading()" in ownerSnippet,
        )
        assertTrue(
            "cancellation should still run terminal loading cleanup",
            "NonCancellable + Dispatchers.Main.immediate" in ownerSnippet,
        )

        val captureSnippet = functionSnippet(source, "private suspend fun captureOnce(")
        assertTrue(
            "capture cleanup should leave loading to an active translation owner",
            "if (!translationJobOwnsLoading(diagId))" in captureSnippet,
        )
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
                marker = "var full = shotter.capture()",
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
    fun everyCapture_usesFloatingWindowPolicyAndRestoresBeforeProcessing() {
        val snippet = functionSnippet(captureServiceSource(), "private suspend fun captureOnce(")
        val policySnippet = functionSnippet(
            captureServiceSource(),
            "private suspend fun prepareFloatingWindowForCapture(",
        )
        val blockingResultIndex = snippet.indexOf("overlay?.hasBlockingLoopResult()")
        val preparationIndex = snippet.indexOf("prepareFloatingWindowForCapture(")
        val captureIndex = snippet.indexOf("var full = shotter.capture()", preparationIndex)
        val restoreIndex = snippet.indexOf(
            "restoreFloatingWindowAfterCapture(floatingWindowPreparation)",
            captureIndex,
        )
        val maskIndex = snippet.indexOf("maskFloatingWindowFromCapture(full, captureMask)", captureIndex)

        assertTrue("loop should only wait for blocking overlay results", blockingResultIndex >= 0)
        assertTrue("every capture should prepare the visible floating window", preparationIndex >= 0)
        assertTrue("screenshot should happen after the floating window decision", captureIndex > preparationIndex)
        assertTrue("temporarily hidden window should be restored immediately", restoreIndex > captureIndex)
        assertTrue("captured overlay pixels should be masked before OCR", maskIndex > captureIndex)
        assertTrue(
            "capture policy must support both hide and preserve paths",
            "floatingWindowCaptureAction(" in policySnippet &&
                "FloatingWindowCaptureAction.HIDE_TEMPORARILY" in policySnippet &&
                "FloatingWindowCaptureAction.PRESERVE_AND_MASK" in policySnippet,
        )
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
                "syncFloatingWindowFromSettings(",
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
                "overlay?.updateFloatingWindowText(update.blockIndex, update.text, phase)"
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
            "canonical unit updates should preserve their OCR block and layout phase",
            "overlay?.updateBlockText(update.blockIndex, update.text, phase)" in blockUpdateSnippet,
        )
    }

    @Test
    fun renderers_useCanonicalOcrUnitsForBatchAndStreaming() {
        val source = captureServiceSource()
        val renderSnippet = functionSnippet(source, "private suspend fun renderFloatingWindow(")
        val planSnippet = functionSnippet(source, "private fun preparePageTranslationPlan(")
        val executorSnippet =
            functionSnippet(source, "private suspend fun launchPageTranslationExecution(")
        val individualSnippet = functionSnippet(source, "private suspend fun translatePageIndividually(")

        assertTrue(
            "both renderers should plan final translation units from OCR blocks in one place",
            "val units = planPageTranslationUnits(blocks)" in planSnippet,
        )
        assertFalse(
            "translation planning must not run a second geometry merger",
            "planCrossLine" in planSnippet || "mergeDisablesCrossLine" in planSnippet,
        )
        assertTrue(
            "floating window placeholders should preserve the exact OCR-block row count used by Blocks",
            "overlay?.prepareFloatingWindow(blocks.map(TextBlock::text))" in renderSnippet,
        )
        assertTrue(
            "floating rendering should use the shared page executor",
            "launchPageTranslationExecution(" in renderSnippet,
        )
        assertTrue(
            "the shared page executor should own individual translation",
            "translatePageIndividually(" in executorSnippet,
        )
        assertTrue(
            "individual translation should submit each complete context-unit source",
            "unit.sourceText" in individualSnippet,
        )
        assertTrue(
            "batch translation should submit the same complete context-unit sources",
            "val sources = translationUnits.map { it.sourceText }" in source,
        )
    }

    private fun captureServiceSource(): String =
        File("src/main/java/com/gameocr/app/service/CaptureService.kt").readText()

    private fun functionSnippet(source: String, signature: String): String {
        val start = source.indexOf(signature)
        require(start >= 0) { "Missing signature: $signature" }
        val parameterStart = source.indexOf('(', start)
        require(parameterStart >= 0) { "Missing parameter list: $signature" }
        var parameterDepth = 0
        var parameterEnd = -1
        for (index in parameterStart until source.length) {
            when (source[index]) {
                '(' -> parameterDepth += 1
                ')' -> {
                    parameterDepth -= 1
                    if (parameterDepth == 0) {
                        parameterEnd = index
                        break
                    }
                }
            }
        }
        require(parameterEnd >= 0) { "Unclosed parameter list: $signature" }
        val firstBrace = source.indexOf('{', parameterEnd + 1)
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
