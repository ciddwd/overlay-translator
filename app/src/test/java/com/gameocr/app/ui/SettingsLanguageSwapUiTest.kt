package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLanguageSwapUiTest {

    @Test
    fun languageSwap_tableDriven_usesDisabledConflictRowsAndConfirmationDialog() {
        val settingsSource = settingsSourceFile().readText()
        val pickerSource = pickerSourceFile().readText()
        val sourcePickerStart = settingsSource.indexOf(
            "SettingsSearchTarget(searchTargetRegistry, *SEARCH_TARGET_SOURCE_LANGUAGE)"
        )
        val targetPickerStart = settingsSource.indexOf(
            "SettingsSearchTarget(searchTargetRegistry, *SEARCH_TARGET_TARGET_LANGUAGE)"
        )
        val sourcePickerBlock = settingsSource.substring(sourcePickerStart, targetPickerStart)
        val targetPickerBlock = settingsSource.substring(targetPickerStart)
        val languageRowSource = pickerSource.substring(
            pickerSource.indexOf("private fun LanguageRow(")
        )

        data class Case(val name: String, val actual: Boolean)

        listOf(
            Case(
                "source conflict row opens a swap request",
                sourcePickerBlock.contains(
                    "pendingLanguageSwapOrigin = LanguageSwapRequestOrigin.SOURCE_PICKER"
                ),
            ),
            Case(
                "target conflict row opens a swap request",
                targetPickerBlock.contains(
                    "pendingLanguageSwapOrigin = LanguageSwapRequestOrigin.TARGET_PICKER"
                ),
            ),
            Case(
                "conflict prompt uses the Catalyst dialog",
                settingsSource.contains("pendingLanguageSwapOrigin?.let { origin ->") &&
                    settingsSource.contains("CatalystAlertDialog("),
            ),
            Case(
                "confirmation performs the atomic swap",
                settingsSource.contains("if (swapAvailable) swapSelectedLanguages()"),
            ),
            Case(
                "swap uses one validated pair result",
                settingsSource.contains(
                    "val swapped = swappedTranslationLanguagePair(sourceLang, targetLang) ?: return"
                ),
            ),
            Case(
                "both states come from the same swapped result",
                settingsSource.contains("sourceLang = swapped.first") &&
                    settingsSource.contains("targetLang = swapped.second"),
            ),
            Case(
                "picker keeps disabled styling separate from clickability",
                pickerSource.contains("enabled = !disabled") &&
                    pickerSource.contains(
                        "clickEnabled = tapAction != LanguagePickerRowTapAction.NONE"
                    ),
            ),
            Case(
                "star action remains enabled on a disabled conflict row",
                languageRowSource.contains("IconButton(onClick = onTogglePin)"),
            ),
            Case(
                "star action does not inherit the row enabled state",
                !languageRowSource.contains(
                    "IconButton(onClick = onTogglePin, enabled = enabled)"
                ),
            ),
            Case(
                "disabled conflict tap is routed separately from selection",
                pickerSource.contains("LanguagePickerRowTapAction.DISABLED_ACTION ->") &&
                    pickerSource.contains("onDisabledSelect?.invoke(lang.code)"),
            ),
            Case(
                "sheet hides before the conflict dialog callback",
                pickerSource.indexOf("sheetState.hide()") <
                    pickerSource.indexOf("onDisabledSelect?.invoke(lang.code)"),
            ),
            Case(
                "automatic detection boundary has a non-swap explanation",
                settingsSource.contains(
                    "R.string.settings_language_conflict_cannot_swap_message"
                ),
            ),
        ).forEach { case ->
            assertTrue(case.name, case.actual)
        }

        assertFalse(
            "settings must not show an independent swap button",
            settingsSource.contains("Icons.Default.SwapVert") ||
                settingsSource.contains("onClick = ::swapSelectedLanguages"),
        )
    }

    private fun settingsSourceFile(): File =
        listOf(
            File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt"),
            File("app/src/main/java/com/gameocr/app/ui/SettingsScreen.kt"),
        ).firstOrNull(File::isFile) ?: error("SettingsScreen.kt not found")

    private fun pickerSourceFile(): File =
        listOf(
            File("src/main/java/com/gameocr/app/ui/LanguagePicker.kt"),
            File("app/src/main/java/com/gameocr/app/ui/LanguagePicker.kt"),
        ).firstOrNull(File::isFile) ?: error("LanguagePicker.kt not found")
}
