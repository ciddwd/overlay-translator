package com.gameocr.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpResumePolicyTest {

    @Test
    fun `range header cases`() {
        val cases = listOf(
            0L to null,
            -1L to null,
            1L to "bytes=1-",
            8_388_608L to "bytes=8388608-",
        )

        cases.forEach { (existingBytes, expected) ->
            assertEquals("existingBytes=$existingBytes", expected, HttpResumePolicy.rangeHeader(existingBytes))
        }
    }

    @Test
    fun `response plan cases`() {
        data class Case(
            val existing: Long,
            val code: Int,
            val length: Long,
            val append: Boolean,
            val initial: Long,
            val total: Long,
        )

        val cases = listOf(
            Case(0, 200, 1_000, false, 0, 1_000),
            Case(400, 206, 600, true, 400, 1_000),
            Case(400, 200, 1_000, false, 0, 1_000),
            Case(0, 206, 1_000, false, 0, 1_000),
            Case(400, 206, -1, true, 400, -1),
            Case(400, 200, -1, false, 0, -1),
        )

        cases.forEach { case ->
            assertEquals(
                "existing=${case.existing} code=${case.code} length=${case.length}",
                HttpResumePlan(case.append, case.initial, case.total),
                HttpResumePolicy.responsePlan(case.existing, case.code, case.length),
            )
        }
    }
}
