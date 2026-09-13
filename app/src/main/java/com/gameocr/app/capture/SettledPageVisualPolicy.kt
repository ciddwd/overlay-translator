package com.gameocr.app.capture

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Samples retain their visibility separately; an obscured pixel is not a black source pixel. */
internal data class SettledPageFrame(
    val width: Int,
    val height: Int,
    val contextId: Int,
    val luminance: ByteArray,
    val visible: BooleanArray,
)

internal object SettledPageVisualPolicy {
    // A tiny exposed sliver cannot establish page identity or stability.
    private const val MIN_VISIBLE_FRACTION = 0.10f

    fun visibility(
        width: Int,
        height: Int,
        sampleSize: Int,
        exclusions: List<OverlayCaptureRect>,
    ): BooleanArray {
        require(width > 0 && height > 0 && sampleSize > 0)
        val visible = BooleanArray(sampleSize * sampleSize) { true }
        exclusions.forEach { rect ->
            if (rect.isEmpty || rect.right <= 0 || rect.bottom <= 0 || rect.left >= width || rect.top >= height) {
                return@forEach
            }
            // Guard one sample cell for filtering and rounded/shadowed window edges.
            val left = (floor(rect.left.coerceAtLeast(0) * sampleSize.toDouble() / width).toInt() - 1).coerceAtLeast(0)
            val top = (floor(rect.top.coerceAtLeast(0) * sampleSize.toDouble() / height).toInt() - 1).coerceAtLeast(0)
            val right = (ceil(rect.right.coerceAtMost(width) * sampleSize.toDouble() / width).toInt() + 1).coerceAtMost(sampleSize)
            val bottom = (ceil(rect.bottom.coerceAtMost(height) * sampleSize.toDouble() / height).toInt() + 1).coerceAtMost(sampleSize)
            for (y in top until bottom) for (x in left until right) visible[y * sampleSize + x] = false
        }
        return visible
    }

    fun comparable(a: SettledPageFrame, b: SettledPageFrame): Boolean =
        a.width == b.width && a.height == b.height && a.contextId == b.contextId &&
            a.luminance.size == b.luminance.size

    /** Null means there is insufficient common source content; do not clear or resubmit a page. */
    fun similarity(a: SettledPageFrame, b: SettledPageFrame): Float? {
        if (!comparable(a, b) || a.luminance.isEmpty() ||
            a.visible.size != a.luminance.size || b.visible.size != b.luminance.size
        ) return null
        var count = 0
        var difference = 0L
        for (i in a.luminance.indices) {
            if (!a.visible[i] || !b.visible[i]) continue
            count++
            difference += abs((a.luminance[i].toInt() and 255) - (b.luminance[i].toInt() and 255))
        }
        if (count == 0 || count.toFloat() / a.luminance.size < MIN_VISIBLE_FRACTION) return null
        return (1.0 - difference.toDouble() / (count.toLong() * 255L)).toFloat().coerceIn(0f, 1f)
    }
}
