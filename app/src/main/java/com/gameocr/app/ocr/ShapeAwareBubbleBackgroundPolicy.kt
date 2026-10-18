package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

/** Reuses completed glyph repairs without requiring a flat fill of the whole text rectangle. */
internal object ShapeAwareBubbleBackgroundPolicy {
    fun layoutBlockIndicesByModel(
        decisions: List<DelayedTextEraseMaskBuilder.Decision>,
        successfulBlocks: Set<Int>,
        completedTextRepairs: Set<Int>,
    ): Map<Int, Set<Int>> {
        val groups = linkedMapOf<Int, MutableSet<Int>>()
        decisions.forEach { decision ->
            val model = decision.modelBubbleIndices.distinct().singleOrNull() ?: return@forEach
            if (decision.blockIndex !in successfulBlocks) return@forEach
            // A leaking detector component may fail the bubble-mask overlap test even though
            // member ownership is unambiguous and local glyph erasure is complete. That failure
            // must not forbid fitting text inside an independently validated balloon interior.
            if (decision.accepted ||
                (decision.reason == DelayedTextEraseMaskBuilder.Reason.MODEL_MASK_COVERAGE_LOW &&
                    decision.blockIndex in completedTextRepairs)
            ) groups.getOrPut(model) { linkedSetOf() } += decision.blockIndex
        }
        return groups
    }

    fun needsModelRepair(blockIndices: Set<Int>, completedTextRepairs: Set<Int>): Boolean =
        blockIndices.any { it !in completedTextRepairs }

    data class TextRepair(
        val blockIndex: Int,
        val bounds: IntRect,
        val pixels: IntArray?,
        val complete: Boolean,
    )

    fun compose(
        modelMask: BubbleSegmentationPostprocessor.InstanceMask,
        modelBackground: IntArray?,
        blockIndices: Set<Int>,
        textRepairs: Map<Int, TextRepair>,
    ): IntArray? {
        if (modelBackground != null) {
            require(modelBackground.size == modelMask.pixels.size)
            return modelBackground
        }
        if (blockIndices.isEmpty()) return null
        val repairs = blockIndices.map { index ->
            textRepairs[index]?.takeIf { it.complete && it.pixels != null }
                ?: return null
        }
        val output = IntArray(modelMask.pixels.size)
        for (repair in repairs) {
            val pixels = repair.pixels ?: return null
            require(pixels.size == repair.bounds.width * repair.bounds.height)
            val left = maxOf(modelMask.left, repair.bounds.left)
            val top = maxOf(modelMask.top, repair.bounds.top)
            val right = minOf(modelMask.right, repair.bounds.right)
            val bottom = minOf(modelMask.bottom, repair.bounds.bottom)
            for (y in top until bottom) for (x in left until right) {
                val local = (y - modelMask.top) * modelMask.width + x - modelMask.left
                val color = pixels[(y - repair.bounds.top) * repair.bounds.width + x - repair.bounds.left]
                if (modelMask.pixels[local] && color ushr 24 != 0) output[local] = color
            }
        }
        return output.takeIf { pixels -> pixels.any { it ushr 24 != 0 } }
    }

    fun textColor(background: IntArray): Int {
        var luminanceSum = 0.0
        var count = 0
        background.forEach { color ->
            if (color ushr 24 != 0) {
                luminanceSum += (color ushr 16 and 255) * 0.2126 +
                    (color ushr 8 and 255) * 0.7152 + (color and 255) * 0.0722
                count++
            }
        }
        return if (count == 0 || luminanceSum / count >= 255 * 0.55) {
            0xff000000.toInt()
        } else {
            0xffffffff.toInt()
        }
    }
}
