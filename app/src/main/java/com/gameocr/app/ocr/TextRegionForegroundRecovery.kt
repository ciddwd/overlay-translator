package com.gameocr.app.ocr

import kotlin.math.abs
import kotlin.math.max

/** Recovers disconnected ink within a confirmed text region on a verified flat background. */
internal object TextRegionForegroundRecovery {
    enum class Reason { FLAT_BACKGROUND, BACKGROUND_NOT_FLAT, INSUFFICIENT_SAMPLES }

    data class Result(val mask: BooleanArray, val addedPixels: Int, val reason: Reason)

    fun recover(
        width: Int,
        height: Int,
        argb: IntArray,
        knownForeground: BooleanArray,
        textRegion: BooleanArray,
        backgroundSamples: IntArray,
        flatRepair: Boolean,
    ): Result {
        require(width > 0 && height > 0)
        require(argb.size == width * height && knownForeground.size == argb.size && textRegion.size == argb.size)
        fun unchanged(reason: Reason) = Result(knownForeground, 0, reason)
        if (!flatRepair) return unchanged(Reason.BACKGROUND_NOT_FLAT)
        if (backgroundSamples.size < MIN_SAMPLES) return unchanged(Reason.INSUFFICIENT_SAMPLES)

        val background = ArgbChannelMedian.fromIndexedColors(backgroundSamples, IntArray(backgroundSamples.size) { it })
        val deviations = IntArray(256)
        backgroundSamples.forEach { deviations[distance(it, background)]++ }
        val rank = (backgroundSamples.size * 9) / 10
        var seen = 0
        val spread = deviations.indices.first { seen += deviations[it]; seen > rank }
        if (spread > MAX_BACKGROUND_SPREAD) return unchanged(Reason.BACKGROUND_NOT_FLAT)
        val threshold = max(MIN_INK_CONTRAST, spread * 2)

        // Label the original crop, not just the clipped text rectangle. This lets us reject a
        // balloon border or illustration that continues outside the confirmed text region.
        val candidate = BooleanArray(argb.size) { distance(argb[it], background) > threshold }
        val visited = BooleanArray(argb.size)
        val queue = IntArray(argb.size)
        val output = knownForeground.copyOf()
        var added = 0
        for (start in candidate.indices) {
            if (!candidate[start] || visited[start]) continue
            var head = 0
            var tail = 1
            queue[0] = start
            visited[start] = true
            var outsideText = false
            var touchesKnownInk = false
            while (head < tail) {
                val index = queue[head++]
                outsideText = outsideText || !textRegion[index]
                touchesKnownInk = touchesKnownInk || (knownForeground[index] && textRegion[index])
                val x = index % width
                val y = index / width
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until width || ny !in 0 until height) continue
                    val next = ny * width + nx
                    if (visited[next] || !candidate[next]) continue
                    visited[next] = true
                    queue[tail++] = next
                }
            }
            if (outsideText && !touchesKnownInk) continue
            for (position in 0 until tail) {
                val index = queue[position]
                if (textRegion[index] && !output[index]) {
                    output[index] = true
                    added++
                }
            }
        }
        return Result(output, added, Reason.FLAT_BACKGROUND)
    }

    private fun distance(a: Int, b: Int): Int = maxOf(
        abs((a ushr 16 and 255) - (b ushr 16 and 255)),
        abs((a ushr 8 and 255) - (b ushr 8 and 255)),
        abs((a and 255) - (b and 255)),
    )

    private const val MIN_SAMPLES = 16
    private const val MAX_BACKGROUND_SPREAD = 20
    private const val MIN_INK_CONTRAST = 20
}
