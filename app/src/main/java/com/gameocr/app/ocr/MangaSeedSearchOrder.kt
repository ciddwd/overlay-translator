package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

/** Pre-sorts polygon pixels by distance while retaining row-major tie ordering. */
internal class MangaSeedSearchOrder private constructor(
    private val imageWidth: Int,
    private val globalIndices: IntArray,
    private val orderedPositions: LongArray,
) {
    /** Sample the center and spatial extremes so a letter counter cannot become the only seed. */
    fun spatialSeeds(candidate: BooleanArray, roi: IntRect): List<Int> {
        require(roi.width > 0 && roi.height > 0)
        require(candidate.size == roi.width * roi.height)
        var nearest = -1
        var left = -1
        var right = -1
        var top = -1
        var bottom = -1
        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (packed in orderedPositions) {
            val globalIndex = globalIndices[packed.toInt()]
            val y = globalIndex / imageWidth
            val x = globalIndex - y * imageWidth
            if (x !in roi.left until roi.right || y !in roi.top until roi.bottom) continue
            val index = (y - roi.top) * roi.width + x - roi.left
            if (!candidate[index]) continue
            if (nearest < 0) nearest = index
            if (x < minX) { minX = x; left = index }
            if (x > maxX) { maxX = x; right = index }
            if (y < minY) { minY = y; top = index }
            if (y > maxY) { maxY = y; bottom = index }
        }
        return listOf(nearest, left, right, top, bottom).filter { it >= 0 }.distinct()
    }

    fun nearest(
        candidate: BooleanArray,
        roi: IntRect,
    ): Int? {
        require(roi.width > 0 && roi.height > 0)
        require(candidate.size == roi.width * roi.height)
        for (packed in orderedPositions) {
            val globalIndex = globalIndices[packed.toInt()]
            val y = globalIndex / imageWidth
            val x = globalIndex - y * imageWidth
            if (x !in roi.left until roi.right || y !in roi.top until roi.bottom) continue
            val localIndex = (y - roi.top) * roi.width + (x - roi.left)
            if (candidate[localIndex]) return localIndex
        }
        return null
    }

    companion object {
        fun prepare(
            imageWidth: Int,
            globalIndices: IntArray,
            distanceSquared: FloatArray,
            count: Int = globalIndices.size,
        ): MangaSeedSearchOrder {
            require(imageWidth > 0)
            require(count in 0..globalIndices.size)
            require(count <= distanceSquared.size)
            val indices = globalIndices.copyOf(count)
            val order = LongArray(count) { position ->
                val distance = distanceSquared[position]
                require(distance.isFinite() && distance >= 0f)
                (java.lang.Float.floatToRawIntBits(distance).toLong() shl 32) or
                    (position.toLong() and 0xffff_ffffL)
            }
            order.sort()
            return MangaSeedSearchOrder(
                imageWidth = imageWidth,
                globalIndices = indices,
                orderedPositions = order,
            )
        }
    }
}
