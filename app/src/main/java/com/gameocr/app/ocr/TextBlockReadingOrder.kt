package com.gameocr.app.ocr

import kotlin.math.min
import kotlin.math.roundToInt

internal fun sortTextBlocksForReading(
    blocks: List<TextBlock>,
    orientationHint: TextOrientation? = null
): List<TextBlock> {
    if (blocks.size <= 1) return blocks
    val orientation = resolveTextBlockReadingOrientation(blocks, orientationHint)
    return when (orientation) {
        TextOrientation.VERTICAL_RTL -> if (blocks.hasMangaRegionSemantics()) {
            sortMangaVerticalRtl(blocks)
        } else {
            sortVertical(blocks, leftToRight = false)
        }
        TextOrientation.VERTICAL_LTR -> sortVertical(blocks, leftToRight = true)
        TextOrientation.HORIZONTAL_RTL -> sortHorizontal(blocks, leftToRight = false)
        else -> sortHorizontal(blocks, leftToRight = true)
    }
}

/**
 * Manga pages commonly place right-to-left panels in horizontal tiers. A global X sort can
 * therefore consume a lower-right panel before the upper-left panel. Infer only unambiguous
 * horizontal whitespace tiers, then preserve the existing vertical RTL order inside each tier.
 * If a tall region bridges the whitespace, the page remains one tier and keeps the legacy order.
 */
private fun sortMangaVerticalRtl(blocks: List<TextBlock>): List<TextBlock> =
    splitMangaHorizontalBands(blocks).flatMap { band ->
        sortVertical(band, leftToRight = false)
    }

internal fun splitMangaHorizontalBands(blocks: List<TextBlock>): List<List<TextBlock>> {
    if (blocks.size <= 1) return listOf(blocks)
    val gapThreshold = mangaHorizontalBandGapThresholdPx(blocks)
    val sorted = blocks.sortedWith(
        compareBy<TextBlock>({ it.boundingBox.top }, { it.boundingBox.left })
    )
    val bands = mutableListOf<MutableList<TextBlock>>()
    var current = mutableListOf(sorted.first())
    var currentBottom = sorted.first().boundingBox.bottom
    sorted.drop(1).forEach { block ->
        val gap = block.boundingBox.top - currentBottom
        if (gap >= gapThreshold) {
            bands += current
            current = mutableListOf(block)
            currentBottom = block.boundingBox.bottom
        } else {
            current += block
            currentBottom = maxOf(currentBottom, block.boundingBox.bottom)
        }
    }
    bands += current
    return bands
}

internal fun mangaHorizontalBandGapThresholdPx(blocks: List<TextBlock>): Int {
    val sourceThicknesses = blocks.flatMap { block ->
        val sources = block.sourceBoxes.takeIf { it.isNotEmpty() }
            ?: listOf(block.boundingBox)
        sources.map { source ->
            min(source.rectWidth(), source.rectHeight()).coerceAtLeast(1)
        }
    }.sorted()
    if (sourceThicknesses.isEmpty()) return 1
    val middle = sourceThicknesses.size / 2
    val median = if (sourceThicknesses.size % 2 == 0) {
        (sourceThicknesses[middle - 1] + sourceThicknesses[middle]) / 2f
    } else {
        sourceThicknesses[middle].toFloat()
    }
    return (median * 0.5f).roundToInt().coerceAtLeast(1)
}

private fun List<TextBlock>.hasMangaRegionSemantics(): Boolean = any { block ->
    block.regionGranularity == TextRegionGranularity.BUBBLE ||
        block.regionGranularity == TextRegionGranularity.FREE_TEXT
}

internal fun resolveTextBlockReadingOrientation(
    blocks: List<TextBlock>,
    orientationHint: TextOrientation? = null,
): TextOrientation {
    val layoutOrientation = dominantLayoutOrientation(blocks.map { it.layoutOrientation })
    return when {
        orientationHint != null && orientationHint != TextOrientation.UNKNOWN -> orientationHint
        layoutOrientation != null -> layoutOrientation
        inferVerticalByShape(blocks) -> TextOrientation.VERTICAL_RTL
        else -> TextOrientation.HORIZONTAL_LTR
    }
}

private fun sortHorizontal(blocks: List<TextBlock>, leftToRight: Boolean): List<TextBlock> {
    val avgHeight = blocks.map { it.boundingBox.rectHeight().coerceAtLeast(1) }.average().toFloat()
    val sameLineThreshold = (avgHeight * 0.65f).coerceAtLeast(8f)
    val lines = mutableListOf<MutableList<TextBlock>>()
    for (block in blocks.sortedWith(compareBy({ it.boundingBox.rectCenterY() }, { it.boundingBox.left }))) {
        val line = lines.firstOrNull { existing ->
            kotlin.math.abs(existing.centerY() - block.boundingBox.rectCenterY()) <= sameLineThreshold
        }
        if (line == null) {
            lines += mutableListOf(block)
        } else {
            line += block
        }
    }
    return lines
        .sortedBy { it.minOf { block -> block.boundingBox.top } }
        .flatMap { line ->
            if (leftToRight) line.sortedBy { it.boundingBox.left }
            else line.sortedByDescending { it.boundingBox.right }
        }
}

private fun sortVertical(
    blocks: List<TextBlock>,
    leftToRight: Boolean
): List<TextBlock> {
    val avgWidth = blocks.map { it.boundingBox.rectWidth().coerceAtLeast(1) }.average().toFloat()
    val sameColumnThreshold = (avgWidth * 0.75f).coerceAtLeast(8f)
    val columns = mutableListOf<MutableList<TextBlock>>()
    val byColumn = if (leftToRight) {
        blocks.sortedWith(compareBy({ it.boundingBox.rectCenterX() }, { it.boundingBox.top }))
    } else {
        blocks.sortedWith(compareByDescending<TextBlock> { it.boundingBox.rectCenterX() }.thenBy { it.boundingBox.top })
    }
    for (block in byColumn) {
        val column = columns.firstOrNull { existing ->
            kotlin.math.abs(existing.centerX() - block.boundingBox.rectCenterX()) <= sameColumnThreshold
        }
        if (column == null) {
            columns += mutableListOf(block)
        } else {
            column += block
        }
    }
    val sortedColumns = if (leftToRight) {
        columns.sortedBy { it.centerX() }
    } else {
        columns.sortedByDescending { it.centerX() }
    }
    return sortedColumns.flatMap { column -> column.sortedBy { it.boundingBox.top } }
}

private fun inferVerticalByShape(blocks: List<TextBlock>): Boolean {
    val portrait = blocks.count { block ->
        val r = block.boundingBox
        r.rectHeight() > r.rectWidth() * 1.3f
    }
    val landscape = blocks.count { block ->
        val r = block.boundingBox
        r.rectWidth() > r.rectHeight() * 1.3f
    }
    return portrait > landscape && portrait.toFloat() / blocks.size >= 0.5f
}

private fun List<TextBlock>.centerY(): Float =
    map { it.boundingBox.rectCenterY() }.average().toFloat()

private fun List<TextBlock>.centerX(): Float =
    map { it.boundingBox.rectCenterX() }.average().toFloat()

private fun android.graphics.Rect.rectWidth(): Int = right - left

private fun android.graphics.Rect.rectHeight(): Int = bottom - top

private fun android.graphics.Rect.rectCenterX(): Int = (left + right) / 2

private fun android.graphics.Rect.rectCenterY(): Int = (top + bottom) / 2
