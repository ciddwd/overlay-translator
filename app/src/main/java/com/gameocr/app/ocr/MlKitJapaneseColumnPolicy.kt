package com.gameocr.app.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal data class MlKitGeometryRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = (right - left).coerceAtLeast(0)

    val height: Int
        get() = (bottom - top).coerceAtLeast(0)

    val centerX: Float
        get() = (left + right) / 2f

    val centerY: Float
        get() = (top + bottom) / 2f

    val isEmpty: Boolean
        get() = width == 0 || height == 0
}

internal data class MlKitJapaneseGlyph(
    val bounds: MlKitGeometryRect,
    val sourceLineIndex: Int,
)

internal data class MlKitJapaneseColumnCandidate(
    val memberIndices: List<Int>,
    val sourceLineIndices: List<Int>,
    val bounds: MlKitGeometryRect,
    val medianGlyphSize: Float,
)

internal data class MlKitJapaneseColumnPlan(
    val columns: List<MlKitJapaneseColumnCandidate>,
    val verticalCoverage: Float,
    val horizontalCoverage: Float,
    val verticalScore: Float,
    val horizontalScore: Float,
    val horizontalLineRatio: Float,
    val shouldRunShadowRecognition: Boolean,
    val reason: String,
)

/**
 * Builds Japanese text-column hypotheses from ML Kit symbol geometry only.
 *
 * The recognized characters never participate in the decision. Every distance is normalized by
 * the median glyph size so the same policy applies to screenshots with different resolutions and
 * font sizes. The first-pass ML Kit line orientation is used only as conflict evidence: a good
 * vertical first pass is deliberately left alone.
 */
internal object MlKitJapaneseColumnPolicy {
    private const val MIN_GLYPHS = 6
    private const val MIN_RUN_GLYPHS = 3
    private const val CROSS_AXIS_CENTER_LIMIT = 0.72f
    private const val PRIMARY_AXIS_GAP_LIMIT = 1.85f
    private const val MIN_GLYPH_SIZE_RATIO = 0.38f
    private const val MIN_AXIS_SPAN = 1.8f
    private const val MIN_VERTICAL_COVERAGE = 0.45f
    private const val MIN_SCORE_ADVANTAGE = 0.08f
    private const val MIN_DENSE_VERTICAL_COVERAGE = 0.75f
    private const val MIN_DENSE_VERTICAL_SCORE = 0.82f
    private const val MAX_DENSE_SCORE_DEFICIT = 0.03f
    private const val MIN_HORIZONTAL_LINE_RATIO = 0.60f

