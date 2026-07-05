package com.gameocr.app.ocr

import android.graphics.Rect
import com.gameocr.app.ocr.BubbleClusterer.IntRect

internal fun mapIntRectFromRotated180(rect: IntRect, imageW: Int, imageH: Int): IntRect =
    IntRect(
        left = (imageW - rect.right).coerceIn(0, imageW),
        top = (imageH - rect.bottom).coerceIn(0, imageH),
        right = (imageW - rect.left).coerceIn(0, imageW),
        bottom = (imageH - rect.top).coerceIn(0, imageH),
    )

internal fun mapRectFromRotated180(rect: Rect, imageW: Int, imageH: Int): Rect {
    val mapped = mapIntRectFromRotated180(
        IntRect(rect.left, rect.top, rect.right, rect.bottom),
        imageW,
        imageH,
    )
    return Rect(mapped.left, mapped.top, mapped.right, mapped.bottom)
}

internal fun mapBlocksFromRotated180(blocks: List<TextBlock>, imageW: Int, imageH: Int): List<TextBlock> =
    blocks.map { block ->
        block.copy(boundingBox = mapRectFromRotated180(block.boundingBox, imageW, imageH))
    }
