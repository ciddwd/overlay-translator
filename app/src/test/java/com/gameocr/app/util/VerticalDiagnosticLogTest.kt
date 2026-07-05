package com.gameocr.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalDiagnosticLogTest {

    @Test
    fun text_escapes_multiline_content_for_single_log_entries() {
        data class Case(
            val name: String,
            val raw: String?,
            val expected: String
        )

        val cases = listOf(
            Case("null-becomes-empty", null, ""),
            Case("lf", "A\nB", "A\\nB"),
            Case("crlf", "A\r\nB", "A\\r\\nB"),
            Case("keeps-normal-text", "traditional vertical text", "traditional vertical text")
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, VerticalDiagnosticLog.text(case.raw))
        }
    }

    @Test
    fun chunks_split_long_messages_without_losing_content() {
        data class Case(
            val name: String,
            val message: String,
            val maxChunkChars: Int,
            val expectedChunks: List<String>
        )

        val cases = listOf(
            Case("empty", "", 3, listOf("")),
            Case("exact", "abc", 3, listOf("abc")),
            Case("one-over", "abcd", 3, listOf("abc", "d")),
            Case("multiple", "abcdefgh", 3, listOf("abc", "def", "gh"))
        )

        cases.forEach { case ->
            val chunks = VerticalDiagnosticLog.chunks(case.message, case.maxChunkChars)
            assertEquals(case.name, case.expectedChunks, chunks)
            assertEquals(case.name + "-joined", case.message, chunks.joinToString(""))
        }
    }
}
