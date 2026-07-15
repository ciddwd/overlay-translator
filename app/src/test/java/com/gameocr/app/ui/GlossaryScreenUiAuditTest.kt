package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryScreenUiAuditTest {
    private val source by lazy { sourceFile("src/main/java/com/gameocr/app/ui/GlossaryScreen.kt").readText() }

    @Test
    fun glossaryScreen_usesSharedPickersAndSettingsStyling() {
        data class Case(val name: String, val marker: String)

        listOf(
            Case("system back handling", "BackHandler(onBack = onBack)"),
            Case("matching top app bar", "TopAppBarDefaults.topAppBarColors"),
            Case("term card", "private fun GlossaryTermCard"),
            Case("compact card radius", "RoundedCornerShape(8.dp)"),
            Case("outlined term card", "MaterialTheme.colorScheme.outlineVariant"),
            Case("source language picker", "label = stringResource(R.string.glossary_source_language)"),
            Case("target language picker", "label = stringResource(R.string.glossary_target_language)"),
            Case("manual app scope", "GlossaryScopeMode.SELECTED_APP"),
            Case("top bar filter action", "IconButton(onClick = { showFilter = true })"),
            Case("fuzzy list filter", "GlossaryListFilterPolicy.filter"),
            Case("category filter chips", "selected = category in categories"),
            Case("enabled status label", "R.string.glossary_status_enabled"),
            Case("localized language names", "Languages.nameOf(context, term.sourceLang)"),
            Case("language names are displayed", "text = \"\$sourceLanguage -> \$targetLanguage\""),
            Case("delete confirmation", "R.string.glossary_delete_confirm_title"),
            Case(
                "lightweight destructive confirmation",
                "DestructiveTextButton(label = confirmLabel, onClick = onConfirm)",
            ),
            Case("duplicate confirmation", "R.string.glossary_duplicate_title"),
            Case("transactional duplicate overwrite", "viewModel.overwriteConflict(conflict.pending)"),
            Case("searchable app picker", "private fun GlossaryAppPickerDialog"),
            Case("app name and package search", "SelectableAppPolicy.filter(apps, query)"),
            Case("virtualized app list", "items(filteredApps, key = SelectableApp::packageName)"),
            Case("cached application icon", "remember(icon) { icon.asImageBitmap() }"),
            Case("application icon placeholder", "imageVector = Icons.Default.Apps"),
            Case("selected app package", "text = selectedApp!!.packageName"),
            Case("custom responsive dialog", "DialogProperties(usePlatformDefaultWidth = false)"),
            Case("fixed dialog footer divider", "HorizontalDivider(color = zinc.border)"),
            Case("shared settings switch", "SwitchRow(stringResource(R.string.glossary_case_sensitive)"),
            Case("light zinc surface", "Color(0xFFFAFAFA)"),
            Case("dark zinc surface", "Color(0xFF18181B)"),
        ).forEach { case -> assertTrue(case.name, source.contains(case.marker)) }

        assertEquals("editor has exactly two language pickers", 2, source.countOccurrences("LanguagePicker("))
        assertEquals("editor has exactly two settings switches", 2, source.countOccurrences("SwitchRow("))
        assertFalse("source language is not a free text field", source.contains("onValueChange = { sourceLang = it }"))
        assertFalse("target language is not a free text field", source.contains("onValueChange = { targetLang = it }"))
        assertFalse("list language labels must not expose codes", source.contains("(\${term.sourceLang})"))
        assertFalse(
            "delete confirmation must not use a filled error button",
            source.contains("buttonColors(containerColor = MaterialTheme.colorScheme.error)"),
        )
        assertFalse("stock alert dialog would reintroduce a non-zinc container", source.contains("AlertDialog("))
        assertFalse("raw switches would drift from settings styling", source.contains("Switch("))
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
