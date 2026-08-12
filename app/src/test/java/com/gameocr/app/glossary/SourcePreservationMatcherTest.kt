package com.gameocr.app.glossary

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePreservationMatcherTest {
    @Test
    fun exactMatching_tableDriven() {
        val terms = listOf(
            preserve("ドキドキ"),
            preserve("ギュッ", scope = "game.one"),
            preserve("ABC", sourceLang = "en", caseSensitive = true),
            preserve("disabled", sourceLang = "en", enabled = false),
            GlossaryTermEntity(
                sourceLang = "ja",
                targetLang = "zh-CN",
                sourceTerm = "普通",
                targetTerm = "正常",
                category = GlossaryTermCategory.TERM,
            ),
        )
        data class Case(
            val name: String,
            val source: String,
            val sourceLang: String,
            val packageName: String?,
            val expected: Boolean,
        )
        listOf(
            Case("exact", "ドキドキ", "ja", null, true),
            Case("edge punctuation", "「ドキドキ……」", "ja-JP", null, true),
            Case("whitespace", "ド キ　ド キ", "auto", null, true),
            Case("substring is rejected", "胸がドキドキする", "ja", null, false),
            Case("app scope matches", "ギュッ", "ja", "game.one", true),
            Case("app scope rejects other app", "ギュッ", "ja", "game.two", false),
            Case("case-sensitive exact", "ABC", "en", null, true),
            Case("case-sensitive mismatch", "abc", "en", null, false),
            Case("disabled", "disabled", "en", null, false),
            Case("normal glossary never preserves", "普通", "ja", null, false),
            Case("wrong language", "ドキドキ", "en", null, false),
        ).forEach { case ->
            val actual = SourcePreservationMatcher.preservedIndexes(
                sources = listOf(case.source),
                sourceLang = case.sourceLang,
                packageName = case.packageName,
                terms = terms,
            )
            assertEquals(case.name, case.expected, 0 in actual)
        }
    }

    @Test
    fun multipleBlocks_keepOriginalIndexesWithoutMerging() {
        assertEquals(
            setOf(0, 2),
            SourcePreservationMatcher.preservedIndexes(
                sources = listOf("ドキドキ", "普通の台詞", "「ドキドキ」"),
                sourceLang = "ja",
                packageName = null,
                terms = listOf(preserve("ドキドキ")),
            ),
        )
    }

    @Test
    fun masterGate_tableDriven_bypassesWithoutChangingEntryStates() {
        data class Case(
            val name: String,
            val preservationEnabled: Boolean,
            val expected: Set<Int>,
        )

        val terms = listOf(
            preserve("ドキドキ", enabled = true),
            preserve("ザーザー", enabled = false),
        )
        listOf(
            Case("default enabled", true, setOf(0)),
            Case("all disabled", false, emptySet()),
            Case("enabled again restores individual states", true, setOf(0)),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                SourcePreservationMatcher.preservedIndexes(
                    sources = listOf("ドキドキ", "ザーザー"),
                    sourceLang = "ja",
                    packageName = null,
                    terms = terms,
                    preservationEnabled = case.preservationEnabled,
                ),
            )
        }

        assertEquals("master gate never mutates entries", listOf(true, false), terms.map { it.enabled })
    }

    private fun preserve(
        source: String,
        scope: String = "",
        sourceLang: String = "ja",
        caseSensitive: Boolean = false,
        enabled: Boolean = true,
    ) = GlossaryTermEntity(
        scopePackage = scope,
        sourceLang = sourceLang,
        targetLang = "*",
        sourceTerm = source,
        targetTerm = source,
        category = GlossaryTermCategory.PRESERVE_SOURCE,
        caseSensitive = caseSensitive,
        enabled = enabled,
    )
}
