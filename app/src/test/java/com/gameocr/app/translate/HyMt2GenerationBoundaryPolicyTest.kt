package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class HyMt2GenerationBoundaryPolicyTest {

    @Test
    fun maxOutputLines_tableDriven_limitsOnlyContextRequestsWithOneLineAllowance() {
        data class Case(
            val name: String,
            val source: String,
            val requestHadBackground: Boolean,
            val expected: Int,
        )

        listOf(
            Case("quick translation stays unlimited", "一行", false, 0),
            Case("single source line allows two output lines", "一行", true, 2),
            Case("two source lines allow three output lines", "一行\n二行", true, 3),
            Case("CRLF is one line boundary", "一行\r\n二行", true, 3),
            Case("blank source line is preserved in the allowance", "一行\n\n二行", true, 4),
            Case("empty defensive input still has a safe limit", "", true, 2),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                HyMt2GenerationBoundaryPolicy.maxOutputLines(
                    source = case.source,
                    requestHadBackground = case.requestHadBackground,
                ),
            )
        }
    }
}
