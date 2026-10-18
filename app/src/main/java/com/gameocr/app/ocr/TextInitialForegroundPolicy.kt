package com.gameocr.app.ocr

import kotlin.math.abs

/** Plans missing ink before repair only when the clean ring has a verified uniform background. */
internal object TextInitialForegroundPolicy {
    enum class Reason { PLANNED, INSUFFICIENT_SAMPLES, BACKGROUND_NOT_FLAT, LOW_CONTRAST }
    data class Result(val foreground: TextRegionForegroundRecovery.Result?, val backgroundColor: Int?, val reason: Reason,
        val excludedInkSamples: Int = 0)

    fun plan(width: Int, height: Int, argb: IntArray, core: BooleanArray,
        support: BooleanArray, semantic: BooleanArray, erase: BooleanArray): Result {
        require(width > 0 && height > 0)
        require(listOf(core.size, support.size, semantic.size, erase.size).all { it == argb.size } && argb.size == width * height)
        fun rejected(reason: Reason) = Result(null, null, reason)
        val outer = BinarySquareDilation.dilate(erase, width, height, 2)
        var ringCount = 0
        var seedCount = 0
        for (i in argb.indices) {
            if (outer[i] && !erase[i]) ringCount++
            if (core[i] && support[i]) seedCount++
        }
        if (ringCount < 16 || seedCount < 4) return rejected(Reason.INSUFFICIENT_SAMPLES)
        val step = ((ringCount + 4095) / 4096).coerceAtLeast(1)
        val samples = IntArray((ringCount + step - 1) / step)
        val sampleIndices = IntArray(samples.size)
        val seeds = IntArray(seedCount)
        var ringCursor = 0; var sampleCursor = 0; var seedCursor = 0
        for (i in argb.indices) {
            if (outer[i] && !erase[i] && ringCursor++ % step == 0) {
                sampleIndices[sampleCursor] = i
                samples[sampleCursor++] = argb[i]
            }
            if (core[i] && support[i]) seeds[seedCursor++] = i
        }
        val background = ArgbChannelMedian.fromIndexedColors(samples, IntArray(samples.size) { it })
        val foreground = ArgbChannelMedian.fromIndexedColors(argb, seeds)
        val contrast = distance(foreground, background)
        if (contrast < 40) return rejected(Reason.LOW_CONTRAST)
        val clean = IntArray(samples.size)
        var cleanCount = 0
        for (j in samples.indices) {
            val color = samples[j]
            if (distance(color, background) <= 12) {
                clean[cleanCount++] = color
                continue
            }
            val i = sampleIndices[j]
            // The original ring can touch missed dark/light stroke ends. Exclude those samples
            // only inside confirmed text geometry and when their color matches the known ink.
            // A gradient, colored artwork, or an outer balloon edge still rejects the estimate.
            if ((!support[i] && !semantic[i]) || distance(color, foreground) > maxOf(32, (contrast * 0.35f).toInt())) {
                return rejected(Reason.BACKGROUND_NOT_FLAT)
            }
        }
        if (cleanCount < 16 || cleanCount.toFloat() / samples.size < 0.70f) return rejected(Reason.BACKGROUND_NOT_FLAT)
        val cleanSamples = clean.copyOf(cleanCount)
        val cleanBackground = ArgbChannelMedian.fromIndexedColors(cleanSamples, IntArray(cleanCount) { it })
        val connected = TextForegroundMaskCompleter.complete(width, height, argb, core, support, cleanSamples)
        val complete = TextRegionForegroundRecovery.recover(width, height, argb, connected.mask,
            semantic, cleanSamples, flatRepair = true)
        return Result(complete, cleanBackground, Reason.PLANNED, samples.size - cleanCount)
    }

    private fun distance(a: Int, b: Int): Int = maxOf(
        abs((a ushr 16 and 255) - (b ushr 16 and 255)),
        abs((a ushr 8 and 255) - (b ushr 8 and 255)),
        abs((a and 255) - (b and 255)),
    )
}