    fun plan(
        glyphs: List<MlKitJapaneseGlyph>,
        lineOrientations: List<TextOrientation?>,
    ): MlKitJapaneseColumnPlan {
        val valid = glyphs.filter { glyph ->
            !glyph.bounds.isEmpty
        }
        if (valid.size < MIN_GLYPHS) {
            return emptyPlan("insufficient-glyphs")
        }

        val glyphScale = median(valid.map { glyph -> glyphEm(glyph.bounds) })
            .coerceAtLeast(1f)
        val verticalRuns = buildRuns(valid, glyphScale, Axis.VERTICAL)
        val horizontalRuns = buildRuns(valid, glyphScale, Axis.HORIZONTAL)
        val verticalMetrics = metrics(verticalRuns, valid.size)
        val horizontalMetrics = metrics(horizontalRuns, valid.size)
        val knownOrientations = lineOrientations.filterNotNull().filterNot {
            it == TextOrientation.UNKNOWN
        }
        val horizontalLineRatio = if (knownOrientations.isEmpty()) {
            0f
        } else {
            knownOrientations.count {
                it == TextOrientation.HORIZONTAL_LTR || it == TextOrientation.HORIZONTAL_RTL
            }.toFloat() / knownOrientations.size
        }
        val dominantVerticalGeometry =
            verticalMetrics.coverage >= MIN_VERTICAL_COVERAGE &&
                verticalMetrics.score >= horizontalMetrics.score + MIN_SCORE_ADVANTAGE
        // Dense manga layouts can form a two-dimensional adjacency lattice. In that case both
        // scores are high, so requiring vertical dominance hides exactly the broken first passes
        // this Debug-only comparison is intended to inspect.
        val denseAmbiguousVerticalGeometry =
            verticalMetrics.coverage >= MIN_DENSE_VERTICAL_COVERAGE &&
                verticalMetrics.score >= MIN_DENSE_VERTICAL_SCORE &&
                verticalMetrics.score >= horizontalMetrics.score - MAX_DENSE_SCORE_DEFICIT
        val strongVerticalGeometry =
            dominantVerticalGeometry || denseAmbiguousVerticalGeometry
        val lineConflict = horizontalLineRatio >= MIN_HORIZONTAL_LINE_RATIO
        val shouldRun = strongVerticalGeometry && lineConflict && verticalRuns.isNotEmpty()
        val reason = when {
            verticalRuns.isEmpty() -> "no-vertical-runs"
            !strongVerticalGeometry -> "vertical-evidence-not-dominant"
            !lineConflict -> "first-pass-not-horizontal"
            else -> "vertical-geometry-conflicts-with-first-pass"
        }

        return MlKitJapaneseColumnPlan(
            columns = verticalRuns.map { run ->
                MlKitJapaneseColumnCandidate(
                    memberIndices = run,
                    sourceLineIndices = run.map { valid[it].sourceLineIndex }.distinct().sorted(),
                    bounds = union(run.map { valid[it].bounds }),
                    medianGlyphSize = median(run.map { glyphEm(valid[it].bounds) }),
                )
            }.sortedWith(
                compareByDescending<MlKitJapaneseColumnCandidate> { it.memberIndices.size }
                    .thenByDescending { it.bounds.centerX }
                    .thenBy { it.bounds.top },
            ),
            verticalCoverage = verticalMetrics.coverage,
            horizontalCoverage = horizontalMetrics.coverage,
            verticalScore = verticalMetrics.score,
            horizontalScore = horizontalMetrics.score,
            horizontalLineRatio = horizontalLineRatio,
            shouldRunShadowRecognition = shouldRun,
            reason = reason,
        )
    }

    private fun buildRuns(
        glyphs: List<MlKitJapaneseGlyph>,
        glyphScale: Float,
        axis: Axis,
    ): List<List<Int>> {
        val unionFind = UnionFind(glyphs.size)
        for (leftIndex in glyphs.indices) {
            for (rightIndex in leftIndex + 1 until glyphs.size) {
                if (canLink(glyphs[leftIndex].bounds, glyphs[rightIndex].bounds, glyphScale, axis)) {
                    unionFind.union(leftIndex, rightIndex)
                }
            }
        }
        return glyphs.indices
            .groupBy(unionFind::find)
            .values
            .filter { members ->
                if (members.size < MIN_RUN_GLYPHS) return@filter false
                val bounds = union(members.map { glyphs[it].bounds })
                val primarySpan = if (axis == Axis.VERTICAL) bounds.height else bounds.width
                val crossSpan = if (axis == Axis.VERTICAL) bounds.width else bounds.height
                primarySpan >= glyphScale * MIN_AXIS_SPAN && primarySpan > crossSpan
            }
    }

    private fun canLink(
        first: MlKitGeometryRect,
        second: MlKitGeometryRect,
        glyphScale: Float,
        axis: Axis,
    ): Boolean {
        val firstSize = glyphEm(first)
        val secondSize = glyphEm(second)
        val sizeRatio = min(firstSize, secondSize) / max(firstSize, secondSize).coerceAtLeast(1f)
        if (sizeRatio < MIN_GLYPH_SIZE_RATIO) return false

        val primaryGap: Float
        val crossCenterDistance: Float
        if (axis == Axis.VERTICAL) {
            primaryGap = axisGap(first.top, first.bottom, second.top, second.bottom)
            crossCenterDistance = kotlin.math.abs(first.centerX - second.centerX)
        } else {
            primaryGap = axisGap(first.left, first.right, second.left, second.right)
            crossCenterDistance = kotlin.math.abs(first.centerY - second.centerY)
        }
        return primaryGap <= glyphScale * PRIMARY_AXIS_GAP_LIMIT &&
            crossCenterDistance <= glyphScale * CROSS_AXIS_CENTER_LIMIT
    }

