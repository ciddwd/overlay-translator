package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LogDurationFormatterTest {

    @Test
    fun formatLogElapsedMs_tableDriven() {
        data class Case(val elapsedMs: Long, val expected: String)

        val cases = listOf(
            Case(-1L, "0ms"),
            Case(0L, "0ms"),
            Case(999L, "999ms"),
            Case(1_000L, "1.0s"),
            Case(1_250L, "1.3s"),
            Case(9_949L, "9.9s"),
            Case(9_950L, "10.0s"),
            Case(10_000L, "10s"),
            Case(65_499L, "65s"),
            Case(65_500L, "66s")
        )

        cases.forEach { case ->
            assertEquals(case.expected, formatLogElapsedMs(case.elapsedMs))
        }
    }
}
