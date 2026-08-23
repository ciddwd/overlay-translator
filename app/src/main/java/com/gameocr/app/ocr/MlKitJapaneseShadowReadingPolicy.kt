package com.gameocr.app.ocr

import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Restores shadow-recognition boxes to the upright crop coordinate system before ordering them.
 *
 * Rotating a crop can help ML Kit recognize Japanese glyphs, but the recognizer's list order is
 * not a reading-order contract. All variants therefore return to one coordinate system and use
 * the existing Japanese vertical reading-order policy: rightmost column first, top to bottom
 * inside a column.
 */
internal object MlKitJapaneseShadowReadingPolicy {
    fun orderVerticalRtl(
        blocks: List<TextBlock>,
        uprightWidth: Int,
        uprightHeight: Int,
        rotationDegrees: Int,
    ): List<TextBlock> {
        require(uprightWidth > 0 && uprightHeight > 0)
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == -90)
        val uprightBlocks = blocks.map { block ->
            block.copy(
                boundingBox = block.boundingBox.toUpright(
                    uprightWidth = uprightWidth,
                    uprightHeight = uprightHeight,
                    rotationDegrees = rotationDegrees,
                ),
                sourceBoxes = block.sourceBoxes.map { source ->
                    source.toUpright(
                        uprightWidth = uprightWidth,
                        uprightHeight = uprightHeight,
                        rotationDegrees = rotationDegrees,
                    )
                },
                layoutOrientation = TextOrientation.VERTICAL_RTL,
            )
        }
        return sortTextBlocksForReading(
            blocks = uprightBlocks,
            orientationHint = TextOrientation.VERTICAL_RTL,
        )
    }

    fun mapRectToUpright(
        rect: MlKitGeometryRect,
        uprightWidth: Int,
        uprightHeight: Int,
        rotationDegrees: Int,
    ): MlKitGeometryRect {
        require(uprightWidth > 0 && uprightHeight > 0)
        val mapped = when (rotationDegrees) {
            0 -> rect
            90 -> MlKitGeometryRect(
                left = rect.top,
                top = uprightHeight - rect.right,
                right = rect.bottom,
                bottom = uprightHeight - rect.left,
            )
            -90 -> MlKitGeometryRect(
                left = uprightWidth - rect.bottom,
                top = rect.left,
                right = uprightWidth - rect.top,
                bottom = rect.right,
            )
            else -> error("Unsupported rotation: $rotationDegrees")
        }
        return MlKitGeometryRect(
            left = mapped.left.coerceIn(0, uprightWidth),
            top = mapped.top.coerceIn(0, uprightHeight),
            right = mapped.right.coerceIn(0, uprightWidth),
            bottom = mapped.bottom.coerceIn(0, uprightHeight),
        )
    }

    private fun Rect.toUpright(
        uprightWidth: Int,
        uprightHeight: Int,
        rotationDegrees: Int,
    ): Rect {
        val mapped = mapRectToUpright(
            rect = MlKitGeometryRect(left, top, right, bottom),
            uprightWidth = uprightWidth,
            uprightHeight = uprightHeight,
            rotationDegrees = rotationDegrees,
        )
        return Rect().apply {
            left = mapped.left
            top = mapped.top
            right = mapped.right
            bottom = mapped.bottom
        }
    }
}

internal data class MlKitJapaneseShadowRepair(
    val sourceLineIndices: List<Int>,
    val replacement: TextBlock,
    val score: Float,
)

internal object MlKitJapaneseShadowRepairPolicy {
    fun apply(
        firstPass: List<TextBlock>,
        repairs: List<MlKitJapaneseShadowRepair>,
    ): List<TextBlock> {
        if (repairs.isEmpty()) return firstPass
        val accepted = mutableListOf<MlKitJapaneseShadowRepair>()
        val claimedIndices = mutableSetOf<Int>()
        repairs.sortedByDescending(MlKitJapaneseShadowRepair::score).forEach { repair ->
            val validIndices = repair.sourceLineIndices.filter(firstPass.indices::contains)
            if (validIndices.isNotEmpty() && validIndices.none(claimedIndices::contains)) {
                accepted += repair.copy(sourceLineIndices = validIndices.distinct().sorted())
                claimedIndices += validIndices
            }
        }
        val replacementByFirstIndex = accepted.associateBy { repair ->
            repair.sourceLineIndices.min()
        }
        return buildList {
            firstPass.indices.forEach { index ->
                replacementByFirstIndex[index]?.let { repair -> add(repair.replacement) }
                if (index !in claimedIndices) add(firstPass[index])
            }
        }
    }

    fun mapNormalizedRectToSource(
        rect: MlKitGeometryRect,
        sourceBounds: MlKitGeometryRect,
        normalizedWidth: Int,
        normalizedHeight: Int,
    ): MlKitGeometryRect {
        require(normalizedWidth > 0 && normalizedHeight > 0)
        val clipped = MlKitGeometryRect(
            left = rect.left.coerceIn(0, normalizedWidth),
            top = rect.top.coerceIn(0, normalizedHeight),
            right = rect.right.coerceIn(0, normalizedWidth),
            bottom = rect.bottom.coerceIn(0, normalizedHeight),
        )
        val scaleX = sourceBounds.width.toFloat() / normalizedWidth
        val scaleY = sourceBounds.height.toFloat() / normalizedHeight
        return MlKitGeometryRect(
            left = sourceBounds.left + (clipped.left * scaleX).roundToInt(),
            top = sourceBounds.top + (clipped.top * scaleY).roundToInt(),
            right = sourceBounds.left + (clipped.right * scaleX).roundToInt(),
            bottom = sourceBounds.top + (clipped.bottom * scaleY).roundToInt(),
        )
    }
}
