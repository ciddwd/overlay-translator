package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedTranslationLayoutTest {
    @Test fun mangaFallbackUsesTheLargestFontInsideItsOwnBounds_tableDriven() {
        data class Case(val text: String, val width: Int, val height: Int, val source: Float, val expected: Int, val fits: Boolean)
        // Geometry and translations from the overflowing page, plus short/empty/tiny slots.
        val cases = listOf(
            Case("前天在房间里半强迫地想做那件事，我得道歉才行……", 168, 273, 57f, 36, true),
            Case("我还不想做那种事……", 67, 150, 67f, 24, true),
            Case("真的对不起！！", 158, 270, 96.5f, 54, true),
            Case("短句", 200, 300, 52f, 84, true),
            Case("", 10, 10, 52f, 84, true),
            Case("字", 100, 6, 52f, 19, false),
        )
        cases.forEach { case ->
            val range = checkNotNull(translationGlyphSizeRange(case.source, 100f, constrainToBounds = true))
            fun extent(size: Int) = VerticalTextDrawer.measureColumns(
                case.text.codePoints().toArray(), size * 1.25f, size.toFloat(), case.height,
            )
            fun fits(size: Int) = extent(size).let { it.first <= case.width && it.second <= case.height }
            val fit = adaptiveLargestFittingTextSizePx(range.minPx, range.targetPx, ::fits)
            assertEquals(case.toString(), case.expected, fit.sizePx)
            assertEquals(case.toString(), case.fits, fit.fits)
            assertEquals(case.toString(), case.fits, fits(fit.sizePx))
            assertTrue(fit.sizePx >= range.minPx)
            assertTrue(fit.sizePx <= range.targetPx)
            if (fit.sizePx < range.targetPx) assertFalse(case.toString(), fits(fit.sizePx + 1))
        }
    }

    @Test fun fontAndViewportScalingDoNotDependOnOneDeviceSize_tableDriven() {
        listOf(0.5f, 1f, 1.5f, 2f).forEach { scale ->
            listOf(1.05f, 1.25f, 1.5f).forEach { fontHeightRatio ->
                val width = (168 * scale).toInt()
                val height = (273 * scale).toInt()
                val range = checkNotNull(translationGlyphSizeRange(57 * scale, 100f, constrainToBounds = true))
                val fit = adaptiveLargestFittingTextSizePx(range.minPx, range.targetPx) { size ->
                    VerticalTextDrawer.measureColumns(IntArray(24) { '字'.code }, size * fontHeightRatio,
                        size.toFloat(), height).let { it.first <= width && it.second <= height }
                }
                val measured = VerticalTextDrawer.measureColumns(IntArray(24) { '字'.code },
                    fit.sizePx * fontHeightRatio, fit.sizePx.toFloat(), height)
                assertTrue(fit.sizePx >= range.minPx)
                assertTrue(fit.fits)
                assertEquals("scale=$scale metrics=$fontHeightRatio: $measured", fit.fits,
                    measured.first <= width && measured.second <= height)
            }
        }
    }

    @Test fun boundedTextUsesTheAreaWhileOrdinaryTextKeepsTheSourceRange() {
        val ordinary = checkNotNull(translationGlyphSizeRange(52f, 80f))
        val bounded = checkNotNull(translationGlyphSizeRange(52f, 80f, constrainToBounds = true))
        assertTrue(bounded.targetPx>ordinary.targetPx)
        assertEquals(45, ordinary.minPx)
        assertTrue(bounded.minPx<ordinary.minPx)
    }

    @Test fun anImpossibleSlotStillReportsOverflowInsteadOfAFalseFit() {
        val fit = adaptiveLargestFittingTextSizePx(45, 52) { size ->
            VerticalTextDrawer.measureColumns(intArrayOf('字'.code), size * 1.25f,
                size.toFloat(), 1).let { it.first <= 1 && it.second <= 1 }
        }
        assertEquals(45, fit.sizePx)
        assertFalse(fit.fits)
        val required = VerticalTextDrawer.measureColumns(intArrayOf('字'.code),
            fit.sizePx * 1.25f, fit.sizePx.toFloat(), 1)
        assertTrue(required.first > 1 || required.second > 1)
    }

    @Test fun verticalMeasurementIncludesTheTallestColumnAndOversizeGlyphs_tableDriven() {
        data class Case(val text: String, val height: Int, val expected: Pair<Int, Int>)
        listOf(
            Case("", 20, 0 to 0),
            Case("甲乙", 20, 8 to 20),
            Case("甲乙丙", 20, 16 to 20),
            Case("甲乙\n丙", 0, 16 to 20),
            Case("甲乙\n丙", 15, 24 to 10),
            Case("甲乙", 5, 16 to 10), // Real height exceeds the slot; do not claim it fits.
            Case("甲\n", 20, 16 to 10),
        ).forEach { case ->
            assertEquals(case.toString(), case.expected,
                VerticalTextDrawer.measureColumns(case.text.codePoints().toArray(), 10f, 8f, case.height))
        }
    }
}
