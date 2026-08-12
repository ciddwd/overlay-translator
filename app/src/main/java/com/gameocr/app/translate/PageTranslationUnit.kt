package com.gameocr.app.translate

import android.graphics.Rect
import com.gameocr.app.ocr.TextBlock

/**
 * The canonical translation unit produced by the OCR pipeline.
 *
 * Geometry-based grouping belongs to the OCR router and is controlled exclusively by the user's
 * adjacent-text merge setting and strength. Translation context modes and renderers consume these
 * units without changing their count or membership.
 */
internal data class PageTranslationUnit(
    val blockIndex: Int,
    val sourceText: String,
    val geometry: DialogueGeometry = DialogueGeometry(0, 0, 0, 0),
)

internal data class DialogueGeometry(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val area: Long
        get() = (right - left).coerceAtLeast(0).toLong() *
            (bottom - top).coerceAtLeast(0).toLong()

    fun intersectionArea(other: DialogueGeometry): Long =
        (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0).toLong() *
            (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0).toLong()

    companion object {
        fun from(rect: Rect): DialogueGeometry = DialogueGeometry(
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
        )
    }
}

internal data class PageTranslationRowUpdate(
    val blockIndex: Int,
    val text: String,
)

internal fun planPageTranslationUnits(blocks: List<TextBlock>): List<PageTranslationUnit> =
    blocks.mapIndexed { index, block ->
        PageTranslationUnit(
            blockIndex = index,
            sourceText = block.text,
            geometry = DialogueGeometry.from(block.boundingBox),
        )
    }

/** Maps one translation result back to its unchanged OCR block for every presentation. */
internal fun pageTranslationRowUpdates(
    translatedText: String,
    unit: PageTranslationUnit,
): List<PageTranslationRowUpdate> = listOf(
    PageTranslationRowUpdate(
        blockIndex = unit.blockIndex,
        text = translatedText,
    )
)
