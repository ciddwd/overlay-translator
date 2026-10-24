package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationGlyphSizingTest {
    @Test fun constrainedTextCanFillItsAreaOrShrinkToFitWhileOrdinaryLabelsKeepTheirRange_tableDriven() {
        for (height in listOf(.2f, 12.3f, 28f, 52f, 96.5f)) {
            val range = requireNotNull(translationGlyphSizeRange(height,100f))
            val bounded=requireNotNull(translationGlyphSizeRange(height,100f,constrainToBounds=true))
            assertTrue(bounded.targetPx>=range.targetPx)
            assertTrue(bounded.minPx<=range.minPx)
            assertTrue(range.minPx >= range.targetPx * .85f)
        }
    }

    @Test fun boundedLabelsCanFitTwoChineseGlyphsIntoTheOriginalOkLabelWidth() {
        val range=requireNotNull(translationGlyphSizeRange(35f,100f,constrainToBounds=true))
        val fit=adaptiveLargestFittingTextSizePx(range.minPx,range.targetPx) { it*2<=43 }
        assertTrue(fit.fits)
        assertEquals(21,fit.sizePx)
        val old=requireNotNull(translationGlyphSizeRange(35f,100f))
        assertTrue(old.minPx*2>43)
    }

    @Test fun candidatesAreCheckedFrom100PercentDownTo85WithoutSkippingWrapDiscontinuities() {
        for (fitting in listOf(setOf(100),setOf(96,89),setOf(85),emptySet())) {
            val visited = mutableListOf<Int>()
            val result = adaptiveLargestFittingTextSizePx(85,100) { candidate ->
                visited += candidate
                candidate in fitting
            }
            val expected = fitting.maxOrNull() ?: 85
            assertEquals(expected,result.sizePx)
            assertEquals((100 downTo expected).toList(),visited)
        }
    }
    @Test fun verticalGlyphEstimateUsesTextLengthAndConfiguredCeiling_tableDriven() {
        data class Case(val widths: List<Int>, val lengths: List<Int>, val chars: Int, val cap: Float, val expected: Float)
        for(scale in listOf(1,2,3)) for(case in listOf(
            Case(listOf(90,80,100),listOf(210,210,210),30,100f,20f),
            Case(listOf(90,80,100),listOf(210,210,210),0,28f,28f),
            Case(listOf(20,30),listOf(500,500),4,100f,25f),
            Case(listOf(-1,0,24),listOf(0,-20),30,100f,24f),
            Case(emptyList(),emptyList(),0,28f,28f),
        )) {
            val value=translationSourceGlyphHeight(case.widths.map { it*scale },40*scale,
                case.chars,case.lengths.map { it*scale },case.cap*scale)
            assertEquals(case.toString(),case.expected*scale,value,0.01f)
        }
    }

    @Test fun avoidExtraWrappingBeforeGrowingTheViewport_tableDriven() {
        data class Case(val budget: Int, val fitUpTo: Int, val expected: Int)
        val range = TranslationGlyphSizeRange(85, 100)
        listOf(
            Case(1, 100, 100), // 100% already preserves the source line count.
            Case(1, 92, 92), // Shrink only enough to avoid an unnecessary second line.
            Case(1, 85, 85),
            Case(1, 70, 85), // Longer translations may wrap, but never go below the floor.
            Case(2, 94, 94),
            Case(0, 90, 90),
        ).forEach { case ->
            val budget = case.budget.coerceAtLeast(1)
            assertEquals(case.toString(), case.expected,
                translationWrapLimitedMaxFontPx(range, case.budget) { if (it <= case.fitUpTo) budget else budget + 1 })
        }
    }
    @Test fun viewportReservesTargetHeightBeforeTheFontIsAllowedToShrink_tableDriven() {
        data class Case(val top: Int, val original: Int, val target: Int, val screen: Int, val bottom: Int, val upper: Int, val gap: Int, val expected: TranslationViewport)
        listOf(
            Case(100, 52, 66, 3200, 3200, 0, 0, TranslationViewport(100, 66)),
            Case(3165, 37, 45, 3200, 3200, 0, 28, TranslationViewport(3155, 45)),
            Case(3170, 30, 37, 3200, 3200, 0, 28, TranslationViewport(3163, 37)),
            Case(150, 20, 60, 3200, 180, 100, 8, TranslationViewport(112, 60)),
            Case(150, 20, 80, 3200, 180, 132, 8, TranslationViewport(132, 40)),
            Case(1411, 48, 52, 3200, 1466, 0, 28, TranslationViewport(1407, 52)),
            Case(1411, 48, 52, 3200, 1466, 1422, 28, TranslationViewport(1411, 48)),
            Case(-10, 20, 25, 100, 100, 0, 0, TranslationViewport(0, 25)),
            Case(0, 0, 0, 0, 0, 0, 0, TranslationViewport(0, 1)),
        ).forEach { case ->
            val viewport = translationTargetViewport(case.top, case.original, case.target, case.screen, case.bottom, case.upper, case.gap)
            assertEquals(case.toString(), case.expected, viewport)
            assertTrue(viewport.topPx >= 0)
            assertTrue(viewport.topPx + viewport.heightPx <= case.screen.coerceAtLeast(1))
        }
    }
    @Test fun sourceLineHeightUsesAMedianAndFallsBackWithoutChangingInput() {
        val heights = listOf(52, 40, 44, 0, -1)
        assertEquals(44f, translationSourceGlyphHeight(heights, 200), 0f)
        assertEquals(listOf(52, 40, 44, 0, -1), heights)
        assertEquals(46f, translationSourceGlyphHeight(listOf(40, 52), 200), 0f)
        assertEquals(52f, translationSourceGlyphHeight(emptyList(), 52), 0f)
        assertEquals(1f, translationSourceGlyphHeight(listOf(0, -1), 0), 0f)
    }
    @Test fun targetAndMinimumUseVisibleInkRatherThanFontLineHeight_tableDriven() {
        data class Case(val source: Float, val inkAt100: Float, val minimum: Int, val target: Int)
        listOf(
            Case(52f, 80f, 45, 52),
            Case(39f, 75f, 34, 39),
            Case(32f, 100f, 28, 32),
            Case(52f, 90f, 45, 52),
            Case(0.2f, 100f, 1, 1),
        ).forEach { case ->
            val range = checkNotNull(translationGlyphSizeRange(case.source, case.inkAt100))
            assertEquals(case.toString(), case.minimum, range.minPx)
            assertEquals(case.toString(), case.target, range.targetPx)
            assertTrue(range.minPx >= range.targetPx * 0.85f)
            assertTrue(range.targetPx <= kotlin.math.ceil(case.source).toInt())
        }
    }

    @Test fun invalidMeasurementsDoNotInstallAnInvalidAutoSizeRange_tableDriven() {
        listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { invalid ->
            assertNull(translationGlyphSizeRange(invalid, 90f))
            assertNull(translationGlyphSizeRange(52f, invalid))
            assertNull(translationGlyphSizeRange(52f, 90f, invalid))
        }
        assertNull(translationGlyphSizeRange(Float.MAX_VALUE, 1f))
    }

    @Test fun fitStopsAt85PercentEvenWhenTheOriginalRectangleIsTooSmall() {
        val range = checkNotNull(translationGlyphSizeRange(52f, 80f))
        val cases = listOf(80 to 52, 50 to 50, 45 to 45, 20 to 45)
        cases.forEach { (availableFontSizePx, expected) ->
            val result = adaptiveLargestFittingTextSizePx(range.minPx, range.targetPx) {
                it <= availableFontSizePx
            }
            assertEquals(expected, result.sizePx)
            assertTrue(result.sizePx >= range.minPx)
        }
    }

    @Test fun shortFontInkNeverMagnifiesTheSourceLineHeight() {
        val sourceHeight = 60f
        listOf(60f, 75f, 90f, 110f).forEach { referenceInk ->
            val range = checkNotNull(translationGlyphSizeRange(sourceHeight, referenceInk))
            val visibleHeight = range.targetPx * referenceInk / 100f
            assertTrue(range.targetPx <= sourceHeight)
            assertTrue(visibleHeight <= sourceHeight + referenceInk / 100f + 0.01f)
        }
    }
}
