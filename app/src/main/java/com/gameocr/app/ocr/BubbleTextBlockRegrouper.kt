package com.gameocr.app.ocr

import android.graphics.Rect
import com.gameocr.app.ocr.BubbleClusterer.IntRect

/**
 * Turns detector-assigned OCR lines into one translation unit per speech bubble.
 *
 * Detector bounds are intentionally not used as render bounds. The output keeps the union of the
 * original text-line boxes so a rejected shape mask cannot erase the whole detector rectangle.
 */
internal object BubbleTextBlockRegrouper {

    fun regroup(
        blocks: List<TextBlock>,
        bubbleByBlock: List<Int?>,
    ): List<TextBlock> {
        require(blocks.size == bubbleByBlock.size)
        if (blocks.isEmpty()) return emptyList()

        val matchedByBubble = linkedMapOf<Int, MutableList<TextBlock>>()
        val unmatched = mutableListOf<TextBlock>()
        blocks.forEachIndexed { index, block ->
            val bubbleId = bubbleByBlock[index]
            if (bubbleId == null) {
                unmatched += block.withSourceBoxFallback()
            } else {
                matchedByBubble.getOrPut(bubbleId) { mutableListOf() } += block
            }
        }

        val grouped = matchedByBubble.map { (bubbleId, members) ->
            mergeBubbleMembers(bubbleId, members)
        }
        return (grouped + unmatched).sortedWith(
            compareBy(
                { it.boundingBox.top },
                { it.boundingBox.left },
            )
        )
    }

    private fun mergeBubbleMembers(
        bubbleId: Int,
        members: List<TextBlock>,
    ): TextBlock {
        val sourceBoxes = members.flatMap { it.sourceBoxesOrBoundingBox() }
        val bounds = Rect(
            sourceBoxes.minOf { it.left },
            sourceBoxes.minOf { it.top },
            sourceBoxes.maxOf { it.right },
            sourceBoxes.maxOf { it.bottom },
        )
        val orientation = inferSourceLayoutOrientation(
            sourceBoxes = sourceBoxes.map { box ->
                IntRect(box.left, box.top, box.right, box.bottom)
            },
            blockBounds = IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            ambiguousFallback = TextOrientation.HORIZONTAL_LTR,
        )
        val ordered = when (orientation) {
            TextOrientation.VERTICAL_RTL -> members.sortedWith(
                compareByDescending<TextBlock> { it.boundingBox.centerX() }
                    .thenBy { it.boundingBox.top }
            )
            else -> members.sortedWith(
                compareBy(
                    { it.boundingBox.top },
                    { it.boundingBox.left },
                )
            )
        }
        val first = ordered.first()
        return first.copy(
            text = ordered.map(TextBlock::text).filter(String::isNotBlank).joinToString("\n"),
            boundingBox = bounds,
            confidence = ordered.minOf(TextBlock::confidence),
            layoutOrientation = orientation,
            sourceBoxes = sourceBoxes.map(::Rect),
            bubbleGroupId = bubbleId,
        )
    }

    private fun TextBlock.withSourceBoxFallback(): TextBlock =
        if (sourceBoxes.isEmpty()) {
            copy(sourceBoxes = listOf(Rect(boundingBox)))
        } else {
            this
        }
}
