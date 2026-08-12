package com.gameocr.app.ocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Completes weak, detector-missed foreground pixels inside already confirmed OCR geometry.
 *
 * Strong detector pixels remain the only seeds. Weak pixels must have the same luminance polarity
 * as those seeds, differ sufficiently from the local background, and belong to a component that
 * touches or is scale-near a seed. This keeps the operation content-independent and prevents a
 * distant panel or illustration edge from becoming an erase target.
 */
internal object TextForegroundMaskCompleter {

    data class Result(
        val mask: BooleanArray,
        val addedPixels: Int,
    )

    fun complete(
        width: Int,
        height: Int,
        argb: IntArray,
        strongMask: BooleanArray,
        supportMask: BooleanArray,
        backgroundSamples: IntArray,
    ): Result {
        require(width > 0 && height > 0)
        require(argb.size == width * height)
        require(strongMask.size == argb.size)
        require(supportMask.size == argb.size)

        var strongPixelCount = 0
        strongMask.indices.forEach { index ->
            if (strongMask[index] && supportMask[index]) strongPixelCount++
        }
        if (
            strongPixelCount < MIN_STRONG_PIXELS ||
            backgroundSamples.size < MIN_BACKGROUND_SAMPLES
        ) {
            return Result(strongMask.copyOf(), addedPixels = 0)
        }

        val foregroundSamples = IntArray(strongPixelCount)
        var foregroundSampleCount = 0
        strongMask.indices.forEach { index ->
            if (strongMask[index] && supportMask[index]) {
                foregroundSamples[foregroundSampleCount++] = argb[index]
            }
        }
        val foreground = medianColor(foregroundSamples)
        val background = medianColor(backgroundSamples)
        val foregroundLuminance = luminance(foreground)
        val backgroundLuminance = luminance(background)
        val luminanceContrast = abs(foregroundLuminance - backgroundLuminance)
        val colorContrast = sqrt(colorDistanceSquared(foreground, background).toDouble())
        if (
            luminanceContrast < MIN_FOREGROUND_LUMINANCE_CONTRAST ||
            colorContrast < MIN_FOREGROUND_COLOR_CONTRAST
        ) {
            return Result(strongMask.copyOf(), addedPixels = 0)
        }

        val darkForeground = foregroundLuminance < backgroundLuminance
        val minimumLuminanceDifference = maxOf(
            MIN_WEAK_LUMINANCE_DIFFERENCE,
            (luminanceContrast * WEAK_CONTRAST_RATIO).roundToInt(),
        )
        val minimumColorDifference = maxOf(
            MIN_WEAK_COLOR_DIFFERENCE,
            (colorContrast * WEAK_CONTRAST_RATIO).roundToInt(),
        )
        val minimumColorDifferenceSquared = minimumColorDifference * minimumColorDifference
        val weak = BooleanArray(argb.size)
        var supportLeft = width
        var supportTop = height
        var supportRight = 0
        var supportBottom = 0
        var supportPixels = 0
        for (index in supportMask.indices) {
            if (!supportMask[index]) continue
            val x = index % width
            val y = index / width
            supportLeft = minOf(supportLeft, x)
            supportTop = minOf(supportTop, y)
            supportRight = maxOf(supportRight, x + 1)
            supportBottom = maxOf(supportBottom, y + 1)
            supportPixels++
            if (strongMask[index]) {
                weak[index] = true
                continue
            }
            val pixelLuminance = luminance(argb[index])
            val samePolarity = if (darkForeground) {
                pixelLuminance <= backgroundLuminance - minimumLuminanceDifference
            } else {
                pixelLuminance >= backgroundLuminance + minimumLuminanceDifference
            }
            weak[index] = samePolarity &&
                colorDistanceSquared(argb[index], background) >= minimumColorDifferenceSquared
        }
        if (supportPixels == 0) return Result(strongMask.copyOf(), addedPixels = 0)

        val supportWidth = supportRight - supportLeft
        val supportHeight = supportBottom - supportTop
        val bridgeRadius = (minOf(supportWidth, supportHeight) * BRIDGE_RADIUS_RATIO)
            .roundToInt()
            .coerceIn(MIN_BRIDGE_RADIUS_PX, MAX_BRIDGE_RADIUS_PX)
        val distance = distanceFromStrong(
            width = width,
            height = height,
            strongMask = strongMask,
            supportMask = supportMask,
            maximumDistance = bridgeRadius,
        )
        val output = strongMask.copyOf()
        val visited = BooleanArray(weak.size)
        val queue = IntArray(weak.size)
        var addedPixels = 0
        for (start in weak.indices) {
            if (!weak[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var touchesStrong = false
            var isNearStrong = false
            var left = start % width
            var top = start / width
            var right = left + 1
            var bottom = top + 1
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                touchesStrong = touchesStrong || strongMask[index]
                isNearStrong = isNearStrong || distance[index] in 0..bridgeRadius
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
                for (dy in -1..1) {
                    val nextY = y + dy
                    if (nextY !in 0 until height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nextX = x + dx
                        if (nextX !in 0 until width) continue
                        val next = nextY * width + nextX
                        if (!weak[next] || visited[next]) continue
                        visited[next] = true
                        queue[tail++] = next
                    }
                }
            }
            val componentPixels = tail
            val detachedComponentIsRelated = isNearStrong && isRelatedDetachedComponent(
                componentPixels = componentPixels,
                componentWidth = right - left,
                componentHeight = bottom - top,
                supportPixels = supportPixels,
                supportWidth = supportWidth,
                supportHeight = supportHeight,
            )
            if (!touchesStrong && !detachedComponentIsRelated) continue
            for (position in 0 until tail) {
                val index = queue[position]
                if (!output[index]) {
                    output[index] = true
                    addedPixels++
                }
            }
        }
        return Result(mask = output, addedPixels = addedPixels)
    }

    private fun distanceFromStrong(
        width: Int,
        height: Int,
        strongMask: BooleanArray,
        supportMask: BooleanArray,
        maximumDistance: Int,
    ): IntArray {
        val distance = IntArray(strongMask.size) { UNREACHED }
        val queue = IntArray(strongMask.size)
        var head = 0
        var tail = 0
        strongMask.indices.forEach { index ->
            if (strongMask[index] && supportMask[index]) {
                distance[index] = 0
                queue[tail++] = index
            }
        }
        while (head < tail) {
            val index = queue[head++]
            val nextDistance = distance[index] + 1
            if (nextDistance > maximumDistance) continue
            val x = index % width
            val y = index / width
            for (dy in -1..1) {
                val nextY = y + dy
                if (nextY !in 0 until height) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nextX = x + dx
                    if (nextX !in 0 until width) continue
                    val next = nextY * width + nextX
                    if (!supportMask[next] || distance[next] != UNREACHED) continue
                    distance[next] = nextDistance
                    queue[tail++] = next
                }
            }
        }
        return distance
    }

