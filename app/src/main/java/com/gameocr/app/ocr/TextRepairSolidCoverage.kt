package com.gameocr.app.ocr

import kotlin.math.ceil
import kotlin.math.sqrt

/** Builds an opaque, coverage-first mask for local text-background repair patches. */
internal object TextRepairSolidCoverage {

    data class Plan(
        val repairMask: BooleanArray,
        val existingExpansionPx: Int,
        val solidExpansionPx: Int,
    ) {
        init {
            require(existingExpansionPx >= 0)
            require(solidExpansionPx > 0)
        }

        val repairPixelCount: Int = repairMask.count { it }
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

        // Preserve the previous opaque coverage radius. Only the outer translucent band is removed.
        val existingExpansion = maximumMaskDistance(
            width = width,
            height = height,
            traversableMask = baseMask,
            seedMask = coreMask,
        )
        val displayExpansion = (existingExpansion / coordinateScale).coerceAtLeast(1f)
        val solidExpansion = ceil(sqrt(displayExpansion.toDouble()))
            .toInt()
            .let { ceil(it * coordinateScale).toInt() }
            .coerceAtLeast(1)
        return Plan(
            repairMask = dilateEuclidean(
                width = width,
                height = height,
                sourceMask = baseMask,
                radius = solidExpansion,
            ),
            existingExpansionPx = existingExpansion,
            solidExpansionPx = solidExpansion,
        )
    }

    private fun dilateEuclidean(
        width: Int,
        height: Int,
        sourceMask: BooleanArray,
        radius: Int,
    ): BooleanArray {
        val output = sourceMask.copyOf()
        val radiusSquared = radius * radius
        for (index in sourceMask.indices) {
            if (!sourceMask[index]) continue
            val x = index % width
            val y = index / width
            for (dy in -radius..radius) {
                val targetY = y + dy
                if (targetY !in 0 until height) continue
                for (dx in -radius..radius) {
                    if (dx * dx + dy * dy > radiusSquared) continue
                    val targetX = x + dx
                    if (targetX in 0 until width) output[targetY * width + targetX] = true
                }
            }
        }
        return output
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
