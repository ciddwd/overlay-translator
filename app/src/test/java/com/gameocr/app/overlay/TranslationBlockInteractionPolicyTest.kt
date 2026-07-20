package com.gameocr.app.overlay

import com.gameocr.app.data.TranslationBlockInteractionMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBlockInteractionPolicyTest {

    @Test
    fun interactionPlan_mapsEverySettingModeToSelectionBehavior() {
        data class Case(
            val mode: TranslationBlockInteractionMode,
            val nativeSelection: Boolean,
            val selectedTextSpeech: Boolean,
            val openPanelOnTap: Boolean,
            val focusableWindow: Boolean,
            val decorViewActionModeHost: Boolean,
        )

        val cases = listOf(
            Case(TranslationBlockInteractionMode.COPY_BUTTON, true, true, false, true, true),
            Case(TranslationBlockInteractionMode.OPEN_COPY_PANEL, false, false, true, false, false),
        )

        cases.forEach { case ->
            val actual = translationBlockInteractionPlan(case.mode)
            assertEquals(case.mode.name, case.nativeSelection, actual.enableNativeTextSelection)
            assertEquals(case.mode.name, case.selectedTextSpeech, actual.enableSelectedTextSpeech)
            assertEquals(case.mode.name, case.openPanelOnTap, actual.openCopyPanelOnBlockTap)
            assertEquals(case.mode.name, case.focusableWindow, actual.windowFocusable)
            assertEquals(case.mode.name, case.decorViewActionModeHost, actual.useDecorViewActionModeHost)
        }
    }

    @Test
    fun overlayManager_usesNativeSelectionWithoutCopyIcons() {
        val source = sourceFile().readText()

        data class Case(val name: String, val marker: String)
        val required = listOf(
            Case("native text selection", "setTextIsSelectable(true)"),
            Case("native selection adds app speech action", "enableSelectionSpeech("),
            Case("speech action follows live TTS state", "translationBlockSelectionSpeechAction != null"),
            Case("speech action reads selected text", "onStart?.invoke(selectedText)"),
            Case("selection view takes focus on touch", "MotionEvent.ACTION_DOWN ->"),
            Case("focusable result window", "newLayoutParams(focusable = interactionPlan.windowFocusable)"),
            Case("native selection uses a DecorView action mode host", "showBlocksInActionModeDialog(root, params)"),
            Case("overlay dialog owns a PhoneWindow", "Dialog(context, R.style.Theme_GameOcr_Transparent)"),
            Case("dialog keeps the application overlay type", "window.setType(params.type)"),
            Case("short tap panel mode", "if (plan.openCopyPanelOnBlockTap)"),
            Case("short tap close mode", "view.setOnClickListener { clear() }"),
            Case("panel mode disables direct long press", "view.isLongClickable = false"),
            Case("panel mode clears long-click listener", "view.setOnLongClickListener(null)"),
        )
        required.forEach { case -> assertTrue(case.name, source.contains(case.marker)) }

        val removed = listOf(
            "addTranslationBlockCopyButton",
            "blockCopyButtons",
            "ic_content_copy",
            "copyTranslationBlockToClipboard",
            "openCopyPanelWhenNativeSelectionIsUnavailable",
        )
        removed.forEach { marker -> assertFalse(marker, source.contains(marker)) }
    }

    @Test
    fun styledTranslationTextView_usesPlatformTextViewForNativeActionMode() {
        val source = listOf(
            File("src/main/java/com/gameocr/app/overlay/StyledTranslationTextView.kt"),
            File("app/src/main/java/com/gameocr/app/overlay/StyledTranslationTextView.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("StyledTranslationTextView.kt not found")

        assertTrue(source.contains("import android.widget.TextView"))
        assertTrue(source.contains("StyledTranslationTextView(context: Context) : TextView(context)"))
        assertTrue(source.contains("override fun startActionMode"))
        assertTrue(source.contains("started=${'$'}{mode != null}"))
        assertFalse(source.contains("AppCompatTextView"))
    }

    @Test
    fun actionableText_rejectsMissingBlankAndLoadingContent() {
        data class Case(val name: String, val text: String?, val expected: Boolean)

        val cases = listOf(
            Case("missing", null, false),
            Case("empty", "", false),
            Case("whitespace", "  \n", false),
            Case("unicode loading ellipsis", "…", false),
            Case("three-dot loading ellipsis", "...", false),
            Case("translated sentence", "Translation is ready", true),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, isTranslationBlockTextActionable(case.text))
        }
    }

    private fun sourceFile(): File =
        listOf(
            File("src/main/java/com/gameocr/app/overlay/OverlayManager.kt"),
            File("app/src/main/java/com/gameocr/app/overlay/OverlayManager.kt"),
        ).firstOrNull(File::isFile) ?: error("OverlayManager.kt not found")
}
