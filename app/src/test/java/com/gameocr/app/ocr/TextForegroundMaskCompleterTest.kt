package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextForegroundMaskCompleterTest {

    @Test
    fun complete_tableDriven_recoversConnectedAndDetachedWeakForegroundForBothPolarities() {
        data class Case(
            val name: String,
            val background: Int,
            val foreground: Int,
            val antialias: Int,
        )

        listOf(
            Case("dark ink on light bubble", gray(248), gray(8), gray(205)),
            Case("light ink on dark panel", gray(18), gray(245), gray(62)),
            Case("colored ink on pale bubble", rgb(224, 242, 254), rgb(30, 64, 175), rgb(125, 153, 211)),
        ).forEach { case ->
            val pixels = IntArray(SIZE) { case.background }
            val support = BooleanArray(SIZE).apply { fill(this, SUPPORT) }
            val strong = BooleanArray(SIZE).apply { fill(this, IntRect(12, 12, 16, 16)) }
            strong.indices.filter { strong[it] }.forEach { pixels[it] = case.foreground }
            fillColor(pixels, IntRect(16, 12, 18, 16), case.antialias)
            fillColor(pixels, IntRect(19, 13, 20, 15), case.foreground)

            val result = TextForegroundMaskCompleter.complete(
                width = WIDTH,
                height = HEIGHT,
                argb = pixels,
                strongMask = strong,
                supportMask = support,
                backgroundSamples = IntArray(64) { case.background },
            )

            assertTrue("${case.name}: antialias joins the strong glyph", result.mask[index(17, 14)])
            assertTrue("${case.name}: nearby detached corner is recovered", result.mask[index(19, 14)])
            assertTrue("${case.name}: completion reports added coverage", result.addedPixels > 0)
        }
    }

    @Test
    fun complete_tableDriven_respectsGeometryAndRejectsUnrelatedComponents() {
        data class Case(
            val name: String,
            val drawCandidate: (IntArray) -> Unit,
            val probeX: Int,
            val probeY: Int,
        )
        val background = gray(250)
        val foreground = gray(5)

        listOf(
            Case(
                name = "distant artwork inside OCR geometry",
                drawCandidate = { pixels -> fillColor(pixels, IntRect(5, 8, 7, 24), foreground) },
                probeX = 6,
                probeY = 16,
            ),
            Case(
                name = "nearby panel line is too long and thin",
                drawCandidate = { pixels -> fillColor(pixels, IntRect(19, 4, 20, 28), foreground) },
                probeX = 19,
                probeY = 16,
            ),
            Case(
                name = "foreground outside OCR support",
                drawCandidate = { pixels -> fillColor(pixels, IntRect(29, 13, 31, 16), foreground) },
                probeX = 30,
                probeY = 14,
            ),
        ).forEach { case ->
            val pixels = IntArray(SIZE) { background }
            val support = BooleanArray(SIZE).apply { fill(this, SUPPORT) }
            val strong = BooleanArray(SIZE).apply { fill(this, IntRect(12, 12, 16, 16)) }
            strong.indices.filter { strong[it] }.forEach { pixels[it] = foreground }
            case.drawCandidate(pixels)

            val result = TextForegroundMaskCompleter.complete(
                width = WIDTH,
                height = HEIGHT,
                argb = pixels,
                strongMask = strong,
                supportMask = support,
                backgroundSamples = IntArray(64) { background },
            )

            assertFalse(case.name, result.mask[index(case.probeX, case.probeY)])
        }
    }

    @Test
    fun complete_tableDriven_isScaleAwareAndDoesNotInventForegroundWithoutReliableSeeds() {
        data class Case(val name: String, val scale: Int)

        listOf(Case("base resolution", 1), Case("double resolution", 2)).forEach { case ->
            val width = WIDTH * case.scale
            val height = HEIGHT * case.scale
            val pixels = IntArray(width * height) { gray(248) }
            val support = BooleanArray(width * height)
            fill(support, SUPPORT.scaled(case.scale), width)
            val strong = BooleanArray(width * height)
            fill(strong, IntRect(12, 12, 16, 16).scaled(case.scale), width)
            strong.indices.filter { strong[it] }.forEach { pixels[it] = gray(8) }
            val detached = IntRect(19, 13, 20, 15).scaled(case.scale)
            fillColor(pixels, detached, gray(8), width)

            val result = TextForegroundMaskCompleter.complete(
                width = width,
                height = height,
                argb = pixels,
                strongMask = strong,
                supportMask = support,
                backgroundSamples = IntArray(64) { gray(248) },
            )

            assertTrue(case.name, result.mask[detached.top * width + detached.left])
        }

        val noStrong = TextForegroundMaskCompleter.complete(
            width = WIDTH,
            height = HEIGHT,
            argb = IntArray(SIZE) { gray(248) },
            strongMask = BooleanArray(SIZE),
            supportMask = BooleanArray(SIZE).apply { fill(this, SUPPORT) },
            backgroundSamples = IntArray(64) { gray(248) },
        )
        assertEquals("no detector seed means no speculative erase", 0, noStrong.addedPixels)
        assertTrue(noStrong.mask.none { it })
    }

    private fun IntRect.scaled(scale: Int) = IntRect(
        left * scale,
        top * scale,
        right * scale,
        bottom * scale,
    )

    private fun fill(mask: BooleanArray, bounds: IntRect, width: Int = WIDTH) {
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) mask[y * width + x] = true
        }
    }

    private fun fillColor(
        pixels: IntArray,
        bounds: IntRect,
        color: Int,
        width: Int = WIDTH,
    ) {
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) pixels[y * width + x] = color
        }
    }

    private fun index(x: Int, y: Int): Int = y * WIDTH + x

    private fun gray(value: Int): Int = rgb(value, value, value)

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    private companion object {
        const val WIDTH = 32
        const val HEIGHT = 32
        const val SIZE = WIDTH * HEIGHT
        val SUPPORT = IntRect(4, 4, 28, 28)
    }
}
