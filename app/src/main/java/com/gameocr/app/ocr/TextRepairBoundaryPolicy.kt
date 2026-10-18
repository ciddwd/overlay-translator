package com.gameocr.app.ocr

/** A verified interior separates curved balloon outlines from text that touches them. */
internal object TextRepairBoundaryPolicy {
    fun constrain(
        mask: TextPixelMaskBuilder.BlockMask,
        interior: BubbleSegmentationPostprocessor.InstanceMask,
        quality: BubbleShapeMaskQuality,
    ): TextPixelMaskBuilder.BlockMask {
        if (quality != BubbleShapeMaskQuality.TRUSTED) return mask
        // Ink joined to a curved outline can cut a narrow notch out of a pixel-derived mask.
        // Close only small notches; a substantial concavity may be real artwork or a bubble tail.
        val closed = closeSmallNotches(interior.width, interior.height, interior.pixels)
        val smallClosure = closed.indices.count { closed[it] && !interior.pixels[it] } <=
            interior.pixels.count { it } * 0.02f
        val inside = BooleanArray(mask.pixels.size) { i ->
            val x = mask.bounds.left + i % mask.bounds.width - interior.left
            val y = mask.bounds.top + i / mask.bounds.width - interior.top
            x in 0 until interior.width && y in 0 until interior.height &&
                (interior.pixels[y * interior.width + x] ||
                    (smallClosure && mask.semanticPixels[i] && closed[y * interior.width + x]))
        }
        val core = BooleanArray(inside.size) { inside[it] && mask.corePixels[it] }
        val coreCount = core.count { it }
        if (coreCount == 0 || coreCount.toDouble() / mask.selectedCorePixels < 0.70) return mask
        return mask.copy(
            pixels = BooleanArray(inside.size) { inside[it] && mask.pixels[it] },
            corePixels = core,
            selectedCorePixels = coreCount,
            supportPixels = BooleanArray(inside.size) { inside[it] && mask.supportPixels[it] },
            semanticPixels = BooleanArray(inside.size) { inside[it] && mask.semanticPixels[it] },
            barrierPixels = BooleanArray(inside.size) { !inside[it] },
        )
    }

    private fun closeSmallNotches(width: Int, height: Int, pixels: BooleanArray): BooleanArray {
        data class Point(val x: Int, val y: Int)
        val edges = ArrayList<Point>()
        for (y in 0 until height) {
            val first = (0 until width).firstOrNull { pixels[y * width + it] } ?: continue
            val last = (width - 1 downTo first).first { pixels[y * width + it] }
            edges += Point(first, y); edges += Point(last, y)
        }
        val points = edges.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (points.size < 3) return pixels
        fun cross(a: Point, b: Point, c: Point) = (b.x - a.x).toLong() * (c.y - a.y) -
            (b.y - a.y).toLong() * (c.x - a.x)
        fun chain(input: List<Point>): List<Point> {
            val output = ArrayList<Point>()
            for (point in input) {
                while (output.size >= 2 && cross(output[output.lastIndex - 1], output.last(), point) <= 0) {
                    output.removeAt(output.lastIndex)
                }
                output += point
            }
            return output.dropLast(1)
        }
        val hull = chain(points) + chain(points.asReversed())
        val closed = pixels.copyOf()
        for (y in 0 until height) {
            val intersections = ArrayList<Double>()
            for (index in hull.indices) {
                val a = hull[index]; val b = hull[(index + 1) % hull.size]
                if (y !in minOf(a.y, b.y)..maxOf(a.y, b.y)) continue
                if (a.y == b.y) { intersections += a.x.toDouble(); intersections += b.x.toDouble() }
                else intersections += a.x + (y - a.y).toDouble() * (b.x - a.x) / (b.y - a.y)
            }
            if (intersections.isEmpty()) continue
            val left = kotlin.math.ceil(intersections.min()).toInt().coerceAtLeast(0)
            val right = kotlin.math.floor(intersections.max()).toInt().coerceAtMost(width - 1)
            for (x in left..right) closed[y * width + x] = true
        }
        return closed
    }
}
