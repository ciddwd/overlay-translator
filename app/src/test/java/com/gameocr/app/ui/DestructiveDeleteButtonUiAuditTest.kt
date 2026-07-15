package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestructiveDeleteButtonUiAuditTest {
    private val settingsSource by lazy {
        sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
    }
    private val glossarySource by lazy {
        sourceFile("src/main/java/com/gameocr/app/ui/GlossaryScreen.kt").readText()
    }
    private val mainSource by lazy {
        sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
    }
    private val buttonSource by lazy {
        sourceFile("src/main/java/com/gameocr/app/ui/DestructiveTextButton.kt").readText()
    }

    @Test
    fun destructiveStyle_isLimitedToDeleteConfirmationActions() {
        data class Case(val name: String, val source: String, val marker: String)

        listOf(
            Case("red text button", buttonSource, "MaterialTheme.colorScheme.error"),
            Case("lightweight text button", buttonSource, "TextButton(onClick = onClick, enabled = enabled)"),
            Case("glossary delete confirmation", glossarySource, "DestructiveTextButton(label = confirmLabel, onClick = onConfirm)"),
            Case("preset delete confirmation", settingsSource, "label = stringResource(R.string.settings_translation_preset_delete)"),
            Case("font and model delete confirmations", settingsSource, "label = stringResource(R.string.settings_model_delete_confirm_yes)"),
            Case("preset list delete stays neutral", settingsSource, "TextButton(onClick = onDelete)"),
        ).forEach { case ->
            assertTrue(case.name, case.source.contains(case.marker))
        }

        assertEquals(
            "settings has font, preset, and four model delete confirmations",
            6,
            settingsSource.countOccurrences("DestructiveTextButton("),
        )
        assertEquals(
            "glossary has one destructive delete confirmation",
            1,
            glossarySource.countOccurrences("DestructiveTextButton("),
        )
        assertFalse(
            "clear and other main-screen actions must remain unchanged",
            mainSource.contains("DestructiveTextButton("),
        )
        assertFalse(
            "model delete confirmations must not use neutral text buttons",
            settingsSource.contains("Text(stringResource(R.string.settings_model_delete_confirm_yes))"),
        )
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
