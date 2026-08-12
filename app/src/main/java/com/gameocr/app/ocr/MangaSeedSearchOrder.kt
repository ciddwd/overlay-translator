package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

/** Pre-sorts polygon pixels by distance while retaining row-major tie ordering. */
internal class MangaSeedSearchOrder private constructor(
    private val imageWidth: Int,
    private val globalIndices: IntArray,
    private val orderedPositions: LongArray,
) {
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
