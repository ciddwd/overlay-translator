package com.gameocr.app.capture

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.gameocr.app.data.CaptureContentOrientation
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import com.gameocr.app.ocr.ShapeAwareBubblePatch
import com.gameocr.app.ocr.TextBlock

/**
 * Resolves and applies a user-requested orientation for content embedded in a capture.
 *
 * AUTO is deliberately a no-op: genuine vertical manga text must not be mistaken for a sideways
 * game. Forced modes only act on an aspect-ratio mismatch, and the bundled document-orientation
 * model selects the direction of the quarter turn.
 */
internal object CaptureContentOrientationPolicy {
    /** Right-angle turns are exact pixel permutations, including the alpha/erase mask. */
    fun canUseOcrSpacePixelMask(counterClockwiseDegrees: Int): Boolean =
        normalizeAngle(counterClockwiseDegrees) % 90 == 0

    fun resolveRotationDegrees(
        requested: CaptureContentOrientation,
        imageWidth: Int,
        imageHeight: Int,
        modelAngle: Int,
    ): Int {
        if (requested == CaptureContentOrientation.AUTO || imageWidth <= 0 || imageHeight <= 0) return 0
        val isLandscape = imageWidth >= imageHeight
        val targetLandscape = requested == CaptureContentOrientation.LANDSCAPE
        if (isLandscape == targetLandscape) return 0

        return when (normalizeAngle(modelAngle)) {
            90 -> 90
            270 -> 270
            else -> 0
        }
    }

