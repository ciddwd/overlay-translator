package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.abs

internal data class MangaOcrCropPlan(
    val sourceBubbleIndex: Int,
    val cropIndex: Int,
    val cropCount: Int,
    val bubble: Bubble,
)

/**
 * Keeps each manga-ocr input small enough for the model's fixed 224x224 encoder.
 *
 * DBNet rectangles can form a transitive chain across a dense document. Passing that whole chain
 * through one crop compresses too many text rows or columns into the encoder and encourages the
 * autoregressive decoder to invent plausible Japanese. Normal speech bubbles stay untouched; only
 * clusters with more than [MAX_TEXT_BANDS_PER_CROP] distinct rows or columns are split.
 */
internal object MangaOcrCropPlanner {
    // The 224px encoder has 14 ViT patches per axis. Six bands preserve roughly two patches per
    // text row/column instead of squeezing a dense document into sub-patch glyphs.
    const val MAX_TEXT_BANDS_PER_CROP: Int = 6

    fun plan(
        bubbles: List<Bubble>,
        rects: List<IntRect>,
        imageWidth: Int,
        imageHeight: Int,
        padding: Int,
        splitByTextBandBubbleIndices: Set<Int> = emptySet(),
    ): List<MangaOcrCropPlan> = bubbles.flatMapIndexed { bubbleIndex, bubble ->
        val members = bubble.memberIndices.mapNotNull { memberIndex ->
            rects.getOrNull(memberIndex)?.let { rect ->
                IndexedRect(memberIndex, rect)
            }
        }
        if (members.isEmpty()) {
            return@flatMapIndexed listOf(
                MangaOcrCropPlan(bubbleIndex, cropIndex = 0, cropCount = 1, bubble),
            )
        }

        val orientation = inferSourceLayoutOrientation(
            sourceBoxes = members.map(IndexedRect::rect),
            blockBounds = bubble.contentRect,
        )
        val bands = groupIntoTextBands(members, orientation)
        val splitEveryBand = bubbleIndex in splitByTextBandBubbleIndices && bands.size > 1
        if (!splitEveryBand && bands.size <= MAX_TEXT_BANDS_PER_CROP) {
            return@flatMapIndexed listOf(
                MangaOcrCropPlan(bubbleIndex, cropIndex = 0, cropCount = 1, bubble),
            )
        }

        val chunks = if (splitEveryBand) {
            bands.map(::listOf)
        } else {
            bands.chunked(MAX_TEXT_BANDS_PER_CROP)
        }
        chunks.mapIndexed { cropIndex, chunk ->
            MangaOcrCropPlan(
                sourceBubbleIndex = bubbleIndex,
                cropIndex = cropIndex,
                cropCount = chunks.size,
                bubble = bubbleForMembers(
                    members = chunk.flatten(),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    padding = padding,
                ),
            )
        }
    }

    private fun groupIntoTextBands(
        members: List<IndexedRect>,
        orientation: TextOrientation,
    ): List<List<IndexedRect>> {
        val horizontal = orientation == TextOrientation.HORIZONTAL_LTR
        val sorted = if (horizontal) {
            members.sortedWith(compareBy({ it.rect.centerY2() }, { it.rect.left }))
        } else {
            members.sortedWith(
                compareByDescending<IndexedRect> { it.rect.centerX2() }
                    .thenBy { it.rect.top },
            )
        }
        val bands = mutableListOf<MutableList<IndexedRect>>()
        sorted.forEach { member ->
            val current = bands.lastOrNull()
            if (current != null && belongsToSameBand(member, current, horizontal)) {
                current += member
            } else {
                bands.add(mutableListOf(member))
            }
        }
        return bands
    }

    private fun belongsToSameBand(
        candidate: IndexedRect,
        band: List<IndexedRect>,
        horizontal: Boolean,
    ): Boolean {
        val candidateCenter2 = if (horizontal) {
            candidate.rect.centerY2()
        } else {
            candidate.rect.centerX2()
        }
        val bandCenter2 = band.map { member ->
            if (horizontal) member.rect.centerY2() else member.rect.centerX2()
        }.average()
        val candidateThickness = if (horizontal) candidate.rect.height else candidate.rect.width
        val bandThickness = band.maxOf { member ->
            if (horizontal) member.rect.height else member.rect.width
        }
        val tolerance = maxOf(candidateThickness, bandThickness) * SAME_BAND_CENTER_TOLERANCE
        return abs(candidateCenter2 - bandCenter2) / 2.0 <= tolerance
    }

    private fun bubbleForMembers(
        members: List<IndexedRect>,
        imageWidth: Int,
        imageHeight: Int,
        padding: Int,
    ): Bubble {
        val left = members.minOf { it.rect.left }
        val top = members.minOf { it.rect.top }
        val right = members.maxOf { it.rect.right }
        val bottom = members.maxOf { it.rect.bottom }
        val contentRect = IntRect(
            left = left.coerceIn(0, imageWidth),
            top = top.coerceIn(0, imageHeight),
            right = right.coerceIn(0, imageWidth),
            bottom = bottom.coerceIn(0, imageHeight),
        )
        val safePadding = padding.coerceAtLeast(0)
        return Bubble(
            rect = IntRect(
                left = (left - safePadding).coerceAtLeast(0),
                top = (top - safePadding).coerceAtLeast(0),
                right = (right + safePadding).coerceAtMost(imageWidth),
                bottom = (bottom + safePadding).coerceAtMost(imageHeight),
            ),
            contentRect = contentRect,
            memberIndices = members.map(IndexedRect::index),
        )
    }

    private data class IndexedRect(
        val index: Int,
        val rect: IntRect,
    )

    private fun IntRect.centerX2(): Int = left + right

    private fun IntRect.centerY2(): Int = top + bottom

    private const val SAME_BAND_CENTER_TOLERANCE: Double = 0.5
}
