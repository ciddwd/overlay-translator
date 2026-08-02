package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A local, transparent overlay patch in the segmentation image coordinate system.
 *
 * Depending on [role], non-transparent pixels contain either a complete translated bubble or only
 * confirmed background repairs. The rest stays transparent so unrelated screen content is live.
 */
internal data class ShapeAwareBubblePatch(
    val modelBubbleIndex: Int?,
    val bounds: IntRect,
    val pixels: IntArray,
    val coordinateScale: Float,
    val blockIndices: List<Int>,
    val role: Role = Role.SHAPE_TRANSLATION,
) {
    enum class Role {
        SHAPE_TRANSLATION,
        TEXT_BACKGROUND,
    }

    init {
        require(bounds.width > 0 && bounds.height > 0)
        require(pixels.size == bounds.width * bounds.height)
        require(coordinateScale > 0f)
        require(blockIndices.isNotEmpty())
        if (role == Role.SHAPE_TRANSLATION) requireNotNull(modelBubbleIndex)
    }

    val replacesBlockViews: Boolean
        get() = role == Role.SHAPE_TRANSLATION

    fun displayBounds(): IntRect = scaledPatchBounds(
        bounds = bounds,
        coordinateScale = coordinateScale,
    )
}

internal object ShapeAwareBubblePatchComposer {

    /**
     * Copies only confirmed repair pixels inside the model mask into a transparent local image.
     */
    fun composeBackground(
        imageWidth: Int,
        imageHeight: Int,
        repairedPixels: IntArray,
        repairedMask: BooleanArray,
        modelMask: BubbleSegmentationPostprocessor.InstanceMask,
    ): IntArray {
        require(imageWidth > 0 && imageHeight > 0)
        require(repairedPixels.size == imageWidth * imageHeight)
        require(repairedMask.size == imageWidth * imageHeight)
        require(modelMask.pixels.size == modelMask.width * modelMask.height)

        val output = IntArray(modelMask.width * modelMask.height)
        for (localY in 0 until modelMask.height) {
            val imageY = modelMask.top + localY
            if (imageY !in 0 until imageHeight) continue
            for (localX in 0 until modelMask.width) {
                val localIndex = localY * modelMask.width + localX
                if (!modelMask.pixels[localIndex]) continue
                val imageX = modelMask.left + localX
                if (imageX !in 0 until imageWidth) continue
                val imageIndex = imageY * imageWidth + imageX
                if (repairedMask[imageIndex]) {
                    output[localIndex] = repairedPixels[imageIndex]
                }
            }
        }
        return output
    }
}

internal fun scaledPatchBounds(
    bounds: IntRect,
    coordinateScale: Float,
): IntRect {
    require(bounds.width > 0 && bounds.height > 0)
    require(coordinateScale > 0f)
    val left = floor(bounds.left / coordinateScale).toInt()
    val top = floor(bounds.top / coordinateScale).toInt()
    val right = ceil(bounds.right / coordinateScale).toInt().coerceAtLeast(left + 1)
    val bottom = ceil(bounds.bottom / coordinateScale).toInt().coerceAtLeast(top + 1)
    return IntRect(left, top, right, bottom)
}
