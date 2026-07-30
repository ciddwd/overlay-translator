package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryScreenUiAuditTest {
    private val source by lazy { sourceFile("src/main/java/com/gameocr/app/ui/GlossaryScreen.kt").readText() }
    private val memorySource by lazy {
        sourceFile("src/main/java/com/gameocr/app/ui/TranslationMemoryPane.kt").readText()
    }
    private val englishStrings by lazy {
        sourceFile("src/main/res/values/strings.xml").readText()
    }
    private val chineseStrings by lazy {
        sourceFile("src/main/res/values-zh-rCN/strings.xml").readText()
    }

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
            Case("top bar filter action", "TranslationLibraryTab.TERMS -> showFilter = true"),
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

    @Test
    fun translationLibrary_tableDriven_exposesBothManagedResourceTypes() {
        data class Case(
            val name: String,
            val sourceText: String,
            val marker: String,
        )

        listOf(
            Case("terms tab", source, "R.string.translation_library_terms_tab"),
            Case("translation memory tab", source, "R.string.translation_library_memory_tab"),
            Case("memory flow is collected", source, "viewModel.memories.collectAsState()"),
            Case("memory search", memorySource, "TranslationMemoryListFilterPolicy.filter"),
            Case("memory query is owned by the library", source, "var memoryQuery by rememberSaveable"),
            Case("memory query is passed into its list", source, "query = memoryQuery"),
            Case("memory filter dialog", source, "private fun TranslationMemoryFilterDialog"),
            Case("memory filter entry", source, "TranslationLibraryTab.MEMORY -> showMemoryFilter = true"),
            Case("memory filter active tint", source, "TranslationLibraryTab.MEMORY -> memoryQuery.isNotBlank()"),
            Case("memory card", memorySource, "private fun TranslationMemoryCard"),
            Case("memory editor", memorySource, "private fun TranslationMemoryEditor"),
            Case("memory delete confirmation", memorySource, "private fun TranslationMemoryDeleteDialog"),
            Case("memory edit callback", source, "viewModel.updateMemory"),
            Case("memory delete callback", source, "viewModel.deleteMemory"),
        ).forEach { case ->
            assertTrue(case.name, case.sourceText.contains(case.marker))
        }
    }

    @Test
    fun translationLibraryFilters_tableDriven_shareTheTopBarDialogPattern() {
        val memoryList = memorySource.substring(
            memorySource.indexOf("internal fun TranslationMemoryPane("),
            memorySource.indexOf("private fun TranslationMemoryCard("),
        )

        data class Case(
            val name: String,
            val content: String,
            val marker: String,
            val expected: Boolean,
        )

        listOf(
            Case(
                "terms open from the shared top-bar action",
                source,
                "TranslationLibraryTab.TERMS -> showFilter = true",
                true,
            ),
            Case(
                "memory opens from the shared top-bar action",
                source,
                "TranslationLibraryTab.MEMORY -> showMemoryFilter = true",
                true,
            ),
            Case(
                "memory dialog uses the shared zinc surface",
                source,
                "text = stringResource(R.string.translation_memory_filter_title)",
                true,
            ),
            Case(
                "memory dialog keeps an outlined query field",
                source,
                "R.string.translation_memory_search_hint",
                true,
            ),
            Case(
                "memory dialog supports clearing its draft",
                source,
                "IconButton(onClick = { query = \"\" })",
                true,
            ),
            Case(
                "memory dialog applies its draft explicitly",
                source,
                "Button(onClick = { onApply(query) })",
                true,
            ),
            Case(
                "memory list no longer owns a persistent query field",
                memoryList,
                "OutlinedTextField(",
                false,
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.marker in case.content)
        }
    }

    @Test
    fun translationLibraryHelp_tableDriven_keepsApprovedContentAndLayout() {
        val topBar = source.substring(
            source.indexOf("TopAppBar("),
            source.indexOf("floatingActionButton ="),
        )
        val helpDialog = source.substring(
            source.indexOf("private fun TranslationLibraryHelpDialog("),
            source.indexOf("private fun TranslationMemoryFilterDialog("),
        )

        data class Case(
            val name: String,
            val content: String,
            val marker: String,
            val expected: Boolean = true,
        )

        listOf(
            Case("help icon follows the navbar title", topBar, "Icons.AutoMirrored.Outlined.HelpOutline"),
            Case("help icon opens the help dialog", topBar, "onClick = { showHelp = true }"),
            Case("help dialog uses zinc styling", helpDialog, "color = zinc.surface"),
            Case("help dialog body scrolls internally", helpDialog, ".verticalScroll(rememberScrollState())"),
            Case("help dialog has a fixed footer", helpDialog, "R.string.translation_library_help_close"),
            Case("terms help section", helpDialog, "R.string.translation_library_help_terms_body"),
            Case("memory help section", helpDialog, "R.string.translation_library_help_memory_body"),
            Case("priority help section", helpDialog, "R.string.translation_library_help_priority_body"),
            Case("correction help section", helpDialog, "R.string.translation_library_help_add_body"),
            Case(
                "correction illustration follows its help text",
                helpDialog,
                "imageRes = R.drawable.translation_correction_help",
            ),
            Case(
                "correction illustration has an accessible description",
                helpDialog,
                "R.string.translation_library_help_add_image_description",
            ),
            Case("help illustration fills the available width", helpDialog, ".fillMaxWidth()"),
            Case("help illustration preserves its aspect ratio", helpDialog, ".aspectRatio(1117f / 633f)"),
            Case(
                "help illustration uses a one-dp theme border",
                helpDialog,
                "MaterialTheme.colorScheme.outlineVariant",
            ),
            Case("management help section", helpDialog, "R.string.translation_library_help_manage_body"),
            Case("Chinese copy explains terms", chineseStrings, "术语：统一角色名和专有名词"),
            Case("Chinese copy explains memory", chineseStrings, "翻译记忆：记住你改过的整句话"),
            Case("Chinese copy explains priority", chineseStrings, "两者谁先使用"),
            Case("English copy is present", englishStrings, "Which one is used first?"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.marker in case.content)
        }

        assertTrue(
            "help icon must be placed after the translation library title",
            topBar.indexOf("R.string.glossary_title") <
                topBar.indexOf("Icons.AutoMirrored.Outlined.HelpOutline"),
        )
        assertTrue(
            "correction illustration must appear after the correction copy",
            helpDialog.indexOf("R.string.translation_library_help_add_body") <
                helpDialog.indexOf("R.drawable.translation_correction_help"),
        )
        assertTrue(
            "management help must stay below the correction illustration",
            helpDialog.indexOf("R.drawable.translation_correction_help") <
                helpDialog.indexOf("R.string.translation_library_help_manage_title"),
        )
        assertTrue(
            "compressed correction illustration must be packaged",
            sourceFile("src/main/res/drawable-nodpi/translation_correction_help.jpg").isFile,
        )
    }

    @Test
    fun translationLibraryCards_tableDriven_shareTheSourceTranslationLayout() {
        val termCard = source.substring(
            source.indexOf("private fun GlossaryTermCard("),
            source.indexOf("private fun GlossaryTermEditor("),
        )
        val memoryCard = memorySource.substring(
            memorySource.indexOf("private fun TranslationMemoryCard("),
            memorySource.indexOf("private fun TranslationMemoryEditor("),
        )

        data class Case(
            val name: String,
            val content: String,
            val marker: String,
            val expected: Boolean,
        )

        listOf(
            Case("term source has its own row", termCard, "text = term.sourceTerm", true),
            Case("term translation has its own row", termCard, "text = term.targetTerm", true),
            Case(
                "term source and translation no longer use an arrow title",
                termCard,
                "\${term.sourceTerm} -> \${term.targetTerm}",
                false,
            ),
            Case(
                "term translation matches memory emphasis",
                termCard,
                "color = MaterialTheme.colorScheme.primary",
                true,
            ),
            Case("memory app has its own row", memoryCard, "text = appLabel", true),
            Case(
                "memory language direction has its own row",
                memoryCard,
                "text = \"\$sourceLanguage -> \$targetLanguage\"",
                true,
            ),
            Case(
                "memory no longer combines app and languages",
                memoryCard,
                "translation_memory_scope_format",
                false,
            ),
            Case(
                "memory no longer shows usage count",
                memoryCard,
                "translation_memory_hit_count",
                false,
            ),
            Case("memory no longer reads hit count", memoryCard, "entry.hitCount", false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.marker in case.content)
        }
    }

    private fun String.countOccurrences(value: String): Int = windowed(value.length).count { it == value }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
