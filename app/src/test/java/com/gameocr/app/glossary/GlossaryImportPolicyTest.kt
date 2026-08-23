package com.gameocr.app.glossary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryImportPolicyTest {
    @Test
    fun parse_tableDriven_acceptsCanonicalConvenienceAndChineseKeys() {
        data class Case(val name: String, val json: String, val expected: List<Pair<String, String>>)

        listOf(
            Case(
                "canonical wrapper",
                """{"format":"overlay-translator.glossary","version":1,"terms":[{"source":"梓ちゃん","target":"梓酱"}]}""",
                listOf("梓ちゃん" to "梓酱"),
            ),
            Case(
                "bare array",
                """[{"source":"先輩","target":"学长"}]""",
                listOf("先輩" to "学长"),
            ),
            Case(
                "Chinese aliases with BOM",
                "\uFEFF" + """[{"原文":"勇者","译文":"勇者"}]""",
                listOf("勇者" to "勇者"),
            ),
        ).forEach { case ->
            val actual = GlossaryImportJson.parse(case.json).entries.map { it.source to it.target }
            assertEquals(case.name, case.expected, actual)
        }
    }

    @Test
    fun parse_tableDriven_rejectsUnsupportedRootsAndMetadata() {
        data class Case(val name: String, val json: String)

        listOf(
            Case("primitive root", "true"),
            Case("missing format", """{"version":1,"terms":[]}"""),
            Case(
                "wrong format",
                """{"format":"other.glossary","version":1,"terms":[]}""",
            ),
            Case(
                "wrong version",
                """{"format":"overlay-translator.glossary","version":2,"terms":[]}""",
            ),
            Case(
                "terms is not an array",
                """{"format":"overlay-translator.glossary","version":1,"terms":{}}""",
            ),
        ).forEach { case ->
            val failure = runCatching { GlossaryImportJson.parse(case.json) }.exceptionOrNull()
            assertTrue(case.name, failure is GlossaryImportFormatException)
        }
    }

    @Test
    fun preview_tableDriven_classifiesDatabaseAndValidationCases() {
        val options = options()
        val existing = listOf(
            term("Alice", "爱丽丝"),
            term("Bob", "鲍勃"),
        )
        val cases = listOf(
            GlossaryImportEntry(1, "Carol", "卡萝尔") to GlossaryImportRowStatus.READY,
            GlossaryImportEntry(2, "Alice", "爱丽丝") to GlossaryImportRowStatus.WILL_SKIP,
            GlossaryImportEntry(3, "Bob", "罗伯特") to GlossaryImportRowStatus.WILL_SKIP,
            GlossaryImportEntry(4, "", "空") to GlossaryImportRowStatus.EMPTY_SOURCE,
            GlossaryImportEntry(5, "Empty", "") to GlossaryImportRowStatus.EMPTY_TARGET,
            GlossaryImportEntry(6, "S".repeat(201), "过长") to
                GlossaryImportRowStatus.SOURCE_TOO_LONG,
            GlossaryImportEntry(7, "Long", "T".repeat(501)) to
                GlossaryImportRowStatus.TARGET_TOO_LONG,
        )
        val document = GlossaryImportDocument(cases.map { it.first })
        val preview = GlossaryImportPreviewPolicy.preview(
            document = document,
            existingTerms = existing,
            options = options,
            selectedIndices = GlossaryImportPreviewPolicy.defaultSelection(document),
        )

        val statusByIndex = preview.rows.associate { it.entry.index to it.status }
        cases.forEach { (entry, expectedStatus) ->
            assertEquals("entry ${entry.index}", expectedStatus, statusByIndex[entry.index])
        }
        assertEquals(
            "invalid and review-required rows must precede ready rows",
            listOf(4, 5, 6, 7, 2, 3, 1),
            preview.rows.map { it.entry.index },
        )
        assertEquals(1, preview.importableCount)
        assertEquals(4, preview.invalidCount)
        assertTrue(preview.canImport)
    }

    @Test
    fun preview_overwritePolicy_onlyMarksChangedExistingTerms() {
        val document = GlossaryImportDocument(
            listOf(
                GlossaryImportEntry(1, "Alice", "爱丽丝"),
                GlossaryImportEntry(2, "Bob", "罗伯特"),
            ),
        )
        val preview = GlossaryImportPreviewPolicy.preview(
            document = document,
            existingTerms = listOf(term("Alice", "爱丽丝"), term("Bob", "鲍勃")),
            options = options(GlossaryImportConflictPolicy.OVERWRITE),
            selectedIndices = GlossaryImportPreviewPolicy.defaultSelection(document),
        )

        assertEquals(
            listOf(GlossaryImportRowStatus.WILL_OVERWRITE, GlossaryImportRowStatus.WILL_OVERWRITE),
            preview.rows.map { it.status },
        )
        assertEquals(2, preview.overwriteCount)
    }

    @Test
    fun preview_duplicateCases_requireResolvingDifferentTranslations() {
        val exactDuplicate = GlossaryImportDocument(
            listOf(
                GlossaryImportEntry(1, "Alice", "爱丽丝"),
                GlossaryImportEntry(2, "Alice", "爱丽丝"),
            ),
        )
        val exactPreview = preview(exactDuplicate, setOf(1, 2))
        assertEquals(
            mapOf(
                1 to GlossaryImportRowStatus.READY,
                2 to GlossaryImportRowStatus.DUPLICATE,
            ),
            exactPreview.rows.associate { it.entry.index to it.status },
        )
        assertTrue(exactPreview.canImport)

        val conflicting = GlossaryImportDocument(
            listOf(
                GlossaryImportEntry(1, "Alice", "爱丽丝"),
                GlossaryImportEntry(2, "Alice", "艾丽丝"),
            ),
        )
        val blocked = preview(conflicting, setOf(1, 2))
        assertTrue(blocked.hasFileConflicts)
        assertFalse(blocked.canImport)

        val resolved = preview(conflicting, setOf(2))
        assertEquals(
            mapOf(
                1 to GlossaryImportRowStatus.DESELECTED,
                2 to GlossaryImportRowStatus.READY,
            ),
            resolved.rows.associate { it.entry.index to it.status },
        )
        assertTrue(resolved.canImport)
    }

    @Test
    fun preview_tableDriven_selectionChangesNeverReorderRows() {
        val document = GlossaryImportDocument(
            listOf(
                GlossaryImportEntry(1, "Ready A", "甲"),
                GlossaryImportEntry(2, "Ready B", "乙"),
                GlossaryImportEntry(3, "Conflict", "丙"),
                GlossaryImportEntry(4, "Conflict", "丁"),
                GlossaryImportEntry(5, "", "无效"),
            ),
        )
        val cases = listOf(
            "all selected" to setOf(1, 2, 3, 4),
            "ready row deselected" to setOf(2, 3, 4),
            "conflict row deselected" to setOf(1, 2, 4),
            "multiple rows deselected" to setOf(2, 4),
        )

        val expectedOrder = listOf(3, 4, 5, 1, 2)
        cases.forEach { (name, selected) ->
            assertEquals(name, expectedOrder, preview(document, selected).rows.map { it.entry.index })
        }
    }

    @Test
    fun preview_tableDriven_allowsDifferentSourcesWithTheSameTranslation() {
        data class Case(val name: String, val entries: List<GlossaryImportEntry>)

        listOf(
            Case(
                "Japanese honorific variants",
                listOf(
                    GlossaryImportEntry(1, "梓ちゃん", "梓酱"),
                    GlossaryImportEntry(2, "梓さん", "梓酱"),
                ),
            ),
            Case(
                "different English words",
                listOf(
                    GlossaryImportEntry(1, "display", "显示"),
                    GlossaryImportEntry(2, "show", "显示"),
                ),
            ),
        ).forEach { case ->
            val document = GlossaryImportDocument(case.entries)
            val preview = preview(document, case.entries.mapTo(mutableSetOf()) { it.index })

            assertTrue(case.name, preview.canImport)
            assertFalse(case.name, preview.hasFileConflicts)
            assertEquals(
                case.name,
                List(case.entries.size) { GlossaryImportRowStatus.READY },
                preview.rows.map { it.status },
            )
        }
    }

    @Test
    fun buildTerms_usesPageOptionsAndOnlyImportableRows() {
        val document = GlossaryImportDocument(
            listOf(
                GlossaryImportEntry(1, "Alice", "爱丽丝"),
                GlossaryImportEntry(2, "Alice", "爱丽丝"),
                GlossaryImportEntry(3, "Bob", "鲍勃"),
            ),
        )
        val options = options().copy(
            scopePackage = "example.game",
            appLabel = "Example",
            category = GlossaryTermCategory.PERSON,
            caseSensitive = true,
            enabled = false,
        )
        val preview = GlossaryImportPreviewPolicy.preview(
            document = document,
            existingTerms = emptyList(),
            options = options,
            selectedIndices = setOf(1, 2, 3),
        )
        val terms = GlossaryImportPreviewPolicy.buildTerms(preview, options)

        assertEquals(listOf("Alice", "Bob"), terms.map { it.sourceTerm })
        terms.forEach { term ->
            assertEquals("example.game", term.scopePackage)
            assertEquals("Example", term.appLabel)
            assertEquals(GlossaryTermCategory.PERSON, term.category)
            assertTrue(term.caseSensitive)
            assertFalse(term.enabled)
        }
    }

    private fun preview(
        document: GlossaryImportDocument,
        selected: Set<Int>,
    ): GlossaryImportPreview = GlossaryImportPreviewPolicy.preview(
        document = document,
        existingTerms = emptyList(),
        options = options(),
        selectedIndices = selected,
    )

    private fun options(
        policy: GlossaryImportConflictPolicy = GlossaryImportConflictPolicy.SKIP,
    ) = GlossaryImportOptions(
        scopePackage = "",
        appLabel = "",
        sourceLang = "ja",
        targetLang = "zh-CN",
        category = GlossaryTermCategory.TERM,
        caseSensitive = false,
        enabled = true,
        conflictPolicy = policy,
    )

    private fun term(source: String, target: String) = GlossaryTermEntity(
        sourceLang = "ja",
        targetLang = "zh-CN",
        sourceTerm = source,
        targetTerm = target,
    )
}
