package com.gameocr.app.ocr

import kotlin.math.ceil
import kotlin.math.sqrt

/** Builds a coverage-first alpha mask for local text-background repair patches. */
internal object TextRepairFeathering {

    data class Plan(
        val opaqueMask: BooleanArray,
        val repairMask: BooleanArray,
        val alpha: IntArray,
        val existingExpansionPx: Int,
        val hardExpansionPx: Int,
        val featherWidthPx: Int,
    ) {
        init {
            require(opaqueMask.size == repairMask.size)
            require(alpha.size == repairMask.size)
            require(existingExpansionPx >= 0)
            require(hardExpansionPx > 0)
            require(featherWidthPx > 0)
        }

        val opaquePixelCount: Int = opaqueMask.count { it }

        val repairPixelCount: Int = repairMask.count { it }

        val featherPixelCount: Int = alpha.count { it in 1..254 }
    }

    fun plan(
        width: Int,
        height: Int,
        baseMask: BooleanArray,
        coreMask: BooleanArray,
        coordinateScale: Float,
    ): Plan {
        require(width > 0 && height > 0)
        require(baseMask.size == width * height)
        require(coreMask.size == baseMask.size)
        require(coordinateScale > 0f)
        require(baseMask.any { it })
        require(coreMask.any { it })
        require(coreMask.indices.all { index -> !coreMask[index] || baseMask[index] })

        val existingExpansion = maximumMaskDistance(
            width = width,
            height = height,
            traversableMask = baseMask,
            seedMask = coreMask,
        )
        val displayExpansion = (existingExpansion / coordinateScale).coerceAtLeast(1f)
        val displayFeatherWidth = ceil(sqrt(displayExpansion.toDouble())).toInt()
        val featherWidth = ceil(displayFeatherWidth * coordinateScale).toInt().coerceAtLeast(1)
        val hardExpansion = ceil(featherWidth / 2f).toInt().coerceAtLeast(1)
        val distanceSquared = BinaryMaskDistanceField.squaredEuclidean(
            width = width,
            height = height,
            sourceMask = baseMask,
        )
        val hardRadiusSquared = hardExpansion * hardExpansion
        val repairRadius = hardExpansion + featherWidth
        val repairRadiusSquared = repairRadius * repairRadius
        val opaqueMask = BooleanArray(baseMask.size)
        val repairMask = BooleanArray(baseMask.size)
        val alpha = IntArray(baseMask.size)
        for (index in distanceSquared.indices) {
            val squaredDistance = distanceSquared[index]
            if (squaredDistance <= hardRadiusSquared) {
                opaqueMask[index] = true
                repairMask[index] = true
                alpha[index] = 255
            } else if (squaredDistance <= repairRadiusSquared) {
                repairMask[index] = true
                val featherLayer = ceil(
                    sqrt(squaredDistance.toDouble()) - hardExpansion
                ).toInt().coerceIn(1, featherWidth)
                alpha[index] = ((featherWidth + 1 - featherLayer) * 255) /
                    (featherWidth + 1)
            }
        }
        return Plan(
            opaqueMask = opaqueMask,
            repairMask = repairMask,
            alpha = alpha,
            existingExpansionPx = existingExpansion,
            hardExpansionPx = hardExpansion,
            featherWidthPx = featherWidth,
        )
    }

    fun applyAlpha(color: Int, maskAlpha: Int): Int {
        require(maskAlpha in 0..255)
        if (maskAlpha == 0) return 0
        val sourceAlpha = color ushr 24 and 0xff
        val outputAlpha = (sourceAlpha * maskAlpha + 127) / 255
        return (outputAlpha shl 24) or (color and 0x00ffffff)
    }

    private fun maximumMaskDistance(
        width: Int,
        height: Int,
        traversableMask: BooleanArray,
        seedMask: BooleanArray,
    ): Int {
        val distances = IntArray(traversableMask.size) { -1 }
        val queue = IntArray(traversableMask.size)
        var head = 0
        var tail = 0
        seedMask.indices.forEach { index ->
            if (seedMask[index]) {
                distances[index] = 0
                queue[tail++] = index
            }
        }
        var maximum = 0
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            val nextDistance = distances[index] + 1
            for (dy in -1..1) {
                val nextY = y + dy
                if (nextY !in 0 until height) continue
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nextX = x + dx
                    if (nextX !in 0 until width) continue
                    val next = nextY * width + nextX
                    if (!traversableMask[next] || distances[next] >= 0) continue
                    distances[next] = nextDistance
                    maximum = maxOf(maximum, nextDistance)
                    queue[tail++] = next
                }
            }
        }
        return maximum
    }

}
