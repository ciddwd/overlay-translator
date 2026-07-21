package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionSpeechPolicyTest {

    @Test
    fun selectedTextForSpeech_tableDriven_normalizesValidSelections() {
        data class Case(
            val name: String,
            val text: String,
            val start: Int,
            val end: Int,
            val expected: String?,
        )

        listOf(
            Case("forward selection", "source translation", 0, 6, "source"),
            Case("reverse selection", "source translation", 6, 0, "source"),
            Case("trims selected whitespace", "  spoken text  ", 0, 15, "spoken text"),
            Case("clamps end to text length", "speech", 2, 99, "eech"),
            Case("negative start", "speech", -1, 3, null),
            Case("negative end", "speech", 1, -1, null),
            Case("collapsed selection", "speech", 2, 2, null),
            Case("selection starts beyond text", "speech", 99, 100, null),
            Case("blank selection", "speech   text", 6, 9, null),
            Case("empty text", "", 0, 1, null),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                selectedTextForSpeech(case.text, case.start, case.end),
            )
        }
    }
}