    private fun axisGap(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int): Float =
        when {
            firstEnd < secondStart -> (secondStart - firstEnd).toFloat()
            secondEnd < firstStart -> (firstStart - secondEnd).toFloat()
            else -> 0f
        }

    private fun metrics(runs: List<List<Int>>, glyphCount: Int): FlowMetrics {
        if (runs.isEmpty() || glyphCount <= 0) return FlowMetrics(0f, 0f)
        val covered = runs.flatten().distinct().size
        val coverage = covered.toFloat() / glyphCount
        val longest = runs.maxOf { it.size }
        val continuity = (longest / 6f).coerceIn(0f, 1f)
        return FlowMetrics(
            coverage = coverage,
            score = coverage * 0.65f + continuity * 0.35f,
        )
    }

    private fun emptyPlan(reason: String) = MlKitJapaneseColumnPlan(
        columns = emptyList(),
        verticalCoverage = 0f,
        horizontalCoverage = 0f,
        verticalScore = 0f,
        horizontalScore = 0f,
        horizontalLineRatio = 0f,
        shouldRunShadowRecognition = false,
        reason = reason,
    )

    private fun glyphEm(rect: MlKitGeometryRect): Float =
        sqrt(rect.width.toFloat() * rect.height.toFloat())

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }

    private fun union(rects: List<MlKitGeometryRect>): MlKitGeometryRect {
        require(rects.isNotEmpty())
        return MlKitGeometryRect(
            rects.minOf { it.left },
            rects.minOf { it.top },
            rects.maxOf { it.right },
            rects.maxOf { it.bottom },
        )
    }

    private enum class Axis { HORIZONTAL, VERTICAL }

    private data class FlowMetrics(val coverage: Float, val score: Float)

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun find(value: Int): Int {
            if (parent[value] != value) parent[value] = find(parent[value])
            return parent[value]
        }

        fun union(first: Int, second: Int) {
            val firstRoot = find(first)
            val secondRoot = find(second)
            if (firstRoot == secondRoot) return
            when {
                rank[firstRoot] < rank[secondRoot] -> parent[firstRoot] = secondRoot
                rank[firstRoot] > rank[secondRoot] -> parent[secondRoot] = firstRoot
                else -> {
                    parent[secondRoot] = firstRoot
                    rank[firstRoot]++
                }
            }
        }
    }
}

/**
 * Expands symbol-flow hypotheses to complete ML Kit line regions. Symbol runs are only conflict
 * evidence; they must not crop glyphs directly because the first pass may split one vertical line
 * into several incomplete horizontal fragments.
 */
