package com.gameocr.app.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCardOverlayCopySupportTest {

    @Test
    fun resultCard_keepsWholeTextActionsAndNativePartialSelection() {
        val source = sourceFile().readText()
        data class Case(
            val name: String,
            val predicate: (String) -> Boolean,
        )

        val cases = listOf(
            Case("dictionary content uses one continuous selectable text host") {
                it.contains("text = buildSelectableDictionaryText(wordResult") &&
                    it.split("setTextIsSelectable(true)").size - 1 >= 3
            },
            Case("whole source copy action") { it.contains("copyToClipboard(sourceText)") },
            Case("whole translation copy action follows latest streamed text") {
                it.contains("currentTranslation.takeIf") && it.contains("::copyToClipboard")
            },
            Case("fixed action row remains outside scroll content") {
                it.indexOf("card.addView(actionRow)") > it.indexOf("card.addView(\n            scroll")
            },
            Case("copy panel uses a Dialog DecorView ActionMode host") {
                it.contains("Dialog(context, R.style.Theme_GameOcr_Transparent)") &&
                    it.contains("window.decorView.setBackgroundColor(Color.TRANSPARENT)")
            },
            Case("copy panel dialog remains a focusable overlay window") {
                it.contains("window.setType(overlayType)") &&
                    it.contains("WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE") &&
                    it.contains("WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL")
            },
            Case("copy panel dismisses its dialog host") {
                it.contains("currentDialog.dismiss()")
            },
            Case("raw WindowManager card host is removed") {
                !it.contains("wm.addView(backdrop")
            },
        )

        cases.forEach { case ->
            assertTrue(case.name, case.predicate(source))
        }
        assertFalse(
            "custom long-click copying must not replace Android text selection",
            source.contains("setOnLongClickListener"),
        )
        listOf(
            "wordResult.definitions.forEachIndexed",
            "wordResult.difficultyNotes.forEach",
            "wordResult.examples.forEach { example ->\n                container.addView",
        ).forEach { oldSplitRenderer ->
            assertFalse(
                "dictionary rows must not be split into separate selection hosts: $oldSplitRenderer",
                source.contains(oldSplitRenderer),
            )
        }
    }

    @Test
    fun dictionaryResult_tableDriven_reflowsTheAlreadyVisibleCard() {
        val source = sourceFile().readText()
        data class Case(
            val name: String,
            val requiredSource: String,
        )

        val cases = listOf(
            Case(
                "empty dictionary section is removed from layout",
                "dictionarySection.visibility = if (hasDictionaryContent) View.VISIBLE else View.GONE",
            ),
            Case("dictionary section requests measurement", "dictionarySection.requestLayout()"),
            Case("scroll content requests measurement", "scrollContent.requestLayout()"),
            Case("adaptive card requests measurement", "card.requestLayout()"),
            Case("attached dialog root requests measurement", "rootView?.requestLayout()"),
            Case("post-layout diagnostics report rendered children", "translationCard dictionary rendered children="),
        )

        cases.forEach { case ->
            assertTrue(case.name, source.contains(case.requiredSource))
        }
    }

    private fun sourceFile(): File =
        listOf(
            File("src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt"),
            File("app/src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt"),
        ).firstOrNull(File::isFile) ?: error("TranslationCardOverlay.kt not found")
}
