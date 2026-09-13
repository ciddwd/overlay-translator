package com.gameocr.app.ocr

/** Group immutable horizontal boxes first, concatenate in reading order only afterwards. */
internal fun groupHorizontalLineRects(
    rects: List<MergeDebugRect>,
    bubbleGroupIds: List<Int?>,
    alignmentTolerance: Float,
    adjacentGapRatio: Float,
    heightRatioLimit: Float,
): List<List<Int>> {
    require(rects.size == bubbleGroupIds.size)
    val minOverlap = maxOf(0.5f, 1f - alignmentTolerance)
    fun aligned(a: MergeDebugRect, b: MergeDebugRect): Boolean {
        val minHeight = minOf(a.height, b.height)
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return maxOf(a.height, b.height) <= minHeight * heightRatioLimit &&
            overlap > minHeight * minOverlap
    }
    fun overlapAllowed(a: MergeDebugRect, b: MergeDebugRect): Boolean {
        val (left, right) = if (a.left <= b.left) a to b else b to a
        val gap = right.left - left.right
        // Allow modest detector padding overlap, not nested boxes or neighbouring text rows.
        val maxOverlap = minOf(minOf(a.width, b.width) * 0.25f, minOf(a.height, b.height) * 0.5f)
        return gap >= -maxOverlap
    }
    fun close(a: MergeDebugRect, b: MergeDebugRect): Boolean {
        val gap = maxOf(a.left, b.left) - minOf(a.right, b.right)
        return gap <= (a.height + b.height) * 0.5f * adjacentGapRatio
    }
    val groups = mutableListOf<MutableList<Int>>()
    // Sweep left to right so a slightly lower middle box cannot arrive after both ends.
    val ordered = rects.indices.sortedWith(compareBy({ rects[it].left }, { rects[it].top }))
    for (index in ordered) {
        val box = rects[index]
        val row = groups.filter { members ->
            bubbleGroupIds[members.first()] == bubbleGroupIds[index] &&
                // Pairwise vertical compatibility prevents a sloping chain growing across rows.
                members.all { aligned(rects[it], box) && overlapAllowed(rects[it], box) } &&
                members.any { close(rects[it], box) }
        }.minByOrNull { members ->
            members.minOf { kotlin.math.abs((rects[it].top + rects[it].bottom) - (box.top + box.bottom)) }
        }
        if (row == null) groups += mutableListOf(index) else row += index
    }
    return groups.map { row -> row.sortedWith(compareBy({ rects[it].left }, { rects[it].top })) }
        .sortedWith(compareBy({ row -> row.minOf { rects[it].top } }, { row -> row.minOf { rects[it].left } }))
}
