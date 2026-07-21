package com.gameocr.app.translate

import android.graphics.Rect
import com.gameocr.app.ocr.TextBlock
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class CrossLineTextFlow {
    HORIZONTAL,
    VERTICAL,
}

internal data class CrossLineTranslationUnit(
    val blockIndexes: List<Int>,
    val sourceText: String,
    val flow: CrossLineTextFlow,
)

internal data class CrossLineSourceBlock(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun shouldUseCrossLineContextTranslation(
    enabled: Boolean,
    mergeAdjacentBlocks: Boolean,
): Boolean = enabled && !mergeAdjacentBlocks

internal fun crossLineContextTranslationEnabled(
    developerOptionsEnabled: Boolean,
    disableCrossLineContextTranslation: Boolean,
): Boolean = !(developerOptionsEnabled && disableCrossLineContextTranslation)

internal fun planCrossLineTranslationUnits(
    blocks: List<TextBlock>,
    sourceLanguageTag: String,
): List<CrossLineTranslationUnit> = planCrossLineSourceUnits(
    blocks = blocks.map { block ->
        val box = block.boundingBox
        CrossLineSourceBlock(block.text, box.left, box.top, box.right, box.bottom)
    },
    sourceLanguageTag = sourceLanguageTag,
)

internal fun planCrossLineSourceUnits(
    blocks: List<CrossLineSourceBlock>,
    sourceLanguageTag: String,
): List<CrossLineTranslationUnit> {
    if (blocks.isEmpty()) return emptyList()
    if (blocks.size == 1) {
        return listOf(
            CrossLineTranslationUnit(
                blockIndexes = listOf(0),
                sourceText = blocks.single().text,
                flow = sourceFlowFor(blocks),
            )
        )
    }
    val flow = sourceFlowFor(blocks)
    val compactJoin = usesCompactLineJoin(sourceLanguageTag, blocks.joinToString("") { it.text })
    val visualLines = blocks.mapIndexed { index, block ->
        VisualLine(
            blockIndexes = listOf(index),
            text = block.text,
            bounds = ContextRect(block.left, block.top, block.right, block.bottom),
        )
    }

    val groups = mutableListOf<MutableList<VisualLine>>()
    visualLines.forEach { line ->
        val current = groups.lastOrNull()
        val currentGeometry = current?.let(::groupGeometry)
        if (current != null && currentGeometry != null &&
            shouldJoinVisualLines(currentGeometry, line, flow)
        ) {
            current += line
        } else {
            groups += mutableListOf(line)
        }
    }
    return groups.map { lines ->
        CrossLineTranslationUnit(
            blockIndexes = lines.flatMap { it.blockIndexes },
            sourceText = lines.map { it.text }.joinSourceSegments(compactJoin),
            flow = flow,
        )
    }
}

private fun groupGeometry(lines: List<VisualLine>): VisualLine = VisualLine(
    blockIndexes = lines.flatMap { it.blockIndexes },
    text = lines.last().text,
    bounds = ContextRect(
        left = lines.minOf { it.bounds.left },
        top = lines.minOf { it.bounds.top },
        right = lines.maxOf { it.bounds.right },
        bottom = lines.maxOf { it.bounds.bottom },
    ),
)

internal fun individualTranslationUnits(blocks: List<TextBlock>): List<CrossLineTranslationUnit> =
    blocks.mapIndexed { index, block ->
        CrossLineTranslationUnit(
            blockIndexes = listOf(index),
            sourceText = block.text,
            flow = flowFor(listOf(block)),
        )
    }

internal fun reflowCrossLineTranslation(
    translatedText: String,
    unit: CrossLineTranslationUnit,
    blocks: List<TextBlock>,
    targetLanguageTag: String,
): List<String> {
    val indexes = unit.blockIndexes.filter { it in blocks.indices }
    if (indexes.isEmpty()) return emptyList()
    if (indexes.size == 1) return listOf(translatedText)

    val weights = indexes.map { index ->
        val box = blocks[index].boundingBox
        when (unit.flow) {
            CrossLineTextFlow.HORIZONTAL -> box.safeWidth
            CrossLineTextFlow.VERTICAL -> box.safeHeight
        }
    }
    return splitAtLanguageLineBreaks(translatedText, weights, targetLanguageTag)
}

private data class VisualLine(
    val blockIndexes: List<Int>,
    val text: String,
    val bounds: ContextRect,
)

private data class ContextRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val safeWidth: Int get() = (right - left).coerceAtLeast(1)
    val safeHeight: Int get() = (bottom - top).coerceAtLeast(1)
}

