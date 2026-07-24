package com.gameocr.app.overlay

import com.gameocr.app.data.FloatingWindowContentMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWindowTextSelectionTest {

    @Test
    fun keyFocusPolicy_tableDriven_onlyFocusesAnActiveUnlockedSelection() {
        data class Case(
            val name: String,
            val locked: Boolean,
            val selectionActive: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("normal unlocked window passes Back through", false, false, false),
            Case("normal locked window passes Back through", true, false, false),
            Case("active unlocked selection temporarily owns Back", false, true, true),
            Case("locking an active selection releases Back", true, true, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                floatingWindowNeedsKeyFocus(
                    locked = case.locked,
                    selectionActive = case.selectionActive,
                ),
            )
        }
    }

    @Test
    fun selectionPolicy_tableDriven_coversLockAndTextStates() {
        data class Case(
            val name: String,
            val locked: Boolean,
            val text: String?,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("unlocked source", false, "Source text", true),
            Case("unlocked translation", false, "Translated text", true),
            Case("locked source", true, "Source text", false),
            Case("locked translation", true, "Translated text", false),
            Case("missing text", false, null, false),
            Case("empty text", false, "", false),
            Case("whitespace", false, " \n ", false),
            Case("unicode loading placeholder", false, "\u2026", false),
            Case("ascii loading placeholder", false, "...", false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                canSelectFloatingWindowText(case.locked, case.text),
            )
        }
    }

    @Test
    fun continuousContent_tableDriven_preservesDisplayOrderInOneTextBuffer() {
        data class Case(
            val name: String,
            val mode: FloatingWindowContentMode,
            val pairs: List<Pair<String, String>>,
            val expected: String,
        )

        val pairs = listOf("Source A" to "Translation A", "Source B" to "Translation B")
        val cases = listOf(
            Case(
                "source and translation",
                FloatingWindowContentMode.SRC_AND_DST,
                pairs,
                "・Source A\nTranslation A\n\n・Source B\nTranslation B",
            ),
            Case(
                "translation only",
                FloatingWindowContentMode.DST_ONLY,
                pairs,
                "Translation A\n\nTranslation B",
            ),
            Case("empty content", FloatingWindowContentMode.SRC_AND_DST, emptyList(), ""),
        )

        cases.forEach { case ->
            val actual = floatingWindowTextSegments(case.pairs, case.mode)
                .joinToString(separator = "") { it.text }
            assertEquals(case.name, case.expected, actual)
        }
    }

    @Test
    fun continuousContentSelection_tableDriven_ignoresLoadingOnlyContent() {
        data class Case(
            val name: String,
            val mode: FloatingWindowContentMode,
            val pairs: List<Pair<String, String>>,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(
                "visible source remains selectable while translation loads",
                FloatingWindowContentMode.SRC_AND_DST,
                listOf("Source" to "\u2026"),
                true,
            ),
            Case(
                "translation-only unicode placeholders are not selectable",
                FloatingWindowContentMode.DST_ONLY,
                listOf("Source A" to "\u2026", "Source B" to "\u2026"),
                false,
            ),
            Case(
                "translation-only ascii placeholders are not selectable",
                FloatingWindowContentMode.DST_ONLY,
                listOf("Source" to "..."),
                false,
            ),
            Case(
                "one completed translation enables the continuous host",
                FloatingWindowContentMode.DST_ONLY,
                listOf("Source A" to "\u2026", "Source B" to "Ready"),
                true,
            ),
            Case("empty pairs", FloatingWindowContentMode.SRC_AND_DST, emptyList(), false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                hasSelectableFloatingWindowContent(case.pairs, case.mode),
            )
        }
    }

    @Test
    fun floatingWindow_smoke_usesOneDialogHostAndNativeSelection() {
        val windowSource = sourceFile("DraggableOverlayWindow.kt").readText()
        val managerSource = sourceFile("OverlayManager.kt").readText()

        data class Case(val name: String, val source: String, val marker: String)

        val required = listOf(
            Case(
                "current floating shell is hosted by a DecorView window",
                windowSource,
                "Dialog(context, R.style.Theme_GameOcr_Transparent)",
            ),
            Case("lock state refreshes native selection", windowSource, "refreshSelectableTextViews()"),
            Case(
                "selection lifecycle uses the platform action mode",
                windowSource,
                "setCustomSelectionActionModeCallback",
            ),
            Case(
                "normal unlocked state keeps the current window non-focusable",
                windowSource,
                "selectionActive = activeSelectionActionMode != null",
            ),
            Case(
                "active selection temporarily makes the window focusable",
                windowSource,
                "selectionActive = true",
            ),
            Case(
                "ending selection restores the non-focusable overlay",
                windowSource,
                "setSelectionWindowFocusable(false)",
            ),
            Case("locking finishes an active selection", windowSource, "activeSelectionActionMode?.finish()"),
            Case(
                "selection checks the live lock and content state",
                windowSource,
                "!locked && isContentSelectable()",
            ),
            Case(
                "system toolbar includes selected-text speech",
                windowSource,
                "R.id.action_speak_selected_text",
            ),
            Case(
                "floating content uses a mutable styled text buffer",
                managerSource,
                "SpannableStringBuilder",
            ),
            Case(
                "all rows use one selectable text host",
                managerSource,
                "floatingWindow.configureSelectableText(contentView",
            ),
        )
        required.forEach { case -> assertTrue(case.name, case.source.contains(case.marker)) }

        assertEquals(
            "floating content must configure exactly one selectable host",
            1,
            Regex("floatingWindow\\.configureSelectableText\\(").findAll(
                buildFloatingContentSource(managerSource),
            ).count(),
        )

        assertFalse(
            "floating content must not open a second copy overlay",
            buildFloatingContentSource(managerSource).contains("TranslationBlockCopyOverlay"),
        )
        assertFalse(
            "the current floating shell must no longer be attached as a raw WindowManager view",
            windowSource.contains("wm.addView(root, params)"),
        )
        val configureSelection = windowSource.substring(
            windowSource.indexOf("fun configureSelectableText("),
            windowSource.indexOf("private fun showDialogHost", windowSource.indexOf("fun configureSelectableText(")),
        )
        assertFalse(
            "selection focus must not be toggled during an in-flight touch gesture",
            configureSelection.contains("MotionEvent.ACTION_DOWN"),
        )
    }

    private fun sourceFile(name: String): File =
        listOf(
            File("src/main/java/com/gameocr/app/overlay/$name"),
            File("app/src/main/java/com/gameocr/app/overlay/$name"),
        ).firstOrNull(File::isFile) ?: error("$name not found")

    private fun buildFloatingContentSource(source: String): String {
        val start = source.indexOf("private fun buildFloatingContent(")
        val end = source.indexOf("private fun DraggableOverlayWindow.applySettings()", start)
        check(start >= 0 && end > start) { "buildFloatingContent source not found" }
        return source.substring(start, end)
    }
}
