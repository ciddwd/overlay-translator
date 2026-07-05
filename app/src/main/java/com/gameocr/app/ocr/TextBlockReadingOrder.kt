package com.gameocr.app.ocr

internal fun sortTextBlocksForReading(
    blocks: List<TextBlock>,
    orientationHint: TextOrientation? = null
): List<TextBlock> {
    if (blocks.size <= 1) return blocks
    val orientation = when {
        orientationHint == TextOrientation.VERTICAL_RTL ||
            orientationHint == TextOrientation.VERTICAL_LTR -> orientationHint
        dominantLayoutOrientation(blocks.map { it.layoutOrientation }) == TextOrientation.VERTICAL_RTL ->
            TextOrientation.VERTICAL_RTL
        dominantLayoutOrientation(blocks.map { it.layoutOrientation }) == TextOrientation.VERTICAL_LTR ->
            TextOrientation.VERTICAL_LTR
        inferVerticalByShape(blocks) -> TextOrientation.VERTICAL_RTL
        else -> TextOrientation.HORIZONTAL_LTR
    }
    return if (orientation == TextOrientation.VERTICAL_RTL || orientation == TextOrientation.VERTICAL_LTR) {
        sortVertical(blocks, leftToRight = orientation == TextOrientation.VERTICAL_LTR)
    } else {
        sortHorizontal(blocks)
    }
}

private fun sortHorizontal(blocks: List<TextBlock>): List<TextBlock> {
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
        .flatMap { line -> line.sortedBy { it.boundingBox.left } }
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
