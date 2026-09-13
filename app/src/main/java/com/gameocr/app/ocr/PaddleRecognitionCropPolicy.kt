package com.gameocr.app.ocr

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Recognition-only crop refinement. DB support is not a glyph outline: use it to distinguish
 * edge-connected background from ink, never as the final crop. Every interior foreground
 * component (including detached dots/diacritics) is retained, with a scale-relative margin.
 * Ambiguous/low-contrast backgrounds and ink touching the crop edge stay unchanged.
 *
 * References: OpenCV Otsu thresholding; Tesseract ImproveQuality / Borders.
 * No detector/render geometry, model, OCR characters or user unclip settings are changed.
 */
internal object PaddleRecognitionCropPolicy {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom
    }

    class Budget {
        private var crops = 0
        private var pixels = 0L
        fun reserve(width: Int, height: Int): Boolean {
            val count = width.toLong() * height
            if (width <= 0 || height <= 0 || crops >= 4 || count > MAX_PIXELS - pixels) return false
            crops++
            pixels += count
            return true
        }
    }

    fun shouldRefine(score: Float): Boolean = score.isFinite() && score > 0f && score < .7f

    fun accept(originalScore: Float, candidateScore: Float): Boolean =
        shouldRefine(originalScore) && candidateScore.isFinite() &&
            candidateScore in .75f..1f && candidateScore - originalScore >= .1f

    fun propose(width: Int, height: Int, argb: IntArray, support: Bounds): Bounds? {
        val count = width.toLong() * height
        if (width <= 0 || height <= 0 || count > MAX_PIXELS || count != argb.size.toLong()) return null
        if (width < height * 1.5f || support.left < 0 || support.top < 0 ||
            support.right > width || support.bottom > height || support.width <= 0 || support.height <= 0) return null
        val outsideCount = argb.size - support.width * support.height
        if (outsideCount < argb.size * .1f) return null

        val gray = IntArray(argb.size)
        val histogram = IntArray(256)
        var sum = 0L
        argb.indices.forEach { i ->
            val color = argb[i]
            val value = ((color ushr 16 and 255) * 299 + (color ushr 8 and 255) * 587 +
                (color and 255) * 114 + 500) / 1000
            gray[i] = value
            histogram[value]++
            sum += value
        }
        var weight = 0L
        var partialSum = 0L
        var bestVariance = 0.0
        var threshold = -1
        for (value in 0..254) {
            weight += histogram[value]
            partialSum += histogram[value].toLong() * value
            val other = argb.size - weight
            if (weight == 0L || other == 0L) continue
            val delta = partialSum.toDouble() / weight - (sum - partialSum).toDouble() / other
            val variance = weight.toDouble() * other * delta * delta
            if (variance > bestVariance) { bestVariance = variance; threshold = value }
        }
        if (threshold < 0) return null
        var outsideDark = 0
        gray.indices.forEach { i ->
            if (!support.contains(i % width, i / width) && gray[i] <= threshold) outsideDark++
        }
        val darkBackground = outsideDark > outsideCount / 2
        val foreground = BooleanArray(argb.size)
        var foregroundCount = 0
        var foregroundSum = 0L
        gray.indices.forEach { i ->
            foreground[i] = (gray[i] <= threshold) != darkBackground
            if (foreground[i]) { foregroundCount++; foregroundSum += gray[i] }
        }
        val backgroundCount = argb.size - foregroundCount
        if (foregroundCount < 16 || backgroundCount < 16 || foregroundCount > argb.size / 2) return null
        val contrast = abs(foregroundSum.toDouble() / foregroundCount -
            (sum - foregroundSum).toDouble() / backgroundCount)
        if (contrast < 64.0) return null

        val visited = BooleanArray(argb.size)
        val queue = IntArray(argb.size)
        var left = support.left
        var top = support.top
        var right = support.right
        var bottom = support.bottom
        var keptPixels = 0
        for (start in foreground.indices) {
            if (!foreground[start] || visited[start]) continue
            var head = 0
            var tail = 1
            queue[0] = start
            visited[start] = true
            var edge = false
            var touchesSupport = false
            var cl = width
            var ct = height
            var cr = 0
            var cb = 0
            while (head < tail) {
                val i = queue[head++]
                val x = i % width
                val y = i / width
                edge = edge || x == 0 || y == 0 || x == width - 1 || y == height - 1
                touchesSupport = touchesSupport || support.contains(x, y)
                cl = minOf(cl, x); ct = minOf(ct, y); cr = maxOf(cr, x + 1); cb = maxOf(cb, y + 1)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx; val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (foreground[next] && !visited[next]) { visited[next] = true; queue[tail++] = next }
                }
            }
            // A connected stroke running out of the crop cannot safely be trimmed.
            if (edge && touchesSupport) return null
            if (edge) continue
            keptPixels += tail
            left = minOf(left, cl); top = minOf(top, ct); right = maxOf(right, cr); bottom = maxOf(bottom, cb)
        }
        if (keptPixels < 16) return null
        val margin = ceil((bottom - top) * .05).toInt().coerceAtLeast(1)
        val bounds = Bounds((left - margin).coerceAtLeast(0), (top - margin).coerceAtLeast(0),
            (right + margin).coerceAtMost(width), (bottom + margin).coerceAtMost(height))
        if (bounds.height.toFloat() / height !in .55f.. .9f || bounds.width < width * .75f) return null
        return bounds
    }

    private const val MAX_PIXELS = 1_000_000L
}
