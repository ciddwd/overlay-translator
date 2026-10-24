package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

/** A model can detect a whole dialogue panel containing independent paragraphs and controls. */
internal object BubbleTextGroupingPolicy {
    fun partition(bounds: List<IntRect>, horizontal: Boolean): List<List<Int>> {
        if (!horizontal) return if (bounds.isEmpty()) emptyList() else listOf(bounds.indices.toList())
        return groupHorizontalParagraphRects(
            bounds.map { MergeDebugRect(it.left,it.top,it.right,it.bottom) },
            List(bounds.size) { 0 }, verticalGapRatio=1.3f, horizontalOverlapRatio=.3f,
        )
    }
}
