package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FloatingEnglishWordPolicyTest {

    @Test
    fun wordHit_tableDriven_handlesEnglishWordsAndJoiners() {
        data class Case(
            val name: String,
            val text: String,
            val tapped: Char,
            val expected: String,
        )

        listOf(
            Case("plain word", "Tap update now", 'd', "update"),
            Case("surrounded by punctuation", "(update), please", 'u', "update"),
            Case("uppercase", "HTTPS request", 'T', "HTTPS"),
            Case("apostrophe", "don't stop", 'n', "don't"),
            Case("curly apostrophe", "it’s ready", 's', "it’s"),
            Case("hyphenated word", "state-of-the-art", 'o', "state-of-the-art"),
            Case("nonbreaking hyphen", "real‑time", 't', "real‑time"),
        ).forEach { case ->
            val offset = case.text.indexOf(case.tapped)
            val hit = floatingEnglishWordAt(case.text, offset)
            assertEquals(case.name, case.expected, hit?.word)
            assertEquals(case.name, case.expected, case.text.substring(hit!!.start, hit.end))
        }
    }

    @Test
    fun wordHit_tableDriven_rejectsNonEnglishTapTargets() {
        data class Case(val name: String, val text: String, val offset: Int)

        listOf(
            Case("space does not snap to neighbor", "hello world", 5),
            Case("punctuation does not snap to neighbor", "hello, world", 5),
            Case("number", "version 2", 8),
            Case("CJK", "更新 update", 0),
            Case("emoji", "🙂 update", 0),
            Case("negative offset", "update", -1),
            Case("past end", "update", 6),
            Case("empty", "", 0),
        ).forEach { case ->
            assertNull(case.name, floatingEnglishWordAt(case.text, case.offset))
        }
    }

    @Test
    fun previewContent_tableDriven_keepsExactlyThreeDisplayFields() {
        data class Case(
            val name: String,
            val translation: String?,
            val partsOfSpeech: List<String>,
            val loading: Boolean,
            val failed: Boolean,
            val expectedTranslation: String,
            val expectedPartOfSpeech: String,
        )

        listOf(
            Case("loading", null, emptyList(), true, false, "翻译中…", ""),
            Case("success trims and deduplicates", " 更新、升级 ", listOf("v.", "v.", " n. "), false, false, "更新、升级", "v. / n."),
            Case("failure", null, emptyList(), false, true, "查询失败", ""),
            Case("plain translation without POS", "更新", emptyList(), false, false, "更新", ""),
        ).forEach { case ->
            val actual = floatingWordPreviewContent(
                word = "update",
                translation = case.translation,
                partsOfSpeech = case.partsOfSpeech,
                loading = case.loading,
                failed = case.failed,
                loadingLabel = "翻译中…",
                failedLabel = "查询失败",
            )
            assertEquals(case.name, "update", actual.word)
            assertEquals(case.name, case.expectedTranslation, actual.translation)
            assertEquals(case.name, case.expectedPartOfSpeech, actual.partOfSpeech)
        }
    }
}
