package com.gameocr.app.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWindowLoopFlickerTest {

    @Test
    fun floatingRenderPaths_tableDriven_preserveWindowAndReplaceContentInPlace() {
        data class Case(
            val name: String,
            val signature: String,
            val emptyCondition: String,
        )

        val source = overlayManagerSource()
        val cases = listOf(
            Case(
                name = "batch floating render",
                signature = "fun showFullScreen(",
                emptyCondition = "if (pairs.isEmpty())",
            ),
            Case(
                name = "streaming floating render",
                signature = "fun prepareFloatingWindow(",
                emptyCondition = "if (sources.isEmpty())",
            ),
        )

        cases.forEach { case ->
            val snippet = functionSnippet(source, case.signature)
            assertTrue(
                "${case.name} should clear only transient/block overlays",
                "clearBlocksAndLoading()" in snippet,
            )
            assertFalse(
                "${case.name} must not destroy the floating window before updating it",
                Regex("""(?m)^\s*clear\(\)\s*$""").containsMatchIn(snippet),
            )
            assertTrue(
                "${case.name} should destroy stale floating state for an empty result",
                Regex(
                    """${Regex.escape(case.emptyCondition)}\s*\{\s*clearFloatingWindow\(\)""",
                ).containsMatchIn(snippet),
            )
            assertTrue(
                "${case.name} should replace content on the existing window",
                "floatingWindow.setContent(content)" in snippet,
            )
            assertTrue(
                "${case.name} should still create the window for its first result",
                "floatingWindow.show(content, onDismiss = ::handleFloatingWindowUserDismiss)" in snippet,
            )
        }
    }

    @Test
    fun clearLayers_tableDriven_keepProgrammaticDestructionSemantics() {
        data class Case(
            val name: String,
            val signature: String,
            val requiredMarkers: List<String>,
            val forbiddenMarkers: List<String>,
        )

        val source = overlayManagerSource()
        val cases = listOf(
            Case(
                name = "block cleanup preserves floating window",
                signature = "private fun clearBlocksAndLoading()",
                requiredMarkers = listOf(
                    "clearLoading()",
                    "dismissError()",
                    "blockViews.clear()",
                ),
                forbiddenMarkers = listOf(
                    "floatingWindow.",
                    "lastFloatingPairs",
                ),
            ),
            Case(
                name = "floating cleanup destroys floating state",
                signature = "private fun clearFloatingWindow()",
                requiredMarkers = listOf(
                    "floatingWindow.hide()",
                    "floatingContentView = null",
                    "floatingStreamingUpdateCounts.clear()",
                    "lastFloatingPairs = null",
                ),
                forbiddenMarkers = listOf(
                    "blocksView",
                    "blockViews",
                ),
            ),
            Case(
                name = "public clear destroys both layers",
                signature = "fun clear()",
                requiredMarkers = listOf(
                    "clearBlocksAndLoading()",
                    "clearFloatingWindow()",
                ),
                forbiddenMarkers = emptyList(),
            ),
        )

        cases.forEach { case ->
            val snippet = functionSnippet(source, case.signature)
            case.requiredMarkers.forEach { marker ->
                assertTrue("${case.name} should contain $marker", marker in snippet)
            }
            case.forbiddenMarkers.forEach { marker ->
                assertFalse("${case.name} should not contain $marker", marker in snippet)
            }
        }
    }

    @Test
    fun captureVisibility_tableDriven_hidesWithoutDestroyingWindow() {
        data class Case(
            val name: String,
            val marker: String,
        )

        val source = sourceFile(
            "app/src/main/java/com/gameocr/app/overlay/DraggableOverlayWindow.kt",
            "src/main/java/com/gameocr/app/overlay/DraggableOverlayWindow.kt",
        ).readText()
        val snippet = functionSnippet(source, "fun setHiddenForCapture(")
        val cases = listOf(
            Case("capture hides drawing", "View.INVISIBLE"),
            Case("capture restores drawing", "View.VISIBLE"),
        )

        cases.forEach { case ->
            assertTrue(case.name, case.marker in snippet)
        }
        assertFalse("capture visibility must not destroy the window", "hide()" in snippet)
    }

    private fun overlayManagerSource(): String = sourceFile(
        "app/src/main/java/com/gameocr/app/overlay/OverlayManager.kt",
        "src/main/java/com/gameocr/app/overlay/OverlayManager.kt",
    ).readText()

    private fun sourceFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull(File::isFile)
            ?: error("Source file not found: ${candidates.joinToString()}")

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
