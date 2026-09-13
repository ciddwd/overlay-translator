package com.gameocr.app.dictionary

import com.gameocr.app.data.TranslatorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryPackPolicyTest {
    @Test
    fun languageRouting_tableDriven_selectsOnlySupportedOfflinePack() {
        data class Case(val language: String, val expected: DictionaryPackId?)
        listOf(
            Case("en", DictionaryPackId.ECDICT),
            Case("en-US", DictionaryPackId.ECDICT),
            Case("ja", DictionaryPackId.JMDICT),
            Case("ja-JP", DictionaryPackId.JMDICT),
            Case("ko", DictionaryPackId.KOREAN_BASIC),
            Case("ko-KR", DictionaryPackId.KOREAN_BASIC),
            Case("zh-CN", null),
            Case("auto", null),
            Case("", null),
        ).forEach { case ->
            assertEquals(case.language, case.expected, DictionaryPackCatalog.forLanguage(case.language)?.id)
        }
    }

    @Test
    fun onlineAvailability_tableDriven_allowsOnlyCloudLlmProtocols() {
        TranslatorEngine.entries.forEach { engine ->
            val expected = engine == TranslatorEngine.OPENAI || engine == TranslatorEngine.ANTHROPIC
            assertEquals(engine.name, expected, supportsOnlineDictionaryLookup(engine))
        }
    }

    @Test
    fun headwordNormalization_tableDriven_isStableWithoutLanguageSpecificRewriting() {
        mapOf(
            "  DISPLAYED " to "displayed",
            "Ｄｉｓｐｌａｙ" to "display",
            "食べる" to "食べる",
            "한국어" to "한국어",
        ).forEach { (input, expected) ->
            assertEquals(input, expected, normalizeDictionaryHeadword(input))
        }
    }

    @Test
    fun packageCatalog_hasStableDistinctIdsAndFiles() {
        assertEquals(3, DictionaryPackCatalog.all.size)
        assertEquals(3, DictionaryPackCatalog.all.map { it.id.wireId }.distinct().size)
        assertEquals(3, DictionaryPackCatalog.all.map { it.id.fileName }.distinct().size)
        assertTrue(DictionaryPackCatalog.all.all { it.licenseName.isNotBlank() })
        assertFalse(DictionaryPackCatalog.all.any { it.languageCode.isBlank() })
        assertNull(DictionaryPackCatalog.forLanguage("fr"))
        assertEquals("English (ECDICT)", DictionaryPackCatalog.forLanguage("en")?.displayName)
    }
}
