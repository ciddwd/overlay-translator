package com.gameocr.app.ui

import com.gameocr.app.glossary.GlossaryImportRowStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryAddScreenUiAuditTest {
    private val addScreen by lazy {
        sourceFile("src/main/java/com/gameocr/app/ui/GlossaryAddScreen.kt").readText()
    }
    private val glossaryScreen by lazy {
        sourceFile("src/main/java/com/gameocr/app/ui/GlossaryScreen.kt").readText()
    }
    private val englishStrings by lazy {
        sourceFile("src/main/res/values/strings.xml").readText()
    }
    private val chineseStrings by lazy {
        sourceFile("src/main/res/values-zh-rCN/strings.xml").readText()
    }

    @Test
    fun addPage_tableDriven_keepsSingleAndBatchFlowsSeparate() {
        data class Case(val name: String, val source: String, val marker: String, val expected: Boolean = true)

        listOf(
            Case("term list owns the expanding add menu", glossaryScreen, "GlossaryAddFabMenu("),
            Case("single action has its own route", glossaryScreen, "addRoute = GlossaryAddRoute.SINGLE"),
            Case("batch action has its own route", glossaryScreen, "addRoute = GlossaryAddRoute.BATCH"),
            Case("single add has a dedicated screen", addScreen, "internal fun GlossaryAddScreen("),
            Case("batch import has a dedicated screen", addScreen, "internal fun GlossaryImportScreen("),
            Case("add page has no mode tabs", addScreen, "GlossaryAddTab", expected = false),
            Case("add page has no mode radio selector", addScreen, "RadioButton(", expected = false),
            Case("add page has no mode selector", addScreen, "GlossaryAddModeSelector", expected = false),
            Case("single term inputs use a card", addScreen, "fun GlossarySingleAddPane("),
            Case("source input exists", addScreen, "R.string.glossary_source_term"),
            Case("target input exists", addScreen, "R.string.glossary_target_term"),
            Case("single settings title is context specific", addScreen, "R.string.glossary_term_settings"),
            Case("batch settings uses the same clear title", addScreen, "title = stringResource(R.string.glossary_term_settings)"),
            Case("single add still checks conflicts", addScreen, "viewModel.findConflict(term)"),
            Case("batch import uses its own repository entry", addScreen, "viewModel.importTerms("),
            Case("source preservation still uses its editor", glossaryScreen, "editingPreservation = true"),
            Case("Chinese single label", chineseStrings, "单条添加"),
            Case("Chinese batch label", chineseStrings, "批量导入"),
            Case("Chinese JSON heading", chineseStrings, "从 JSON 文件导入"),
            Case("English batch label", englishStrings, "Batch import"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.marker in case.source)
        }
        val singlePane = addScreen.substringAfter("fun GlossarySingleAddPane(")
            .substringBefore("private fun GlossaryBatchImportPane(")
        val batchPane = addScreen.substringAfter("private fun GlossaryBatchImportPane(")
            .substringBefore("private fun GlossaryImportBottomAction(")
        assertTrue(
            "source and target inputs must stay inside the first single-add card",
            singlePane.indexOf("Card(") < singlePane.indexOf("R.string.glossary_source_term") &&
                singlePane.indexOf("R.string.glossary_target_term") <
                singlePane.indexOf("R.string.glossary_term_settings"),
        )
        assertTrue(
            "batch settings must be visible before a JSON file is selected",
            batchPane.indexOf("GlossaryImportOptionsEditor(") <
                batchPane.indexOf("if (document != null)"),
        )
        assertTrue(
            "the JSON file card must use the standard surface style",
            "CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)" in batchPane &&
                "CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)" !in batchPane,
        )
        data class VisibilityCase(
            val name: String,
            val pane: String,
            val visibleMarker: String,
            val hiddenMarker: String,
        )
        listOf(
            VisibilityCase(
                "single mode shows term fields and hides JSON import",
                singlePane,
                "R.string.glossary_source_term",
                "R.string.glossary_import_file_title",
            ),
            VisibilityCase(
                "batch mode shows JSON import and hides term fields",
                batchPane,
                "R.string.glossary_import_file_title",
                "R.string.glossary_source_term",
            ),
        ).forEach { case ->
            assertTrue(case.name, case.visibleMarker in case.pane && case.hiddenMarker !in case.pane)
        }
    }

    @Test
    fun addMenu_tableDriven_expandsTwoLabeledActionsAndCollapsesSafely() {
        val menu = glossaryScreen.substringAfter("private fun GlossaryAddFabMenu(")
            .substringBefore("private fun TranslationLibraryHelpDialog(")
        data class Case(val name: String, val marker: String)

        listOf(
            Case("animated expansion", "AnimatedVisibility("),
            Case("batch import label", "R.string.glossary_add_mode_batch"),
            Case("single add label", "R.string.glossary_add_mode_single"),
            Case("batch file icon", "Icons.Default.Description"),
            Case("single edit icon", "Icons.Default.Edit"),
            Case("main action changes to close", "if (expanded) Icons.Default.Close else Icons.Default.Add"),
            Case("back closes the menu first", "if (addMenuExpanded) addMenuExpanded = false else onBack()"),
            Case("blank area closes the menu", "indication = null"),
            Case("tab changes close the menu", "LaunchedEffect(selectedTab)"),
        ).forEach { case -> assertTrue(case.name, case.marker in glossaryScreen || case.marker in menu) }

        assertEquals(
            "the expanded menu must contain exactly two labeled floating actions",
            2,
            Regex("ExtendedFloatingActionButton\\(").findAll(menu).count(),
        )
        assertTrue(
            "batch import must be above single add",
            menu.indexOf("R.string.glossary_add_mode_batch") <
                menu.indexOf("R.string.glossary_add_mode_single"),
        )
    }

    @Test
    fun previewTrailingStatus_tableDriven_hidesOnlyTheNormalReadyLabel() {
        data class Case(
            val status: GlossaryImportRowStatus,
            val expectedVisible: Boolean,
        )

        listOf(
            Case(GlossaryImportRowStatus.READY, false),
            Case(GlossaryImportRowStatus.WILL_OVERWRITE, true),
            Case(GlossaryImportRowStatus.WILL_SKIP, true),
            Case(GlossaryImportRowStatus.DUPLICATE, true),
            Case(GlossaryImportRowStatus.FILE_CONFLICT, true),
            Case(GlossaryImportRowStatus.DESELECTED, true),
            Case(GlossaryImportRowStatus.EMPTY_SOURCE, true),
            Case(GlossaryImportRowStatus.EMPTY_TARGET, true),
            Case(GlossaryImportRowStatus.SOURCE_TOO_LONG, true),
            Case(GlossaryImportRowStatus.TARGET_TOO_LONG, true),
        ).forEach { case ->
            assertEquals(
                case.status.name,
                case.expectedVisible,
                GlossaryImportPreviewStatusPolicy.showsTrailingStatus(case.status),
            )
        }
    }

    @Test
    fun previewFilters_tableDriven_coverEveryStatusWithoutChangingRowOrder() {
        data class Case(
            val status: GlossaryImportRowStatus,
            val expectedFilters: Set<GlossaryImportPreviewFilter>,
        )

        val cases = listOf(
            Case(GlossaryImportRowStatus.READY, setOf(GlossaryImportPreviewFilter.IMPORTABLE)),
            Case(GlossaryImportRowStatus.WILL_OVERWRITE, setOf(
                GlossaryImportPreviewFilter.IMPORTABLE,
                GlossaryImportPreviewFilter.OVERWRITE,
            )),
            Case(GlossaryImportRowStatus.WILL_SKIP, setOf(GlossaryImportPreviewFilter.SKIPPED)),
            Case(GlossaryImportRowStatus.DUPLICATE, setOf(GlossaryImportPreviewFilter.SKIPPED)),
            Case(GlossaryImportRowStatus.DESELECTED, setOf(GlossaryImportPreviewFilter.SKIPPED)),
            Case(GlossaryImportRowStatus.FILE_CONFLICT, setOf(GlossaryImportPreviewFilter.PROBLEM)),
            Case(GlossaryImportRowStatus.EMPTY_SOURCE, setOf(GlossaryImportPreviewFilter.PROBLEM)),
            Case(GlossaryImportRowStatus.EMPTY_TARGET, setOf(GlossaryImportPreviewFilter.PROBLEM)),
            Case(GlossaryImportRowStatus.SOURCE_TOO_LONG, setOf(GlossaryImportPreviewFilter.PROBLEM)),
            Case(GlossaryImportRowStatus.TARGET_TOO_LONG, setOf(GlossaryImportPreviewFilter.PROBLEM)),
        )

        cases.forEachIndexed { index, case ->
            val row = com.gameocr.app.glossary.GlossaryImportPreviewRow(
                entry = com.gameocr.app.glossary.GlossaryImportEntry(index + 1, "source", "target"),
                status = case.status,
                selected = case.status != GlossaryImportRowStatus.DESELECTED,
                selectable = true,
            )
            GlossaryImportPreviewFilter.entries.forEach { filter ->
                val expected = filter == GlossaryImportPreviewFilter.ALL || filter in case.expectedFilters
                assertEquals(
                    "${case.status} in $filter",
                    expected,
                    GlossaryImportPreviewFilterPolicy.matches(filter, row),
                )
            }
        }
    }

    @Test
    fun existingTermConflictPolicy_usesAConnectedSingleChoiceGroup() {
        val group = addScreen.substringAfter("private fun GlossaryImportConflictPolicyGroup(")
            .substringBefore("private fun GlossaryImportPreviewTabs(")
        data class Case(val name: String, val marker: String)

        listOf(
            Case("connected group", "SingleChoiceSegmentedButtonRow("),
            Case("two connected options", "SegmentedButton("),
            Case("position-aware shapes", "SegmentedButtonDefaults.itemShape("),
            Case("skip option", "GlossaryImportConflictPolicy.SKIP"),
            Case("overwrite option", "GlossaryImportConflictPolicy.OVERWRITE"),
        ).forEach { case -> assertTrue(case.name, case.marker in group) }

        assertEquals(
            "the group must contain exactly one segmented-button declaration rendered for two options",
            1,
            Regex("SegmentedButton\\(").findAll(group).count(),
        )
        assertFalse("loose chips must not be used for this choice", "EngineChip(" in group)
    }

    @Test
    fun batchImport_tableDriven_usesSafAndPreviewBeforeCommit() {
        data class Case(val name: String, val marker: String)

        listOf(
            Case("system document picker", "ActivityResultContracts.OpenDocument()"),
            Case("no persistent file permission", "readGlossaryImportFile(context, uri)"),
            Case("size-limited streaming read", "total > GLOSSARY_IMPORT_MAX_FILE_BYTES"),
            Case("preview recalculates from options", "GlossaryImportPreviewPolicy.preview("),
            Case("file conflicts block import", "currentPreview.canImport"),
            Case("terms are built only after preview", "GlossaryImportPreviewPolicy.buildTerms("),
            Case("import action is fixed below the list", "GlossaryImportBottomAction("),
            Case("preview list owns the remaining height", "modifier = Modifier.weight(1f)"),
            Case("Snackbar reports failures", "SnackbarHost(snackbarHostState)"),
            Case(
                "preview row applies trailing-status visibility policy",
                "GlossaryImportPreviewStatusPolicy.showsTrailingStatus(row.status)",
            ),
            Case("summary card is replaced by filter tabs", "GlossaryImportPreviewTabs("),
            Case("tabs filter the existing ordered rows", "items(filteredRows"),
        ).forEach { case ->
            assertTrue(case.name, addScreen.contains(case.marker))
        }
        assertTrue(
            "preview must run before the database import",
            addScreen.indexOf("GlossaryImportPreviewPolicy.preview(") <
                addScreen.indexOf("viewModel.importTerms("),
        )
        assertTrue(
            "the fixed import action must be composed after preview rows",
            addScreen.indexOf("items(filteredRows") <
                addScreen.indexOf("GlossaryImportBottomAction("),
        )
        assertFalse("the old preview summary card must be removed", "GlossaryImportSummaryCard(" in addScreen)
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
