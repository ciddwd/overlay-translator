package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class SakuraContextBatchPolicyTest {
    @Test
    fun plan_preservesOneRegionPerLine_tableDriven() {
        data class Case(
            val name: String,
            val sources: List<String>,
            val expected: String?,
        )

        listOf(
            Case("two regions", listOf("first", "second"), "first\nsecond"),
            Case("internal CRLF is flattened", listOf("first\r\npart", "second"), "first part\nsecond"),
            Case("internal CR is flattened", listOf("first\rpart", "second"), "first part\nsecond"),
            Case("single region uses normal translation", listOf("first"), null),
            Case("blank region is unsafe", listOf("first", "  "), null),
        ).forEach { case ->
            val plan = SakuraContextBatchPolicy.plan(case.sources)
            if (case.expected == null) {
                assertNull(case.name, plan)
            } else {
                assertNotNull(case.name, plan)
                assertEquals(case.name, case.expected, plan?.joinedSource)
                assertEquals(case.sources.size, plan?.sourceLines?.size)
            }
        }
    }

    @Test
    fun parse_requiresExactNonBlankLineCount_tableDriven() {
        data class Case(
            val name: String,
            val output: String?,
            val expected: List<String>?,
        )

        listOf(
            Case("exact LF", "one\ntwo", listOf("one", "two")),
            Case("CRLF", "one\r\ntwo", listOf("one", "two")),
            Case("outer whitespace", "  one  \n  two  \n", listOf("one", "two")),
            Case("missing line", "one", null),
            Case("extra line", "one\ntwo\nthree", null),
            Case("blank middle line", "one\n\nthree", null),
            Case("null output", null, null),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                SakuraContextBatchPolicy.parse(case.output, expectedCount = 2),
            )
        }
    }
}