private fun flowFor(blocks: List<TextBlock>): CrossLineTextFlow {
    val portrait = blocks.count { block ->
        val box = block.boundingBox
        box.safeHeight > box.safeWidth * 1.3f
    }
    val landscape = blocks.count { block ->
        val box = block.boundingBox
        box.safeWidth > box.safeHeight * 1.3f
    }
    return if (portrait > landscape && portrait >= 2) {
        CrossLineTextFlow.VERTICAL
    } else {
        CrossLineTextFlow.HORIZONTAL
    }
}

private fun sourceFlowFor(blocks: List<CrossLineSourceBlock>): CrossLineTextFlow {
    val portrait = blocks.count { block ->
        val width = (block.right - block.left).coerceAtLeast(1)
        val height = (block.bottom - block.top).coerceAtLeast(1)
        height > width * 1.3f
    }
    val landscape = blocks.count { block ->
        val width = (block.right - block.left).coerceAtLeast(1)
        val height = (block.bottom - block.top).coerceAtLeast(1)
        width > height * 1.3f
    }
    return if (portrait > landscape && portrait >= 2) {
        CrossLineTextFlow.VERTICAL
    } else {
        CrossLineTextFlow.HORIZONTAL
    }
}

private fun shouldJoinVisualLines(
    current: VisualLine,
    next: VisualLine,
    flow: CrossLineTextFlow,
): Boolean {
    if (sameVisualAxisLine(current.bounds, next.bounds, flow)) return true
    if (startsStructuredItem(next.text)) return false
    if (endsSemanticUnit(current.text)) return false
    return when (flow) {
        CrossLineTextFlow.HORIZONTAL -> {
            val lineHeight = minOf(current.bounds.safeHeight, next.bounds.safeHeight)
            val gap = next.bounds.top - current.bounds.bottom
            val overlap = intervalOverlap(
                current.bounds.left,
                current.bounds.right,
                next.bounds.left,
                next.bounds.right,
            )
            val minWidth = minOf(current.bounds.safeWidth, next.bounds.safeWidth)
            gap >= -lineHeight * 0.35f &&
                gap <= lineHeight * 0.9f &&
                (overlap.toFloat() / minWidth >= 0.3f ||
                    abs(current.bounds.left - next.bounds.left) <= lineHeight)
        }
        CrossLineTextFlow.VERTICAL -> {
            val columnWidth = minOf(current.bounds.safeWidth, next.bounds.safeWidth)
            val gap = current.bounds.left - next.bounds.right
            val overlap = intervalOverlap(
                current.bounds.top,
                current.bounds.bottom,
                next.bounds.top,
                next.bounds.bottom,
            )
            val minHeight = minOf(current.bounds.safeHeight, next.bounds.safeHeight)
            gap >= -columnWidth * 0.35f &&
                gap <= columnWidth * 0.9f &&
                overlap.toFloat() / minHeight >= 0.3f
        }
    }
}

private fun sameVisualAxisLine(
    first: ContextRect,
    second: ContextRect,
    flow: CrossLineTextFlow,
): Boolean = when (flow) {
    CrossLineTextFlow.HORIZONTAL -> {
        val overlap = intervalOverlap(first.top, first.bottom, second.top, second.bottom)
        val minHeight = minOf(first.safeHeight, second.safeHeight)
        val (left, right) = if (first.left <= second.left) first to second else second to first
        val gap = right.left - left.right
        overlap.toFloat() / minHeight >= 0.5f && gap <= minHeight * 1.5f
    }
    CrossLineTextFlow.VERTICAL -> {
        val overlap = intervalOverlap(first.left, first.right, second.left, second.right)
        val minWidth = minOf(first.safeWidth, second.safeWidth)
        val (upper, lower) = if (first.top <= second.top) first to second else second to first
        val gap = lower.top - upper.bottom
        overlap.toFloat() / minWidth >= 0.5f && gap <= minWidth * 1.5f
    }
}

