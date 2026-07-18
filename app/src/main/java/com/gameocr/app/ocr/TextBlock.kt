package com.gameocr.app.ocr

import android.graphics.Rect
import com.gameocr.app.ocr.BubbleClusterer.IntRect

/**
 * 一段识别出的文本（一般对应原图里的一个文本块）。
 * [boundingBox] 用屏幕坐标系，方便 overlay 直接贴在原文下方。
 */
data class TextBlock(
    val text: String,
    val boundingBox: Rect,
    val confidence: Float = 1f,
    val recognizedLanguage: String? = null,
    val layoutOrientation: TextOrientation? = null,
    val sourceBoxes: List<Rect> = emptyList(),
)

internal fun TextBlock.sourceBoxesOrBoundingBox(): List<Rect> =
    sourceBoxes.takeIf { it.isNotEmpty() }?.map(::Rect) ?: listOf(Rect(boundingBox))

internal fun mergeSourceBoxes(first: TextBlock, second: TextBlock): List<Rect> =
    first.sourceBoxesOrBoundingBox() + second.sourceBoxesOrBoundingBox()

internal fun inferSourceLayoutOrientation(
    sourceBoxes: List<IntRect>,
    blockBounds: IntRect,
    ambiguousFallback: TextOrientation = TextOrientation.VERTICAL_RTL,
): TextOrientation {
    var portrait = 0
    var landscape = 0
    sourceBoxes.forEach { box ->
        val width = box.width.coerceAtLeast(1)
        val height = box.height.coerceAtLeast(1)
        when {
            height >= width * 1.2f -> portrait++
            width >= height * 1.2f -> landscape++
        }
    }
    return when {
        portrait > landscape -> TextOrientation.VERTICAL_RTL
        landscape > portrait -> TextOrientation.HORIZONTAL_LTR
        blockBounds.width >= blockBounds.height * 1.3f -> TextOrientation.HORIZONTAL_LTR
        blockBounds.height >= blockBounds.width * 1.3f -> TextOrientation.VERTICAL_RTL
        else -> ambiguousFallback
    }
}

internal fun TextBlock.withFallbackLayoutOrientation(
    fallback: TextOrientation,
): TextBlock =
    if (layoutOrientation == null || layoutOrientation == TextOrientation.UNKNOWN) {
        copy(layoutOrientation = fallback)
    } else {
        this
    }
