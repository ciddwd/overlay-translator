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
                "prepareCleanCaptureFrame(hideFloatingButton = true, keepLoading = loadingShown)"
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
    fun fullScreenTrigger_showsImmediateLoadingAndKeepsItWhileHidingButton() {
        val snippet = functionSnippet(captureServiceSource(), "private fun triggerOnce()")

        val loadingIndex = snippet.indexOf("overlay?.showLoadingHint() == true")
        val prepareIndex = snippet.indexOf("prepareCleanCaptureFrame(hideFloatingButton = true, keepLoading = loadingShown)")
        val captureIndex = snippet.indexOf("captureOnce(", prepareIndex)

        assertTrue("full screen trigger should show loading immediately", loadingIndex >= 0)
        assertTrue("loading should be shown before hiding capture chrome", loadingIndex < prepareIndex)
        assertTrue("trigger should preserve already-shown loading while clearing overlays", prepareIndex >= 0)
        assertTrue("trigger should start capture after clean-frame preparation", captureIndex > prepareIndex)
        assertTrue(
            "captureOnce should not show a duplicate loading if immediate loading was visible",
            "showLoadingAfterScreenshot = !loadingShown" in snippet
        )
    }

    @Test
    fun overlayClear_supportsKeepingImmediateLoadingDuringCleanCapture() {
        val snippet = functionSnippet(
            File("src/main/java/com/gameocr/app/overlay/OverlayManager.kt").readText(),
            "fun clear(keepLoading: Boolean = false)"
        )

        assertTrue(
            "clear should only dismiss loading when keepLoading is false",
            "if (!keepLoading) clearLoading()" in snippet
        )
        assertTrue("clear should still dismiss stale errors", "dismissError()" in snippet)
        assertTrue("clear should still hide floating translation window", "floatingWindow.hide()" in snippet)
        assertTrue("clear should still remove block overlays", "blocksView?.let" in snippet)
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
            Case("floating window resync", "syncFloatingWindowFromSettings(settings)"),
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
                "overlay?.updateBlockText(idx, partial)"
            ),
            Case(
                "floating window partial update",
                "private suspend fun renderFloatingWindow(",
                "overlay?.updateFloatingWindowText(idx, partial)"
            )
        )

        assertTrue(
            "translateOne should let partial callbacks switch dispatchers synchronously",
            "onPartial: suspend (String) -> Unit" in translateSnippet
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