internal object MlKitJapaneseRegionPolicy {
    fun expand(
        columns: List<MlKitJapaneseColumnCandidate>,
        lineBounds: List<MlKitGeometryRect>,
    ): List<MlKitJapaneseColumnCandidate> {
        if (columns.isEmpty()) return emptyList()
        val expanded = columns.map { column ->
            val completeLineBounds = column.sourceLineIndices.mapNotNull { lineIndex ->
                lineBounds.getOrNull(lineIndex)?.takeUnless(MlKitGeometryRect::isEmpty)
            }
            column.copy(bounds = union(completeLineBounds + column.bounds))
        }
        val unionFind = RegionUnionFind(expanded.size)
        for (firstIndex in expanded.indices) {
            val firstLines = expanded[firstIndex].sourceLineIndices.toSet()
            for (secondIndex in firstIndex + 1 until expanded.size) {
                if (firstLines.any(expanded[secondIndex].sourceLineIndices::contains)) {
                    unionFind.union(firstIndex, secondIndex)
                }
            }
        }
        return expanded.indices
            .groupBy(unionFind::find)
            .values
            .map { group ->
                val members = group.flatMap { expanded[it].memberIndices }.distinct().sorted()
                val sourceLines = group.flatMap { expanded[it].sourceLineIndices }.distinct().sorted()
                MlKitJapaneseColumnCandidate(
                    memberIndices = members,
                    sourceLineIndices = sourceLines,
                    bounds = union(group.map { expanded[it].bounds }),
                    medianGlyphSize = median(group.map { expanded[it].medianGlyphSize }),
                )
            }
            .distinctBy { region ->
                listOf(
                    region.bounds.left,
                    region.bounds.top,
                    region.bounds.right,
                    region.bounds.bottom,
                ) to region.sourceLineIndices
            }
            .sortedWith(
                compareByDescending<MlKitJapaneseColumnCandidate> { it.memberIndices.size }
                    .thenByDescending { it.bounds.centerX }
                    .thenBy { it.bounds.top },
            )
    }

    private fun union(rects: List<MlKitGeometryRect>): MlKitGeometryRect {
        require(rects.isNotEmpty())
        return MlKitGeometryRect(
            left = rects.minOf { it.left },
            top = rects.minOf { it.top },
            right = rects.maxOf { it.right },
            bottom = rects.maxOf { it.bottom },
        )
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }

    private class RegionUnionFind(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(value: Int): Int {
            if (parent[value] != value) parent[value] = find(parent[value])
            return parent[value]
        }

        fun union(first: Int, second: Int) {
            val firstRoot = find(first)
            val secondRoot = find(second)
            if (firstRoot != secondRoot) parent[secondRoot] = firstRoot
        }
    }
}

internal data class MlKitJapaneseColumnCropPlan(
    val sourceBounds: MlKitGeometryRect,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    val changed: Boolean
        get() = sourceBounds.width != outputWidth || sourceBounds.height != outputHeight
}

internal object MlKitJapaneseColumnCropPolicy {
    private const val PADDING_EM = 0.35f
    private const val TARGET_GLYPH_PX = 20f
    // The full screenshot has already passed through the page input policy. Do not discard
    // character detail a second time when extracting a small local recognition region.
    private const val MIN_SCALE = 1.00f
    private const val MAX_SCALE = 2.50f
    private const val MAX_LONG_SIDE = 1_600

    fun plan(
        candidate: MlKitJapaneseColumnCandidate,
        imageWidth: Int,
        imageHeight: Int,
    ): MlKitJapaneseColumnCropPlan {
        require(imageWidth > 0 && imageHeight > 0)
        val padding = ceil(candidate.medianGlyphSize * PADDING_EM).toInt().coerceAtLeast(1)
        val source = MlKitGeometryRect(
            (candidate.bounds.left - padding).coerceIn(0, imageWidth),
            (candidate.bounds.top - padding).coerceIn(0, imageHeight),
            (candidate.bounds.right + padding).coerceIn(0, imageWidth),
            (candidate.bounds.bottom + padding).coerceIn(0, imageHeight),
        )
        val requestedScale = (TARGET_GLYPH_PX / candidate.medianGlyphSize.coerceAtLeast(1f))
            .coerceIn(MIN_SCALE, MAX_SCALE)
        val longSideLimitedScale = min(
            requestedScale,
            MAX_LONG_SIDE.toFloat() / max(source.width, source.height).coerceAtLeast(1),
        )
        return MlKitJapaneseColumnCropPlan(
            sourceBounds = source,
            outputWidth = (source.width * longSideLimitedScale).toInt().coerceAtLeast(1),
            outputHeight = (source.height * longSideLimitedScale).toInt().coerceAtLeast(1),
        )
    }
}
