package com.gameocr.app.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextPolicyTest {
    @Test fun cleanRemovesModelTagsAndNormalizesSpacing_tableDriven() {
        listOf(
            "<|zh|><|NEUTRAL|><|Speech|><|withitn|>你好。" to "你好。",
            "  hello   world \n" to "hello world",
            "<|en|>" to "",
            "" to "",
            "a <|BGM|> b" to "a b",
            "不是标签 <zh> 保留" to "不是标签 <zh> 保留",
        ).forEach { (raw, expected) -> assertEquals(raw, expected, SpeechTextPolicy.clean(raw)) }
    }

    @Test fun hallucinationFilterKeepsSpeechAndDropsInventions_tableDriven() {
        data class Case(val text: String, val seconds: Float, val expected: Boolean)
        listOf(
            Case("I think we should go now.", 2f, false),
            Case("你好，今天天气不错。", 2f, false),
            Case("Go, go.", 1f, false),
            Case("", 1f, true),
            Case("   ", 1f, true),
            Case("Thank you. Thank you. Thank you. Thank you.", 6f, true),
            Case("you you you you", 2f, true),
            Case("ああああああ", 2f, true),
            // Far denser than speech for the audio duration.
            Case("This is a very long sentence that could never be spoken in half a second.", 0.5f, true),
            Case("ok", 0f, false),
        ).forEach { case ->
            assertEquals("'${case.text}' ${case.seconds}s", case.expected,
                SpeechTextPolicy.isLikelyHallucination(case.text, case.seconds))
        }
    }
}
