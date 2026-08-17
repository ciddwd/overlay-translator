package com.gameocr.app.translate

import android.graphics.Rect
import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.RenderMode
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
    val blockIndexes: List<Int> = listOf(blockIndex),
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

internal fun planPageTranslationUnits(
    blocks: List<TextBlock>,
    presentation: RenderMode = RenderMode.BLOCKS,
    mergeAdjacentBlocks: Boolean = false,
    mergeStrength: MergeStrength = MergeStrength.STANDARD,
): List<PageTranslationUnit> {
    val units = blocks.mapIndexed { index, block ->
        PageTranslationUnit(
            blockIndex = index,
            sourceText = PageTranslationPresentationTextPolicy.normalize(presentation, block.text),
            geometry = DialogueGeometry.from(block.boundingBox),
        )
    }
    if (!PageTranslationGroupingPolicy.shouldMergeAll(
            presentation = presentation,
            mergeAdjacentBlocks = mergeAdjacentBlocks,
            mergeStrength = mergeStrength,
        )
    ) return units

    val members = units.filter { it.sourceText.isNotBlank() }
    if (members.isEmpty()) return emptyList()
    return listOf(
        PageTranslationUnit(
            // The floating window is prepared from this new one-row list, so its only row is 0.
            blockIndex = 0,
            sourceText = members.joinToString(" ") { it.sourceText.trim() },
            geometry = DialogueGeometry(
                left = members.minOf { it.geometry.left },
                top = members.minOf { it.geometry.top },
                right = members.maxOf { it.geometry.right },
                bottom = members.maxOf { it.geometry.bottom },
            ),
            blockIndexes = members.flatMap(PageTranslationUnit::blockIndexes),
        )
    )
}

/** Keeps the distance-independent grouping mode strictly inside the floating presentation. */
internal object PageTranslationGroupingPolicy {
    fun shouldMergeAll(
        presentation: RenderMode,
        mergeAdjacentBlocks: Boolean,
        mergeStrength: MergeStrength,
    ): Boolean =
        presentation == RenderMode.FLOATING_WINDOW &&
            mergeAdjacentBlocks &&
            mergeStrength == MergeStrength.ALL
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
