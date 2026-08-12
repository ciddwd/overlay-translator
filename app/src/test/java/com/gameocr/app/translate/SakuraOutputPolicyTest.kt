package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SakuraOutputPolicyTest {

    @Test
    fun validateGroup_tableDriven_coversStructuralAndTokenFailures() {
        data class Case(
            val name: String,
            val sources: List<String>,
            val output: String?,
            val hitLimit: Boolean,
            val expectedReason: SakuraOutputRejectionReason?,
            val expectedTexts: List<String?>?,
        )

        listOf(
            Case("valid single line", listOf("誰？"), "是谁？", false, null, listOf("是谁？")),
            Case("valid two lines", listOf("一", "二"), "第一\n第二", false, null, listOf("第一", "第二")),
            Case("missing line", listOf("一", "二"), "第一", false,
                SakuraOutputRejectionReason.LINE_COUNT_MISMATCH, null),
            Case("extra line", listOf("一", "二"), "第一\n第二\n第三", false,
                SakuraOutputRejectionReason.LINE_COUNT_MISMATCH, null),
            Case("empty output", listOf("一"), " ", false, SakuraOutputRejectionReason.EMPTY, null),
            Case("token limit", listOf("一"), "第一", true, SakuraOutputRejectionReason.TOKEN_LIMIT, null),
        ).forEach { case ->
            val actual = SakuraOutputPolicy.validateGroup(
                sources = case.sources,
                output = case.output,
                hitTokenLimit = case.hitLimit,
                forbiddenEchoes = listOf(SakuraPromptPolicy.BASIC_INSTRUCTION),
            )
            assertEquals(case.name, case.expectedReason, actual.rejectionReason)
            assertEquals(case.name, case.expectedTexts, actual.lines?.map { it.text })
        }
    }

    @Test
    fun validateLine_tableDriven_rejectsBadTranslationsWithoutContentSpecificRules() {
        data class Case(
            val name: String,
            val source: String,
            val output: String?,
            val expectedReason: SakuraOutputRejectionReason?,
            val expectedDisposition: SakuraOutputDisposition,
        )

        listOf(
            Case("valid Chinese", "よく頑張った", "你已经很努力了", null,
                SakuraOutputDisposition.ACCEPTED),
            Case("shared Han is valid", "学生です", "是学生", null,
                SakuraOutputDisposition.ACCEPTED),
            Case("Latin name is valid", "アリスだ", "是 Alice", null,
                SakuraOutputDisposition.ACCEPTED),
            Case("Chinese may retain a Japanese name", "彼女の名前", "她叫アリス", null,
                SakuraOutputDisposition.ACCEPTED),
            Case("Chinese may retain a Japanese title", "君の名は", "《君の名は》很好看", null,
                SakuraOutputDisposition.ACCEPTED),
            Case("empty", "短句", "", SakuraOutputRejectionReason.EMPTY,
                SakuraOutputDisposition.REJECTED),
            Case("single-line source rejects multiline output", "短句", "第一行\n第二行",
                SakuraOutputRejectionReason.MULTILINE, SakuraOutputDisposition.REJECTED),
            Case("multiline source accepts multiline output", "一行目\n二行目", "第一行\n第二行", null,
                SakuraOutputDisposition.ACCEPTED),
            Case("CRLF source accepts LF output", "一行目\r\n二行目", "第一行\n第二行", null,
                SakuraOutputDisposition.ACCEPTED),
            Case("multiline source accepts combined output", "一行目\n二行目", "合并后的译文", null,
                SakuraOutputDisposition.ACCEPTED),
            Case("repeated phrase degeneration", "短句", "书名".repeat(40),
                SakuraOutputRejectionReason.DEGENERATE_REPETITION, SakuraOutputDisposition.REJECTED),
            Case("observed single character degeneration", "えっかっ", "咦" + "啊".repeat(35),
                SakuraOutputRejectionReason.DEGENERATE_REPETITION, SakuraOutputDisposition.REJECTED),
            Case("periodic phrase degeneration", "短句", "好的".repeat(10),
                SakuraOutputRejectionReason.DEGENERATE_REPETITION, SakuraOutputDisposition.REJECTED),
            Case("source repetition is not a false positive", "は".repeat(4), "哈".repeat(12), null,
                SakuraOutputDisposition.ACCEPTED),
            Case("high diversity runaway remains too long", "短句",
                "abcdefghijklmnopqrstuvwxyz0123456789", SakuraOutputRejectionReason.TOO_LONG,
                SakuraOutputDisposition.REJECTED),
            Case("exact prompt echo", "短句", SakuraPromptPolicy.BASIC_INSTRUCTION,
                SakuraOutputRejectionReason.PROMPT_ECHO, SakuraOutputDisposition.REJECTED),
            Case("paraphrased prompt echo", "短句", "将下面的句子翻译成中文，不使用图片",
                SakuraOutputRejectionReason.PROMPT_ECHO, SakuraOutputDisposition.REJECTED),
            Case("exact Japanese source copy", "よく頑張った", "よく頑張った",
                SakuraOutputRejectionReason.SOURCE_COPY, SakuraOutputDisposition.RETRYABLE),
            Case("short Japanese source copy", "あいうえ", "あいうえ",
                SakuraOutputRejectionReason.SOURCE_COPY, SakuraOutputDisposition.RETRYABLE),
            Case("simplified Han with kana remains Japanese", "親も俺なんかに預けて", "亲も俺なんかに预けて",
                SakuraOutputRejectionReason.JAPANESE_RESIDUE, SakuraOutputDisposition.RETRYABLE),
        ).forEach { case ->
            val actual = SakuraOutputPolicy.validateLineDetailed(
                source = case.source,
                output = case.output,
                forbiddenEchoes = listOf(
                    SakuraPromptPolicy.BASIC_INSTRUCTION,
                    SakuraPromptPolicy.GLOSSARY_INSTRUCTION,
                ),
            )
            assertEquals(case.name, case.expectedReason, actual.rejectionReason)
            assertEquals(case.name, case.expectedDisposition, actual.disposition)
            when (case.expectedDisposition) {
                SakuraOutputDisposition.ACCEPTED,
                SakuraOutputDisposition.RETRYABLE -> assertEquals(case.name, case.output, actual.text)
                SakuraOutputDisposition.REJECTED -> assertNull(case.name, actual.text)
            }
        }
    }

    @Test
    fun validateGroup_preservesGoodLinesAndMarksOnlyBadLineForRetry() {
        val actual = SakuraOutputPolicy.validateGroup(
            sources = listOf("一行目", "親も俺なんかに預けて", "三行目"),
            output = "第一行\n亲も俺なんかに预けて\n第三行",
            hitTokenLimit = false,
            forbiddenEchoes = listOf(SakuraPromptPolicy.BASIC_INSTRUCTION),
        )

        assertNull(actual.rejectionReason)
        assertFalse(actual.accepted)
        assertTrue(actual.lines?.get(0)?.accepted == true)
        assertEquals(SakuraOutputRejectionReason.JAPANESE_RESIDUE, actual.lines?.get(1)?.rejectionReason)
        assertTrue(actual.lines?.get(1)?.retryable == true)
        assertEquals("亲も俺なんかに预けて", actual.lines?.get(1)?.text)
        assertTrue(actual.lines?.get(2)?.accepted == true)
    }
}