    fun rotateForOcr(bitmap: Bitmap, counterClockwiseDegrees: Int): Bitmap {
        val angle = normalizeAngle(counterClockwiseDegrees)
        if (angle == 0) return bitmap
        // Android's positive Matrix angle follows screen coordinates (clockwise). Paddle's label
        // is the counter-clockwise correction angle, hence the sign inversion.
        val matrix = Matrix().apply { postRotate(-angle.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun mapBlocksToOriginal(
        blocks: List<TextBlock>,
        originalWidth: Int,
        originalHeight: Int,
        counterClockwiseDegrees: Int,
    ): List<TextBlock> {
        val angle = normalizeAngle(counterClockwiseDegrees)
        if (angle == 0) return blocks
        return blocks.map { block ->
            block.copy(
                boundingBox = mapRectToOriginal(
                    block.boundingBox,
                    originalWidth,
                    originalHeight,
                    angle,
                ),
                sourceBoxes = block.sourceBoxes.map { box ->
                    mapRectToOriginal(box, originalWidth, originalHeight, angle)
                },
                presentationRotationDegrees = angle,
            )
        }
    }

    /**
     * Maps display-space blocks back into the bitmap space used by OCR. This is intentionally the
     * inverse of [mapBlocksToOriginal]: masks are built from the OCR bitmap and must never be
     * claimed with display-space coordinates.
     */
    fun mapBlocksToOcr(
        blocks: List<TextBlock>,
        originalWidth: Int,
        originalHeight: Int,
        counterClockwiseDegrees: Int,
    ): List<TextBlock> {
        val angle = normalizeAngle(counterClockwiseDegrees)
        if (angle == 0) return blocks
        return blocks.map { block ->
            block.copy(
                boundingBox = mapRectToOcr(
                    block.boundingBox,
                    originalWidth,
                    originalHeight,
                    angle,
                ),
                sourceBoxes = block.sourceBoxes.map { box ->
                    mapRectToOcr(box, originalWidth, originalHeight, angle)
                },
                presentationRotationDegrees = 0,
            )
        }
    }

    /**
     * Restores a repaired/translated patch produced in OCR bitmap coordinates to the untouched
     * capture coordinate system. Pixels are only rotated; mask coverage and colors are preserved.
     */
    fun mapPatchesToOriginal(
        patches: List<ShapeAwareBubblePatch>,
        originalImageWidth: Int,
        originalImageHeight: Int,
        counterClockwiseDegrees: Int,
    ): List<ShapeAwareBubblePatch> {
        val angle = normalizeAngle(counterClockwiseDegrees)
        require(originalImageWidth > 0 && originalImageHeight > 0)
        require(canUseOcrSpacePixelMask(angle))
        if (angle == 0 && patches.all {
                it.bounds.left >= 0 && it.bounds.top >= 0 &&
                    it.bounds.right <= originalImageWidth && it.bounds.bottom <= originalImageHeight
            }
        ) return patches
        return patches.mapNotNull { patch ->
            val mappedBounds = mapIntRectToOriginal(
                rect = patch.bounds,
                originalWidth = originalImageWidth,
                originalHeight = originalImageHeight,
                counterClockwiseDegrees = angle,
            )
            if (mappedBounds.width <= 0 || mappedBounds.height <= 0) return@mapNotNull null
            // Pull each visible destination pixel from its OCR coordinate. Clipping only the
            // rectangle would leave the old pixel stride, shifting the mask or crashing creation.
            val pixels = IntArray(mappedBounds.width * mappedBounds.height)
            for (y in 0 until mappedBounds.height) for (x in 0 until mappedBounds.width) {
                val originalX = mappedBounds.left + x
                val originalY = mappedBounds.top + y
                val sourceX = when (angle) {
                    90 -> originalY
                    180 -> originalImageWidth - 1 - originalX
                    270 -> originalImageHeight - 1 - originalY
                    else -> originalX
                } - patch.bounds.left
                val sourceY = when (angle) {
                    90 -> originalImageWidth - 1 - originalX
                    180 -> originalImageHeight - 1 - originalY
                    270 -> originalX
                    else -> originalY
                } - patch.bounds.top
                pixels[y * mappedBounds.width + x] = patch.pixels[sourceY * patch.bounds.width + sourceX]
            }
            patch.copy(
                bounds = mappedBounds,
                pixels = pixels,
            )
        }
    }

    fun rotatedWidth(originalWidth: Int, originalHeight: Int, counterClockwiseDegrees: Int): Int =
        if (normalizeAngle(counterClockwiseDegrees) in setOf(90, 270)) originalHeight else originalWidth

    fun rotatedHeight(originalWidth: Int, originalHeight: Int, counterClockwiseDegrees: Int): Int =
        if (normalizeAngle(counterClockwiseDegrees) in setOf(90, 270)) originalWidth else originalHeight

    internal fun mapRectToOriginal(
        rect: Rect,
        originalWidth: Int,
        originalHeight: Int,
        counterClockwiseDegrees: Int,
    ): Rect {
        val angle = normalizeAngle(counterClockwiseDegrees)
        val mapped = when (angle) {
            90 -> Rect(
                originalWidth - rect.bottom,
                rect.left,
                originalWidth - rect.top,
                rect.right,
            )
            180 -> Rect(
                originalWidth - rect.right,
                originalHeight - rect.bottom,
                originalWidth - rect.left,
                originalHeight - rect.top,
            )
            270 -> Rect(
                rect.top,
                originalHeight - rect.right,
                rect.bottom,
                originalHeight - rect.left,
            )
            else -> Rect(rect)
        }
        mapped.left = mapped.left.coerceIn(0, originalWidth)
        mapped.right = mapped.right.coerceIn(0, originalWidth)
        mapped.top = mapped.top.coerceIn(0, originalHeight)
        mapped.bottom = mapped.bottom.coerceIn(0, originalHeight)
        if (mapped.left > mapped.right) mapped.sort()
        return mapped
    }

    internal fun mapRectToOcr(
        rect: Rect,
        originalWidth: Int,
        originalHeight: Int,
        counterClockwiseDegrees: Int,
    ): Rect {
        val angle = normalizeAngle(counterClockwiseDegrees)
        val rotatedWidth = rotatedWidth(originalWidth, originalHeight, angle)
        val rotatedHeight = rotatedHeight(originalWidth, originalHeight, angle)
        val mapped = when (angle) {
            90 -> Rect(
                rect.top,
                originalWidth - rect.right,
                rect.bottom,
                originalWidth - rect.left,
            )
            180 -> Rect(
                originalWidth - rect.right,
                originalHeight - rect.bottom,
                originalWidth - rect.left,
                originalHeight - rect.top,
            )
            270 -> Rect(
                originalHeight - rect.bottom,
                rect.left,
                originalHeight - rect.top,
                rect.right,
            )
            else -> Rect(rect)
        }
        mapped.left = mapped.left.coerceIn(0, rotatedWidth)
        mapped.right = mapped.right.coerceIn(0, rotatedWidth)
        mapped.top = mapped.top.coerceIn(0, rotatedHeight)
        mapped.bottom = mapped.bottom.coerceIn(0, rotatedHeight)
        if (mapped.left > mapped.right || mapped.top > mapped.bottom) mapped.sort()
        return mapped
    }

    private fun mapIntRectToOriginal(
        rect: IntRect,
        originalWidth: Int,
        originalHeight: Int,
        counterClockwiseDegrees: Int,
    ): IntRect {
        val mapped = when (normalizeAngle(counterClockwiseDegrees)) {
            90 -> IntRect(
                originalWidth - rect.bottom,
                rect.left,
                originalWidth - rect.top,
                rect.right,
            )
            180 -> IntRect(
                originalWidth - rect.right,
                originalHeight - rect.bottom,
                originalWidth - rect.left,
                originalHeight - rect.top,
            )
            270 -> IntRect(
                rect.top,
                originalHeight - rect.right,
                rect.bottom,
                originalHeight - rect.left,
            )
            else -> rect
        }
        val left = minOf(mapped.left, mapped.right).coerceIn(0, originalWidth)
        val right = maxOf(mapped.left, mapped.right).coerceIn(0, originalWidth)
        val top = minOf(mapped.top, mapped.bottom).coerceIn(0, originalHeight)
        val bottom = maxOf(mapped.top, mapped.bottom).coerceIn(0, originalHeight)
        return IntRect(left, top, right, bottom)
    }

    private fun normalizeAngle(angle: Int): Int = ((angle % 360) + 360) % 360
}
