package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class HorizontalRtlTextTest {
    @Test
    fun horizontalRtlDisplayText_keepsLogicalLineOrderForPerLineBidiLayout() {
        data class Case(
            val name: String,
            val input: String,
            val expected: String,
        )

        val rlo = "\u202E"
        val pdf = "\u202C"
        val cases = listOf(
            Case("empty", "", ""),
            Case("single character", "译", "${rlo}译$pdf"),
            Case("CJK punctuation stays logical", "你好，世界。", "${rlo}你好，世界。$pdf"),
            Case("paired punctuation stays logical", "(你好)", "${rlo}(你好)$pdf"),
            Case("LF keeps first paragraph first", "第一行\n第二行", "${rlo}第一行$pdf\n${rlo}第二行$pdf"),
            Case("CRLF keeps first paragraph first", "甲乙\r\n丙丁", "${rlo}甲乙$pdf\r\n${rlo}丙丁$pdf"),
            Case("blank line remains blank", "甲\n\n乙", "${rlo}甲$pdf\n\n${rlo}乙$pdf"),
            Case("surrogate and combining clusters stay logical", "A😀e\u0301中", "${rlo}A😀e\u0301中$pdf"),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, horizontalRtlDisplayText(case.input))
        }
    }
}
