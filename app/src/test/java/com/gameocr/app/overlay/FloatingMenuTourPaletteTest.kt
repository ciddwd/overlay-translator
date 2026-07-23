package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingMenuTourPaletteTest {
    @Test
    fun colors_useExpectedZincTokens_tableDriven() {
        data class Case(
            val name: String,
            val nightMode: Boolean,
            val expected: FloatingMenuTourColors,
        )

        val cases = listOf(
            Case(
                name = "light",
                nightMode = false,
                expected = FloatingMenuTourColors(
                    surface = 0xFFFAFAFA.toInt(),
                    text = 0xFF18181B.toInt(),
                    secondaryText = 0xFF52525B.toInt(),
                    accent = 0xFF3F3F46.toInt(),
                    actionText = 0xFFFFFFFF.toInt(),
                    border = 0xFFD4D4D8.toInt(),
                ),
            ),
            Case(
                name = "dark",
                nightMode = true,
                expected = FloatingMenuTourColors(
                    surface = 0xFF18181B.toInt(),
                    text = 0xFFFAFAFA.toInt(),
                    secondaryText = 0xFFA1A1AA.toInt(),
                    accent = 0xFFD4D4D8.toInt(),
                    actionText = 0xFF18181B.toInt(),
                    border = 0xFF52525B.toInt(),
                ),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                FloatingMenuTourPalette.colors(case.nightMode),
            )
        }
    }
}
