package com.gameocr.app.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SourceTtsLanguagePolicyTest {
    @Test
    fun resolve_tableDriven_actualTextOverridesConfiguredSourceAndReusesOnlyExactEvidence() = runBlocking {
        data class Case(val name: String, val text: String, val evidence: List<TtsLanguageEvidence>,
            val detected: String?, val fallback: String, val expected: String, val calls: Int)
        val chinese = "妳不要隨便\n亂跑好不好，"
        val cases = listOf(
            Case("Chinese despite Japanese preset", chinese, emptyList(), "zh", "ja", "zh", 1),
            Case("Paddle auto is not a language", chinese, listOf(TtsLanguageEvidence(chinese, "auto")), "zh", "ja", "zh", 1),
            Case("reuse actual Chinese", chinese, listOf(TtsLanguageEvidence(chinese, "zh")), null, "ja", "zh", 0),
            Case("same language across blocks", chinese, listOf(TtsLanguageEvidence("妳不要隨便", "zh"), TtsLanguageEvidence("亂跑好不好，", "zh")), null, "ja", "zh", 0),
            Case("one complete block of mixed page", "Good morning", listOf(TtsLanguageEvidence("今日は晴れです", "ja"), TtsLanguageEvidence("Good morning", "en")), null, "ja", "en", 0),
            Case("partial selection detects separately", "Good morning", listOf(TtsLanguageEvidence("Good morning 今日は晴れです", "ja")), "en", "ja", "en", 1),
            Case("edited text does not reuse", "Good evening", listOf(TtsLanguageEvidence("Good morning", "en")), "en", "ja", "en", 1),
            Case("mixed selection detects together", "Hello こんにちは", listOf(TtsLanguageEvidence("Hello", "en"), TtsLanguageEvidence("こんにちは", "ja")), "ja", "en", "ja", 1),
            Case("null tag detects", chinese, listOf(TtsLanguageEvidence(chinese, null)), "zh", "ja", "zh", 1),
            Case("und tag detects", chinese, listOf(TtsLanguageEvidence(chinese, "und")), "zh", "ja", "zh", 1),
            Case("uncertain keeps fallback", "成功", emptyList(), null, "ja", "ja", 1),
            Case("und detector result keeps fallback", "成功", emptyList(), "und", "ja", "ja", 1),
            Case("no language is not invented", "12345", emptyList(), null, "auto", "auto", 1),
            Case("normalizes existing tag", chinese, listOf(TtsLanguageEvidence(chinese, "zh_TW")), null, "ja", "zh-TW", 0),
        )
        for (case in cases) {
            var calls = 0
            val result = SourceTtsLanguagePolicy.resolve(case.text, case.evidence, case.fallback) {
                calls++
                assertEquals(case.name, case.text, it)
                case.detected
            }
            assertEquals(case.name, case.expected, result)
            assertEquals("${case.name}: identifier calls", case.calls, calls)
        }
    }

    @Test
    fun cancellationDoesNotTurnIntoFallbackSpeech() = runBlocking {
        try {
            SourceTtsLanguagePolicy.resolve("こんにちは", emptyList(), "en") { throw CancellationException("closed") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
