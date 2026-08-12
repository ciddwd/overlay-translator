package com.gameocr.app.translate

import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.RuntimeDialogueTurn
import com.gameocr.app.data.RuntimeGlossaryTerm
import com.gameocr.app.data.RuntimeTranslationPromptContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class SakuraContextBatchPolicyTest {
    @Test
    fun promptScope_tableDriven_keepsFastIsolatedAndContextModesShared() {
        data class Case(
            val mode: TranslationContextMode,
            val expected: BatchPromptScope,
        )

        listOf(
            Case(TranslationContextMode.FAST_PER_SEGMENT, BatchPromptScope.ISOLATED_ITEMS),
            Case(TranslationContextMode.PAGE_CONTEXT, BatchPromptScope.SHARED_PAGE),
            Case(TranslationContextMode.CONTINUOUS_CONTEXT, BatchPromptScope.SHARED_PAGE),
        ).forEach { case ->
            assertEquals(
                case.mode.name,
                case.expected,
                SakuraBatchPromptScopePolicy.resolve(case.mode),
            )
        }
    }

    @Test
    fun promptContext_tableDriven_stripsDialogueOnlyFromFastMode() {
        val original = RuntimeTranslationPromptContext(
            currentApplication = "reader",
            glossary = listOf(RuntimeGlossaryTerm("name", "姓名")),
            currentPage = listOf("current-a", "current-b"),
            previousFrame = listOf(RuntimeDialogueTurn("previous", "上一句")),
        )
        data class Case(
            val mode: TranslationContextMode,
            val expectedCurrent: List<String>,
            val expectedPrevious: Int,
        )

        listOf(
            Case(TranslationContextMode.FAST_PER_SEGMENT, emptyList(), 0),
            Case(TranslationContextMode.PAGE_CONTEXT, original.currentPage, 1),
            Case(TranslationContextMode.CONTINUOUS_CONTEXT, original.currentPage, 1),
        ).forEach { case ->
            val actual = SakuraBatchPromptScopePolicy.promptContext(case.mode, original)
            assertEquals(case.mode.name, case.expectedCurrent, actual.currentPage)
            assertEquals(case.mode.name, case.expectedPrevious, actual.previousFrame.size)
            assertEquals(case.mode.name, original.glossary, actual.glossary)
            assertEquals(case.mode.name, original.currentApplication, actual.currentApplication)
        }
    }

    @Test
    fun plan_preservesOneRegionPerLine_tableDriven() {
        data class Case(
            val name: String,
            val sources: List<String>,
            val expected: String?,
        )

        listOf(
            Case("two regions", listOf("first", "second"), "first\nsecond"),
            Case("internal CRLF is flattened", listOf("first\r\npart", "second"), "first part\nsecond"),
            Case("internal CR is flattened", listOf("first\rpart", "second"), "first part\nsecond"),
            Case("single region uses normal translation", listOf("first"), null),
            Case("blank region is unsafe", listOf("first", "  "), null),
        ).forEach { case ->
            val plan = SakuraContextBatchPolicy.plan(case.sources)
            if (case.expected == null) {
                assertNull(case.name, plan)
            } else {
                assertNotNull(case.name, plan)
                assertEquals(case.name, case.expected, plan?.joinedSource)
                assertEquals(case.sources.size, plan?.sourceLines?.size)
            }
        }
    }

    @Test
    fun parse_requiresExactNonBlankLineCount_tableDriven() {
        data class Case(
            val name: String,
            val output: String?,
            val expectedCount: Int,
            val expected: List<String>?,
        )

        listOf(
            Case("single line", "translated", 1, listOf("translated")),
            Case("exact LF", "one\ntwo", 2, listOf("one", "two")),
            Case("CRLF", "one\r\ntwo", 2, listOf("one", "two")),
            Case("outer whitespace", "  one  \n  two  \n", 2, listOf("one", "two")),
            Case("missing line", "one", 2, null),
            Case("extra line", "one\ntwo\nthree", 2, null),
            Case("blank middle line", "one\n\nthree", 3, null),
            Case("zero expected lines", "one", 0, null),
            Case("null output", null, 2, null),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                SakuraContextBatchPolicy.parse(case.output, expectedCount = case.expectedCount),
            )
        }
    }

    @Test
    fun groups_tableDriven_respectsLineAndTokenBudgetsWithoutReordering() {
        data class Case(
            val name: String,
            val sources: List<String>,
            val maxTokens: Int,
            val expectedSizes: List<Int>?,
        )

        listOf(
            Case("single source is validated by Sakura path", listOf("a"), 100, listOf(1)),
            Case("blank source uses normal fallback", listOf("a", " "), 100, null),
            Case("nine short lines stay in one page request", List(9) { "line-$it" }, 100, listOf(9)),
            Case("23 short lines stay together when budget fits", List(23) { "x" }, 100, listOf(23)),
            Case("token budget splits only when required", listOf("aaaa", "bbbb", "cccc"), 9, listOf(2, 1)),
            Case("oversized single line remains addressable", listOf("0123456789", "b"), 4, listOf(1, 1)),
        ).forEach { case ->
            val groups = SakuraContextBatchPolicy.groups(
                sources = case.sources,
                maxPromptTokens = case.maxTokens,
                promptTokenCount = String::length,
            )
            assertEquals(case.name, case.expectedSizes, groups?.map { it.sourceLines.size })
            if (groups != null) {
                assertEquals(case.name, case.sources.map(String::trim), groups.flatMap { it.sourceLines })
                assertEquals(case.name, case.sources.indices.toList(), groups.flatMap { group ->
                    group.sourceLines.indices.map { group.startIndex + it }
                })
            }
        }
    }
}