    private fun isRelatedDetachedComponent(
        componentPixels: Int,
        componentWidth: Int,
        componentHeight: Int,
        supportPixels: Int,
        supportWidth: Int,
        supportHeight: Int,
    ): Boolean {
        val maximumPixels = maxOf(
            MIN_DETACHED_COMPONENT_PIXELS,
            ceil(supportPixels * MAX_DETACHED_SUPPORT_RATIO).toInt(),
        )
        if (componentPixels > maximumPixels) return false
        val longSide = maxOf(componentWidth, componentHeight)
        val shortSide = minOf(componentWidth, componentHeight).coerceAtLeast(1)
        val supportLongSide = maxOf(supportWidth, supportHeight).coerceAtLeast(1)
        val lineLike = longSide.toFloat() / shortSide >= MAX_DETACHED_ASPECT_RATIO &&
            longSide.toFloat() / supportLongSide >= MAX_DETACHED_LONG_SIDE_RATIO
        return !lineLike
    }

    private fun medianColor(colors: IntArray): Int {
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        colors.forEach { color ->
            red[color ushr 16 and 0xff]++
            green[color ushr 8 and 0xff]++
            blue[color and 0xff]++
        }
        val middle = colors.size / 2
        return (0xff shl 24) or
            (histogramValueAt(red, middle) shl 16) or
            (histogramValueAt(green, middle) shl 8) or
            histogramValueAt(blue, middle)
    }

    private fun histogramValueAt(histogram: IntArray, position: Int): Int {
        var cumulative = 0
        histogram.forEachIndexed { value, count ->
            cumulative += count
            if (cumulative > position) return value
        }
        return histogram.lastIndex
    }

    private fun luminance(color: Int): Int {
        val red = color ushr 16 and 0xff
        val green = color ushr 8 and 0xff
        val blue = color and 0xff
        return ((red * 299 + green * 587 + blue * 114) / 1000).coerceIn(0, 255)
    }

    private fun colorDistanceSquared(first: Int, second: Int): Int {
        val red = (first ushr 16 and 0xff) - (second ushr 16 and 0xff)
        val green = (first ushr 8 and 0xff) - (second ushr 8 and 0xff)
        val blue = (first and 0xff) - (second and 0xff)
        return red * red + green * green + blue * blue
    }

    private const val UNREACHED = -1
    private const val MIN_STRONG_PIXELS = 4
    private const val MIN_BACKGROUND_SAMPLES = 16
    private const val MIN_FOREGROUND_LUMINANCE_CONTRAST = 18
    private const val MIN_FOREGROUND_COLOR_CONTRAST = 28.0
    private const val MIN_WEAK_LUMINANCE_DIFFERENCE = 8
    private const val MIN_WEAK_COLOR_DIFFERENCE = 20
    private const val WEAK_CONTRAST_RATIO = 0.10f
    private const val BRIDGE_RADIUS_RATIO = 0.18f
    private const val MIN_BRIDGE_RADIUS_PX = 1
    private const val MAX_BRIDGE_RADIUS_PX = 16
    private const val MIN_DETACHED_COMPONENT_PIXELS = 12
    private const val MAX_DETACHED_SUPPORT_RATIO = 0.20
    private const val MAX_DETACHED_ASPECT_RATIO = 6f
    private const val MAX_DETACHED_LONG_SIDE_RATIO = 0.72f
}
