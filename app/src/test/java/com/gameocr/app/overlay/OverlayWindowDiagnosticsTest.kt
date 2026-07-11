package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayWindowDiagnosticsTest {

    @Test
    fun cutoutModeLogLabel_handlesSupportedAndUnsupportedValues() {
        data class Case(
            val name: String,
            val mode: Int?,
            val expected: String,
        )

        listOf(
            Case("unsupported before API 28", null, "unsupported"),
            Case("default mode", 0, "0"),
            Case("always mode", 3, "3"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, cutoutModeLogLabel(case.mode))
        }
    }
}
