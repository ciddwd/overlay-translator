package com.gameocr.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapCaptureDiagnosticsTest {

    @Test
    fun classifyBitmapFrameSamples_tableDriven_detectsProjectionBlankFrames() {
        data class Case(
            val name: String,
            val samples: List<Int>,
            val expectedBlankLike: Boolean,
            val expectedTransparentCount: Int,
            val expectedDarkCount: Int
        )

        val black = argb(255, 0, 0, 0)
        val white = argb(255, 255, 255, 255)
        val red = argb(255, 220, 20, 20)
        val green = argb(255, 20, 180, 60)
        val blue = argb(255, 20, 80, 220)

        val cases = listOf(
            Case(
                name = "all transparent surface",
                samples = List(100) { argb(0, 0, 0, 0) },
                expectedBlankLike = true,
                expectedTransparentCount = 100,
                expectedDarkCount = 0
            ),
            Case(
                name = "all black protected frame",
                samples = List(100) { black },
                expectedBlankLike = true,
                expectedTransparentCount = 0,
                expectedDarkCount = 100
            ),
            Case(
                name = "threshold includes 98 percent black",
                samples = List(98) { black } + List(2) { white },
                expectedBlankLike = true,
                expectedTransparentCount = 0,
                expectedDarkCount = 98
            ),
            Case(
                name = "black border with visible content is not blank",
                samples = List(90) { black } + List(10) { white },
                expectedBlankLike = false,
                expectedTransparentCount = 0,
                expectedDarkCount = 90
            ),
            Case(
                name = "varied gallery-like colors are not blank",
                samples = List(40) { index ->
                    when (index % 4) {
                        0 -> white
                        1 -> red
                        2 -> green
                        else -> blue
                    }
                },
                expectedBlankLike = false,
                expectedTransparentCount = 0,
                expectedDarkCount = 0
            )
        )

        cases.forEach { case ->
            val actual = classifyBitmapFrameSamples(case.samples)
            assertEquals(case.name, case.expectedBlankLike, actual.blankLike)
            assertEquals(case.name, case.expectedTransparentCount, actual.transparentCount)
            assertEquals(case.name, case.expectedDarkCount, actual.darkCount)
            assertEquals(case.name, case.samples.size, actual.sampleCount)
        }
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}