private fun intervalOverlap(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int): Int =
    (minOf(firstEnd, secondEnd) - maxOf(firstStart, secondStart)).coerceAtLeast(0)

private val Rect.safeWidth: Int
    get() = (right - left).coerceAtLeast(1)

private val Rect.safeHeight: Int
    get() = (bottom - top).coerceAtLeast(1)

private fun List<String>.joinSourceSegments(compact: Boolean): String {
    if (isEmpty()) return ""
    return fold("") { accumulated, segment ->
        val next = segment.trim()
        when {
            accumulated.isEmpty() -> next
            next.isEmpty() -> accumulated
            compact -> accumulated + next
            accumulated.endsWith('\u00AD') -> accumulated.dropLast(1) + next
            accumulated.endsWith('-') -> accumulated + next
            else -> "$accumulated $next"
        }
    }
}

private fun usesCompactLineJoin(languageTag: String, text: String): Boolean {
    val primary = languageTag.trim().lowercase().substringBefore('-')
    if (primary == "zh" || primary == "ja" || primary == "th") return true
    if (primary.isNotEmpty() && primary != "auto") return false
    val compact = text.filterNot(Char::isWhitespace)
    if (compact.isEmpty()) return false
    val cjk = compact.count { char ->
        char.code in 0x3040..0x30FF || char.code in 0x3400..0x9FFF
    }
    return cjk * 2 >= compact.length
}

private fun startsStructuredItem(text: String): Boolean = STRUCTURED_ITEM.matchesAt(text.trimStart(), 0)

private fun endsSemanticUnit(text: String): Boolean {
    val trimmed = text.trimEnd().trimEnd(*CLOSING_PUNCTUATION)
    return trimmed.lastOrNull()?.let { it in TERMINAL_PUNCTUATION } == true
}

private fun splitAtLanguageLineBreaks(
    text: String,
    weights: List<Int>,
    languageTag: String,
): List<String> {
    if (weights.isEmpty()) return emptyList()
    if (weights.size == 1 || text.isEmpty()) return listOf(text) + List(weights.size - 1) { "" }

    val locale = runCatching { Locale.forLanguageTag(languageTag) }.getOrDefault(Locale.ROOT)
    val iterator = BreakIterator.getLineInstance(locale)
    iterator.setText(text)
    val boundaries = mutableListOf<Int>()
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        if (boundary > 0) boundaries += boundary
        boundary = iterator.next()
    }
    if (boundaries.lastOrNull() != text.length) boundaries += text.length

    val totalWeight = weights.sum().coerceAtLeast(1)
    val results = mutableListOf<String>()
    var start = 0
    var consumedWeight = 0
    weights.dropLast(1).forEach { weight ->
        consumedWeight += weight
        val desired = (text.length.toFloat() * consumedWeight / totalWeight).roundToInt()
        val candidates = boundaries.filter { it > start && it < text.length }
        val split = candidates.minByOrNull { abs(it - desired) }
            ?: desired.coerceIn((start + 1).coerceAtMost(text.length), text.length)
        results += text.substring(start, split).trim()
        start = split
    }
    results += text.substring(start).trim()
    return results
}

private val STRUCTURED_ITEM = Regex("^(?:[\\p{N}]+[.)．、]|[-•●▪◦])\\s*.*")
private val CLOSING_PUNCTUATION = charArrayOf('"', '\'', '”', '’', '」', '』', '）', ')', ']', '】')
private val TERMINAL_PUNCTUATION = setOf('。', '！', '？', '!', '?', '.', ':', '：')
