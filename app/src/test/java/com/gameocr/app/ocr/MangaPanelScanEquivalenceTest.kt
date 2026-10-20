package com.gameocr.app.ocr

import com.gameocr.app.ocr.MangaPanelGroupingPolicy.Separator
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaPanelScanEquivalenceTest {
    @Test
    fun optimizedScan_matchesOriginalSeparatorsAcrossThresholdsEdgesAndRadii() {
        val random = Random(9187)
        val dimensions = listOf(1 to 1, 2 to 9, 9 to 2, 17 to 23,
            64 to 96, 160 to 320, 480 to 510, 900 to 780, 1440 to 3200)
        for ((width, height) in dimensions) for (mode in 0..2) {
            val shades = intArrayOf(0xff000000.toInt(), 0xff6d6d6d.toInt(),
                0xff6e6e6e.toInt(), 0xffe0e0e0.toInt(), 0xffe1e1e1.toInt(), -1)
            val source = IntArray(width * height) {
                when (mode) {
                    0 -> -1
                    1 -> shades[random.nextInt(shades.size)]
                    else -> if (random.nextInt(10) < 8) -1 else random.nextInt() or 0xff000000.toInt()
                }
            }
            if (width > 12 && height > 12) {
                for (x in listOf(1, width / 2, width - 2)) {
                    for (y in 3 until height - 3) source[y * width + x] = 0xff6d6d6d.toInt()
                }
                for (y in listOf(1, height / 2, height - 2)) {
                    for (x in 3 until width - 3) source[y * width + x] = 0xff6d6d6d.toInt()
                }
            }
            assertEquals("${width}x$height mode=$mode", referenceSeparators(width, height, source),
                MangaPanelGroupingPolicy.separators(width, height, source))
        }
    }

    private fun referenceSeparators(width: Int, height: Int, argb: IntArray): List<Separator> {
        require(width > 0 && height > 0 && argb.size == width * height)
        val minimumRun = maxOf(24, minOf(width, height) / 10)
        val edgeRadius = maxOf(2, minOf(width, height) / 150)
        val output = mutableListOf<Separator>()
        val luminances = ByteArray(argb.size) { i -> (((argb[i] ushr 16 and 255) * 299 +
            (argb[i] ushr 8 and 255) * 587 + (argb[i] and 255) * 114) / 1000).toByte() }
        fun luma(i: Int): Int = luminances[i].toInt() and 255
        for (vertical in listOf(true, false)) {
            val lanes = if (vertical) width else height
            val length = if (vertical) height else width
            fun index(lane: Int, position: Int) = if (vertical) position * width + lane else lane * width + position
            for (lane in 1 until lanes - 1) {
                // Collapse adjacent dark lanes to the frame's leading edge.
                var position = 0
                while (position < length) {
                    fun dark(position: Int) = (-1..1).any { offset -> luma(index(lane + offset, position)) < 110 }
                    if (!dark(position)) { position++; continue }
                    val start = position
                    var lightBefore = 0; var lightAfter = 0
                    while (position < length && dark(position)) {
                        if ((1..edgeRadius).any { lane-it >= 0 && luma(index(lane-it, position)) >= 225 }) lightBefore++
                        if ((1..edgeRadius).any { lane+it < lanes && luma(index(lane+it, position)) >= 225 }) lightAfter++
                        position++
                    }
                    val run = position - start
                    if (run >= minimumRun && maxOf(lightBefore, lightAfter) >= run * 0.80f) {
                        output += Separator(vertical, lane, start, position)
                    }
                }
            }
        }
        val merged = mutableListOf<Separator>()
        output.sortedWith(compareBy({ it.vertical }, { it.lane }, { it.start })).forEach { line ->
            val previous = merged.lastOrNull()
            if (previous != null && previous.vertical == line.vertical &&
                line.lane - previous.lane <= edgeRadius && line.start <= previous.end && line.end >= previous.start) {
                merged[merged.lastIndex] = previous.copy(start = minOf(previous.start, line.start), end = maxOf(previous.end, line.end))
            } else merged += line
        }
        return merged
    }

}
