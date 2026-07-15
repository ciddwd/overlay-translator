package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSettingsUiAuditTest {
    private val source by lazy { sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText() }

    @Test
    fun translationControls_followRequestedOrderAfterTargetLanguage() {
        data class Case(val name: String, val before: String, val after: String)

        listOf(
            Case("target before streaming", "R.string.settings_target_lang", "R.string.settings_streaming"),
            Case("streaming before empty retry", "R.string.settings_streaming", "R.string.settings_retry_empty_translation_label"),
            Case("empty retry before terminology consistency", "R.string.settings_retry_empty_translation_label", "R.string.settings_glossary_enabled"),
            Case("consistency before sending app name", "R.string.settings_glossary_enabled", "R.string.settings_send_app_name"),
            Case("send app name before app detection", "R.string.settings_send_app_name", "R.string.settings_foreground_app_detection"),
            Case("app detection before usage access", "R.string.settings_foreground_app_detection", "R.string.settings_grant_usage_access"),
            Case("usage access before terminology cell", "R.string.settings_grant_usage_access", "R.string.settings_manage_glossary"),
        ).forEach { case ->
            val beforeIndex = source.indexOf(case.before)
            val afterIndex = source.indexOf(case.after)
            assertTrue("${case.name}: missing ${case.before}", beforeIndex >= 0)
            assertTrue("${case.name}: missing ${case.after}", afterIndex >= 0)
            assertTrue(case.name, beforeIndex < afterIndex)
        }
    }

    @Test
    fun promptEditors_areInsideCollapsedAdvancedSection() {
        val advancedGate = source.indexOf("if (promptAdvancedExpanded)")
        listOf(
            "R.string.settings_prompt_label",
            "R.string.settings_dictionary_prompt_title",
            "R.string.settings_dictionary_prompt_desc",
        ).forEach { marker ->
            assertTrue("missing $marker", source.contains(marker))
            assertTrue("$marker must follow the advanced gate", advancedGate in 0 until source.indexOf(marker))
        }
    }

    @Test
    fun usageAndTerminologyEntries_useLinkCellsWithPermissionStatus() {
        data class Case(val name: String, val marker: String)

        listOf(
            Case("cell component", "private fun SettingsLinkCell"),
            Case("standard list cell", "ListItem("),
            Case("section-matching transparent background", "ListItemDefaults.colors(containerColor = Color.Transparent)"),
            Case("granted status", "R.string.settings_permission_granted"),
            Case("not granted status", "R.string.settings_permission_not_granted"),
            Case("resume refresh", "Lifecycle.Event.ON_RESUME"),
        ).forEach { case -> assertTrue(case.name, source.contains(case.marker)) }
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
