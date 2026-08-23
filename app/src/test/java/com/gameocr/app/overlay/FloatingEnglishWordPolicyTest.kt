package com.gameocr.app.overlay

import com.gameocr.app.translate.WordResult
import com.gameocr.app.translate.WordSense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun previewContent_tableDriven_keepsMeaningsAttachedToTheirPartOfSpeech() {
        data class Case(
            val name: String,
            val translation: String?,
            val wordResult: WordResult?,
            val loading: Boolean,
            val failed: Boolean,
            val expectedLines: List<String>,
        )

        listOf(
            Case("loading", null, null, true, false, listOf("翻译中…")),
            Case(
                "displayed keeps adjective and verb meanings separate",
                "显示的、表现、展示、陈列",
                WordResult(
                    lemma = "display",
                    senses = listOf(
                        WordSense("adj.", listOf("显示的")),
                        WordSense(
                            "v.",
                            listOf("表现", "展示", "陈列"),
                            "display 的过去式和过去分词",
                        ),
                    ),
                ),
                false,
                false,
                listOf(
                    "adj. 显示的",
                    "v. 表现（display 的过去式和过去分词）；展示；陈列",
                ),
            ),
            Case(
                "legacy single POS can be grouped safely",
                "更新、升级",
                WordResult(pos = listOf("v."), definitions = listOf("更新", "升级")),
                false,
                false,
                listOf("v. 更新；升级"),
            ),
            Case(
                "legacy multiple POS stays explicitly ungrouped",
                "记录、记下",
                WordResult(pos = listOf("n.", "v."), definitions = listOf("记录", "记下")),
                false,
                false,
                listOf("记录；记下", "词性：n. / v."),
            ),
            Case("failure", null, null, false, true, listOf("查询失败")),
            Case("plain translation without POS", "更新", null, false, false, listOf("更新")),
        ).forEach { case ->
            val actual = floatingWordPreviewContent(
                word = "update",
                translation = case.translation,
                wordResult = case.wordResult,
                loading = case.loading,
                failed = case.failed,
                loadingLabel = "翻译中…",
                failedLabel = "查询失败",
            )
            assertEquals(case.name, "update", actual.word)
            assertEquals(case.name, case.expectedLines, actual.lines)
        }
    }

    @Test
    fun detailsAction_tableDriven_isVisibleOnlyAfterSuccessfulLookup() {
        data class Case(
            val name: String,
            val loading: Boolean,
            val hasDetails: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("loading with no result", true, false, false),
            Case("loading with stale result", true, true, false),
            Case("failed lookup", false, false, false),
            Case("completed lookup", false, true, true),
        ).forEach { case ->
            val actual = shouldShowFloatingWordDetailsAction(case.loading, case.hasDetails)
            if (case.expected) assertTrue(case.name, actual) else assertFalse(case.name, actual)
        }
    }

    @Test
    fun fullDetailsContent_tableDriven_replacesCompactPreviewWithExplicitState() {
        val complete = WordResult(
            lemma = "display",
            senses = listOf(WordSense("v.", listOf("展示", "陈列"))),
            examples = listOf(com.gameocr.app.translate.ExamplePair("Display it.", "把它展示出来。")),
        )
        val empty = WordResult()

        data class Case(
            val name: String,
            val completed: Boolean,
            val result: WordResult?,
            val expectedTranslation: String?,
            val expectedResult: WordResult?,
            val expectedLoading: Boolean,
        )

        listOf(
            Case("fresh request clears compact content", false, complete, null, null, true),
            Case("complete result replaces loading", true, complete, null, complete, false),
            Case("null result becomes explicit failure", true, null, "查询失败", null, false),
            Case("empty result becomes explicit failure", true, empty, "查询失败", null, false),
        ).forEach { case ->
            val actual = floatingWordDetailsContent(
                completed = case.completed,
                wordResult = case.result,
                failedLabel = "查询失败",
            )
            assertEquals(case.name, case.expectedTranslation, actual.translation)
            assertEquals(case.name, case.expectedResult, actual.wordResult)
            assertEquals(case.name, case.expectedLoading, actual.loading)
        }
    }

    @Test
    fun translationSection_tableDriven_hidesDuplicateWordTranslation() {
        data class Case(
            val name: String,
            val translation: String?,
            val wordResult: WordResult?,
            val loading: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("pending lookup keeps loading state", null, null, true, true),
            Case(
                "structured word hides duplicate translation",
                "显示的、展示",
                WordResult(senses = listOf(WordSense("v.", listOf("展示")))),
                false,
                false,
            ),
            Case(
                "structured word hides empty translation row",
                null,
                WordResult(phonetic = "/dɪˈspleɪ/"),
                false,
                false,
            ),
            Case("sentence keeps translation", "这是一个完整句子。", null, false, true),
            Case(
                "fallback-only result remains a plain translation",
                "这是一个短语。",
                WordResult(fallbackTranslation = "这是一个短语。"),
                false,
                true,
            ),
            Case("failure message remains visible", "查询失败", null, false, true),
            Case("completed empty state has no empty heading", null, null, false, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldShowTranslationCardTranslationSection(
                    translation = case.translation,
                    wordResult = case.wordResult,
                    loading = case.loading,
                ),
            )
        }
    }

    @Test
    fun sectionDivider_tableDriven_neverDuplicatesAdjacentSeparators() {
        data class Case(
            val name: String,
            val hasSource: Boolean,
            val showTranslation: Boolean,
            val hasDictionary: Boolean,
            val expected: Int,
        )

        listOf(
            Case("sentence", true, true, false, 1),
            Case("dictionary word", true, false, true, 1),
            Case("defensive mixed state", true, true, true, 1),
            Case("source only", true, false, false, 0),
            Case("content without source", false, true, true, 0),
            Case("empty card", false, false, false, 0),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                translationCardSectionDividerCount(
                    hasSourceContent = case.hasSource,
                    showTranslationSection = case.showTranslation,
                    hasDictionaryContent = case.hasDictionary,
                ),
            )
        }
    }
}
