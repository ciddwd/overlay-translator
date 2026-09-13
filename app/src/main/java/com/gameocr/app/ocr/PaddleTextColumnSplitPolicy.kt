package com.gameocr.app.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Geometry-only stable probability bands. No OCR strings, image coordinates or model calls. */
internal object PaddleTextColumnSplitPolicy {
    data class Partition(val members: List<IntArray>, val cuts: List<Float>)
    private data class Band(val left: Int, val right: Int)

    /** Reject separators crossing ink; support both dark and light text on a flat background. */
    fun clearSeparator(luminance: IntArray): Boolean {
        if (luminance.isEmpty() || luminance.any { it !in 0..255 }) return false
        val sorted = luminance.sorted()
        val background = sorted[sorted.size / 2]
        if (background in 33..223) return false
        return luminance.count { kotlin.math.abs(it - background) > 32 } <= luminance.size * 0.02f
    }

    fun split(
        map: Array<FloatArray>,
        members: IntArray,
        threshold: Float,
        cutIsClear: (x: Float, top: Int, bottom: Int) -> Boolean,
    ): Partition? {
        val width = map.firstOrNull()?.size ?: return null
        if (width == 0 || members.size !in 32..262144 || !threshold.isFinite() || threshold !in 0f..1f) return null
        if (map.any { it.size != width }) return null
        if (members.any { it < 0 || it.toLong() >= width.toLong() * map.size }) return null
        val left = members.minOf { it % width }
        val right = members.maxOf { it % width }
        val top = members.minOf { it / width }
        val bottom = members.maxOf { it / width }
        val w = right - left + 1
        val h = bottom - top + 1
        // A short/square component can be a single ideograph or Hangul syllable.
        if (w < 7 || h < w * 1.3f) return null
        val values = members.map { map[it / width][it % width] }.sorted()
        if (values.any { !it.isFinite() }) return null
        val peak = values[values.size * 3 / 4]
        if (peak - threshold < 0.25f) return null
        fun bands(level: Float): Pair<List<Band>, IntArray> {
            val counts = IntArray(w)
            members.forEach { if (map[it / width][it % width] >= level) counts[it % width - left]++ }
            val minimum = ceil(h * 0.18f).toInt().coerceAtLeast(3)
            val result = mutableListOf<Band>()
            var x = 0
            while (x < w) {
                if (counts[x] < minimum) { x++; continue }
                val start = x
                while (x < w && counts[x] >= minimum) x++
                result += Band(start, x)
            }
            return result to counts
        }
        val (low, counts) = bands(threshold + (peak - threshold) * 0.35f)
        val (high, _) = bands(threshold + (peak - threshold) * 0.55f)
        if (low.size !in 2..6 || low.size != high.size) return null
        val widths = low.map { it.right - it.left }
        if (widths.min() < 3 || widths.max() > widths.min() * 2.2f) return null
        if (low.zip(high).any { (a, b) ->
                min(a.right, b.right) - max(a.left, b.left) < (a.right - a.left) * 0.65f
            }) return null
        val covered = low.sumOf { b -> (b.left until b.right).sumOf { counts[it] } }
        if (covered < counts.sum() * 0.85f) return null
        val extents = low.map { b ->
            val core = members.filter { it % width - left in b.left until b.right &&
                map[it / width][it % width] >= threshold + (peak - threshold) * 0.35f }
            core.minOf { it / width } to core.maxOf { it / width }
        }
        if (extents.zip(widths).any { (e, bw) -> e.second - e.first + 1 < bw * 2.2f }) return null
        if (extents.zipWithNext().any { (a, b) ->
                min(a.second, b.second) - max(a.first, b.first) + 1 <
                    min(a.second - a.first + 1, b.second - b.first + 1) * 0.4f
            }) return null
        val cuts = low.zipWithNext().map { (a, b) ->
            val gap = b.left - a.right
            if (gap < max(1f, min(a.right - a.left, b.right - b.left) * 0.18f)) return null
            val valley = (a.right until b.left).minOf { counts[it] }
            if (valley > h * 0.08f) return null
            val candidates = (a.right until b.left).filter { counts[it] <= valley + 1 }
            val center = (a.right + b.left - 1) / 2f
            val cut = candidates.minBy { kotlin.math.abs(it - center) } + left + 0.5f
            if (!cutIsClear(cut, top, bottom + 1)) return null
            cut
        }
        val groups = List(cuts.size + 1) { mutableListOf<Int>() }
        members.forEach { pixel -> groups[cuts.count { pixel % width >= it }].add(pixel) }
        if (groups.any { it.size < 16 }) return null
        // A missing weak column must not be silently assigned to a strong neighbour.
        if (groups.zip(widths).any { (pixels, seedWidth) ->
                pixels.maxOf { it % width } - pixels.minOf { it % width } + 1 > seedWidth * 2.2f
            }) return null
        return Partition(groups.map { it.toIntArray() }, cuts)
    }
}
