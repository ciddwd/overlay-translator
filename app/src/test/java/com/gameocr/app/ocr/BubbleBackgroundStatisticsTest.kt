package com.gameocr.app.ocr

import kotlin.math.roundToInt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleBackgroundStatisticsTest {

    @Test
    fun estimate_tableDriven_matchesStableSortReference() {
        data class Case(val name: String, val colors: IntArray)

        val random = Random(42)
        val cases = listOf(
            Case("single color", IntArray(12) { rgb(240, 240, 240) }),
            Case(
                "luminance ties preserve input ordering",
                intArrayOf(
                    rgb(255, 0, 0), rgb(0, 130, 0), rgb(255, 0, 0), rgb(0, 130, 0),
                    rgb(20, 20, 20), rgb(250, 250, 250), rgb(30, 30, 30), rgb(240, 240, 240),
                    rgb(40, 40, 40), rgb(230, 230, 230), rgb(50, 50, 50), rgb(220, 220, 220),
                ),
            ),
            Case("odd sample count", IntArray(31) { randomColor(random) }),
            Case("even sample count", IntArray(32) { randomColor(random) }),
            Case("large deterministic sample", IntArray(4096) { randomColor(random) }),
        )

        cases.forEach { case ->
            val scratch = BubbleBackgroundStatistics.Scratch().apply {
                ensureSampleCapacity(case.colors.size)
                case.colors.copyInto(samples)
            }
            val actual = scratch.estimate(
                count = case.colors.size,
                brightSampleStartRatio = BRIGHT_RATIO,
                lowSampleRatio = LOW_RATIO,
            )
            assertEquals(case.name, reference(case.colors), actual)
        }
    }

    @Test
    fun estimate_reusedScratch_doesNotLeakPreviousHistogramCounts() {
        val scratch = BubbleBackgroundStatistics.Scratch()
        listOf(
            IntArray(128) { rgb(250, 250, 250) },
            IntArray(12) { rgb(8, 16, 24) },
            IntArray(257) { index -> rgb(index and 0xff, 64, 192) },
        ).forEachIndexed { index, colors ->
            scratch.ensureSampleCapacity(colors.size)
            colors.copyInto(scratch.samples)
            assertEquals(
                "reuse case $index",
                reference(colors),
                scratch.estimate(colors.size, BRIGHT_RATIO, LOW_RATIO),
            )
        }
    }

    private fun reference(colors: IntArray): BubbleBackgroundStatistics.Estimate {
        val sorted = colors.toList().sortedBy(::luminance)
        val brightestStart = (sorted.size * BRIGHT_RATIO).roundToInt().coerceIn(0, sorted.lastIndex)
        val brightest = sorted.subList(brightestStart, sorted.size)
        val reds = brightest.map { it ushr 16 and 0xff }.sorted()
        val greens = brightest.map { it ushr 8 and 0xff }.sorted()
        val blues = brightest.map { it and 0xff }.sorted()
        val red = reds[reds.size / 2]
        val green = greens[greens.size / 2]
        val blue = blues[blues.size / 2]
        return BubbleBackgroundStatistics.Estimate(
            red = red,
            green = green,
            blue = blue,
            luminance = luminance(red, green, blue),
            lowLuminance = luminance(sorted[((sorted.lastIndex) * LOW_RATIO).roundToInt()]),
        )
    }

    private fun randomColor(random: Random): Int = rgb(
        random.nextInt(256),
        random.nextInt(256),
        random.nextInt(256),
    )

    private fun rgb(red: Int, green: Int, blue: Int): Int =
        (0xff shl 24) or (red shl 16) or (green shl 8) or blue

    private fun luminance(color: Int): Int = luminance(
        red = color ushr 16 and 0xff,
        green = color ushr 8 and 0xff,
        blue = color and 0xff,
    )

    private fun luminance(red: Int, green: Int, blue: Int): Int =
        (red * 299 + green * 587 + blue * 114) / 1000

    private companion object {
        const val BRIGHT_RATIO = 0.45f
        const val LOW_RATIO = 0.30f
    }
}
