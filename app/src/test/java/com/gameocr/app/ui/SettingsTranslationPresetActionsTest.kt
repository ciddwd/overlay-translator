package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTranslationPresetActionsTest {

    @Test
    fun copyAction_isRemovedFromEverySettingsPresetUiLayer() {
        data class Case(
            val name: String,
            val forbiddenMarker: String,
        )

        listOf(
            Case(
                name = "section callback",
                forbiddenMarker = "onCopy",
            ),
            Case(
                name = "copy button label",
                forbiddenMarker = "settings_translation_preset_copy",
            ),
            Case(
                name = "copy visibility policy",
                forbiddenMarker = "translationPresetCopyVisible",
            ),
            Case(
                name = "duplicate action call",
                forbiddenMarker = "duplicateTranslationPreset(",
            ),
        ).forEach { case ->
            assertFalse(
                case.name,
                settingsSource().contains(case.forbiddenMarker),
            )
        }
    }

    @Test
    fun removingCopy_keepsCustomDeleteAction() {
        assertTrue(
            "custom delete action remains available",
            settingsSource().contains("settings_translation_preset_delete"),
        )
    }

    private fun settingsSource(): String =
        moduleFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
