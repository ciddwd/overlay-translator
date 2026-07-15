package com.gameocr.app.glossary

import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryMatcherTest {
    @Test
    fun matching_cases() {
        val terms = listOf(
            term("Alice", "爱丽丝", scope = "", updated = 1),
            term("Alice", "艾莉丝", scope = "game.one", updated = 2),
            term("Alice Academy", "爱丽丝学园", scope = "", updated = 3),
            term("HP", "生命值", scope = "", caseSensitive = true, updated = 4),
            term("unused", "未使用", scope = "", enabled = false, updated = 5),
            term("Alice", "アリス", scope = "", targetLang = "ja", updated = 6),
        )
        data class Case(
            val name: String,
            val source: String,
            val packageName: String?,
            val expectedTargets: List<String>,
        )
        val cases = listOf(
            Case("app term overrides global", "Alice arrived", "game.one", listOf("艾莉丝")),
            Case("global fallback", "Alice arrived", "game.two", listOf("爱丽丝")),
            Case("longer terms sort first", "Alice Academy welcomes Alice", null, listOf("爱丽丝学园", "爱丽丝")),
            Case("case sensitive matches exact", "HP is full", null, listOf("生命值")),
            Case("case sensitive rejects lowercase", "hp is full", null, emptyList()),
        )
        cases.forEach { case ->
            val actual = GlossaryMatcher.match(
                source = case.source,
                sourceLang = "en",
                targetLang = "zh-CN",
                packageName = case.packageName,
                terms = terms,
            )
            assertEquals(case.name, case.expectedTargets, actual.map(GlossaryMatch::targetTerm))
        }
    }

    @Test
    fun limits_areAppliedDeterministically() {
        val terms = (1..10).map { index -> term("term$index", "译$index", updated = index.toLong()) }
        val source = terms.joinToString(" ") { it.sourceTerm }
        assertEquals(
            3,
            GlossaryMatcher.match(source, "en", "zh-CN", null, terms, maxTerms = 3).size,
        )
        assertEquals(
            1,
            GlossaryMatcher.match(source, "en", "zh-CN", null, terms, maxCharacters = 8).size,
        )
    }

    private fun term(
        source: String,
        target: String,
        scope: String = "",
        sourceLang: String = "en",
        targetLang: String = "zh-CN",
        caseSensitive: Boolean = false,
        enabled: Boolean = true,
        updated: Long,
    ) = GlossaryTermEntity(
        scopePackage = scope,
        sourceLang = sourceLang,
        targetLang = targetLang,
        sourceTerm = source,
        normalizedSourceTerm = normalizeGlossaryTerm(source, caseSensitive),
        targetTerm = target,
        caseSensitive = caseSensitive,
        enabled = enabled,
        updatedAtMs = updated,
    )
}
