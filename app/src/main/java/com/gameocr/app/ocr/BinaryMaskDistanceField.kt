package com.gameocr.app.ocr

import kotlin.math.max

/** Exact squared Euclidean distance to the nearest true pixel in a binary mask. */
internal object BinaryMaskDistanceField {
    const val UNREACHABLE: Int = Int.MAX_VALUE / 4

    fun squaredEuclidean(
        width: Int,
        height: Int,
        sourceMask: BooleanArray,
    ): IntArray {
        require(width > 0 && height > 0)
        require(sourceMask.size == width * height)
        if (sourceMask.none { it }) return IntArray(sourceMask.size) { UNREACHABLE }

        val intermediate = IntArray(sourceMask.size)
        val output = IntArray(sourceMask.size)
        val workSize = max(width, height)
        val source = IntArray(workSize)
        val transformed = IntArray(workSize)
        val locations = IntArray(workSize)
        val boundaries = DoubleArray(workSize + 1)

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                source[x] = if (sourceMask[rowOffset + x]) 0 else UNREACHABLE
            }
            transform1d(source, width, transformed, locations, boundaries)
            for (x in 0 until width) {
                intermediate[rowOffset + x] = transformed[x]
            }
        }

        for (x in 0 until width) {
            for (y in 0 until height) {
                source[y] = intermediate[y * width + x]
            }
            transform1d(source, height, transformed, locations, boundaries)
            for (y in 0 until height) {
                output[y * width + x] = transformed[y]
            }
        }
        return output
    }

    /** Felzenszwalb-Huttenlocher lower-envelope transform, O(n). */
    private fun transform1d(
        source: IntArray,
        length: Int,
        output: IntArray,
        locations: IntArray,
        boundaries: DoubleArray,
    ) {
        var envelopeSize = 0
        locations[0] = 0
        boundaries[0] = Double.NEGATIVE_INFINITY
        boundaries[1] = Double.POSITIVE_INFINITY

        for (position in 1 until length) {
            var intersection: Double
            while (true) {
                val previous = locations[envelopeSize]
                intersection = (
                    source[position].toLong() + position.toLong() * position -
                        source[previous].toLong() - previous.toLong() * previous
                    ).toDouble() / (2.0 * (position - previous))
                if (intersection > boundaries[envelopeSize]) break
                envelopeSize--
            }
            envelopeSize++
            locations[envelopeSize] = position
            boundaries[envelopeSize] = intersection
            boundaries[envelopeSize + 1] = Double.POSITIVE_INFINITY
        }

        envelopeSize = 0
        for (position in 0 until length) {
            while (boundaries[envelopeSize + 1] < position) envelopeSize++
            val nearest = locations[envelopeSize]
            val delta = position - nearest
            output[position] = (
                source[nearest].toLong() + delta.toLong() * delta
                ).coerceAtMost(UNREACHABLE.toLong()).toInt()
        }
    }
}
