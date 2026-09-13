package com.gameocr.app.ocr

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Expands a detector-derived glyph mask only while the candidate boundary still looks like one
 * continuous background.
 *
 * This deliberately uses geometry and image statistics only. OCR text, language and known page
 * contents must never influence mask completion.
 */
internal object AdaptiveTextEraseCompletionPolicy {

    enum class Strategy {
        OBSERVED_SUPPORT,
        ADAPTIVE_GROWTH,
        SEMANTIC_REGION,
        REJECTED,
    }

    data class Result(
        val mask: BooleanArray,
        val strategy: Strategy,
        val reliable: Boolean,
        val semanticPixels: Int,
        val coveredSemanticPixels: Int,
        val boundarySamples: Int,
        val boundaryInlierFraction: Float,
        val boundaryColorSpread: Float,
    ) {
        val semanticCoverage: Float
            get() = if (semanticPixels == 0) 1f else {
                coveredSemanticPixels.toFloat() / semanticPixels
            }
    }

    fun refine(
        width: Int,
        height: Int,
        sourceArgb: IntArray,
        seedMask: BooleanArray,
        supportMask: BooleanArray,
        semanticMask: BooleanArray,
    ): Result {
        require(width > 0 && height > 0)
        require(sourceArgb.size == width * height)
        require(seedMask.size == sourceArgb.size)
        require(supportMask.size == sourceArgb.size)
        require(semanticMask.size == sourceArgb.size)

        val base = BooleanArray(sourceArgb.size) { index ->
            seedMask[index] || supportMask[index]
        }
        val semanticPixels = semanticMask.count { it }
        val baseCoverage = countIntersection(base, semanticMask)
        if (
            semanticPixels == 0 ||
            baseCoverage.toFloat() / semanticPixels >= COMPLETE_COVERAGE_FRACTION
        ) {
            return Result(
                mask = base,
                strategy = Strategy.OBSERVED_SUPPORT,
                reliable = true,
                semanticPixels = semanticPixels,
                coveredSemanticPixels = baseCoverage,
                boundarySamples = 0,
                boundaryInlierFraction = 1f,
                boundaryColorSpread = 0f,
            )
        }

        val semanticBounds = maskBounds(semanticMask, width, height)
            ?: return rejected(base, semanticPixels, baseCoverage)
        val maximumGrowth = maxOf(semanticBounds.width, semanticBounds.height).coerceAtLeast(1)
        val radii = GROWTH_FRACTIONS
            .map { fraction -> ceil(maximumGrowth * fraction).toInt().coerceAtLeast(1) }
            .distinct()

        val candidates = mutableListOf<Pair<Strategy, BooleanArray>>()
        radii.forEach { radius ->
            val grown = BinarySquareDilation.dilate(
                input = supportMask,
                width = width,
                height = height,
                radius = radius,
            )
            val candidate = BooleanArray(base.size) { index ->
                base[index] || (grown[index] && semanticMask[index])
            }
            candidates += Strategy.ADAPTIVE_GROWTH to candidate
        }
        candidates += Strategy.SEMANTIC_REGION to BooleanArray(base.size) { index ->
            base[index] || semanticMask[index]
        }

        val distinctCandidates = candidates
            .distinctBy { (_, mask) -> countIntersection(mask, semanticMask) }
        var best: EvaluatedCandidate? = null
        distinctCandidates.forEach { (strategy, candidate) ->
            val evaluation = evaluateBoundary(
                width = width,
                height = height,
                sourceArgb = sourceArgb,
                candidate = candidate,
                seedMask = seedMask,
                semanticBounds = semanticBounds,
            ) ?: return@forEach
            if (!evaluation.accepted) return@forEach
            val covered = countIntersection(candidate, semanticMask)
            if (covered.toFloat() / semanticPixels < COMPLETE_COVERAGE_FRACTION) {
                return@forEach
            }
            val current = EvaluatedCandidate(
                strategy = strategy,
                mask = candidate,
                coveredSemanticPixels = covered,
                boundary = evaluation,
            )
            if (
                best == null ||
                current.coveredSemanticPixels > best!!.coveredSemanticPixels ||
                (
                    current.coveredSemanticPixels == best!!.coveredSemanticPixels &&
                        current.boundary.colorSpread < best!!.boundary.colorSpread
                    )
            ) {
                best = current
            }
        }

        val selected = best ?: return rejected(base, semanticPixels, baseCoverage)
        return Result(
            mask = selected.mask,
            strategy = selected.strategy,
            reliable = true,
            semanticPixels = semanticPixels,
            coveredSemanticPixels = selected.coveredSemanticPixels,
            boundarySamples = selected.boundary.samples,
            boundaryInlierFraction = selected.boundary.inlierFraction,
            boundaryColorSpread = selected.boundary.colorSpread,
        )
    }

    private data class BoundaryEvaluation(
        val accepted: Boolean,
        val samples: Int,
        val inlierFraction: Float,
        val colorSpread: Float,
    )

    private data class EvaluatedCandidate(
        val strategy: Strategy,
        val mask: BooleanArray,
        val coveredSemanticPixels: Int,
        val boundary: BoundaryEvaluation,
    )

    private fun evaluateBoundary(
        width: Int,
        height: Int,
        sourceArgb: IntArray,
        candidate: BooleanArray,
        seedMask: BooleanArray,
        semanticBounds: Bounds,
    ): BoundaryEvaluation? {
        val ringRadius = (minOf(semanticBounds.width, semanticBounds.height) / 80)
            .coerceIn(MIN_RING_RADIUS, MAX_RING_RADIUS)
        val expanded = BinarySquareDilation.dilate(candidate, width, height, ringRadius)
        val boundaryIndices = IntArray(expanded.size)
        var boundaryCount = 0
        expanded.indices.forEach { index ->
            if (expanded[index] && !candidate[index]) {
                boundaryIndices[boundaryCount++] = index
            }
        }
        if (boundaryCount < MIN_BOUNDARY_SAMPLES) return null

        // OCR bounds can end directly on an antialiased glyph edge. Sampling an inner ring would
        // therefore classify real text pixels as a background discontinuity. Instead, derive a
        // robust background reference from the candidate interior while excluding the confirmed
        // foreground seed, then require the outer ring to match that reference. This is the same
        // structural guard used by progressive mask-growth cleaners: expansion may continue only
        // while it remains inside one visually continuous background region.
        val referenceIndices = IntArray(candidate.size)
        var referenceCount = 0
        candidate.indices.forEach { index ->
            if (candidate[index] && !seedMask[index]) {
                referenceIndices[referenceCount++] = index
            }
        }
        if (referenceCount < MIN_BOUNDARY_SAMPLES) return null

        val reference = medianColor(
            sourceArgb = sourceArgb,
            indices = referenceIndices,
            count = referenceCount,
        )

        val step = ceil(boundaryCount.toDouble() / MAX_BOUNDARY_SAMPLES).toInt().coerceAtLeast(1)
        val sampledCount = ((boundaryCount + step - 1) / step).coerceAtMost(MAX_BOUNDARY_SAMPLES)
        val reds = IntArray(sampledCount)
        val greens = IntArray(sampledCount)
        val blues = IntArray(sampledCount)
        var sample = 0
        var cursor = 0
        while (cursor < boundaryCount && sample < sampledCount) {
            val color = sourceArgb[boundaryIndices[cursor]]
            reds[sample] = color ushr 16 and 0xff
            greens[sample] = color ushr 8 and 0xff
            blues[sample] = color and 0xff
            sample++
            cursor += step
        }
        val deviations = IntArray(sample)
        var inliers = 0
        for (index in 0 until sample) {
            val deviation = maxOf(
                abs(reds[index] - reference.red),
                abs(greens[index] - reference.green),
                abs(blues[index] - reference.blue),
            )
            deviations[index] = deviation
            if (deviation <= BOUNDARY_INLIER_DISTANCE) inliers++
        }
        deviations.sort()
        val percentileIndex = ((sample - 1) * BOUNDARY_SPREAD_PERCENTILE_NUMERATOR) /
            BOUNDARY_SPREAD_PERCENTILE_DENOMINATOR
        val spread = deviations[percentileIndex].toFloat()
        val inlierFraction = inliers.toFloat() / sample
        return BoundaryEvaluation(
            accepted = inlierFraction >= MIN_BOUNDARY_INLIER_FRACTION &&
                spread <= MAX_BOUNDARY_COLOR_SPREAD,
            samples = sample,
            inlierFraction = inlierFraction,
            colorSpread = spread,
        )
    }

    private data class Rgb(
        val red: Int,
        val green: Int,
        val blue: Int,
    )

    private fun medianColor(
        sourceArgb: IntArray,
        indices: IntArray,
        count: Int,
    ): Rgb {
        val step = ceil(count.toDouble() / MAX_BOUNDARY_SAMPLES).toInt().coerceAtLeast(1)
        val sampledCount = ((count + step - 1) / step).coerceAtMost(MAX_BOUNDARY_SAMPLES)
        val reds = IntArray(sampledCount)
        val greens = IntArray(sampledCount)
        val blues = IntArray(sampledCount)
        var sample = 0
        var cursor = 0
        while (cursor < count && sample < sampledCount) {
            val color = sourceArgb[indices[cursor]]
            reds[sample] = color ushr 16 and 0xff
            greens[sample] = color ushr 8 and 0xff
            blues[sample] = color and 0xff
            sample++
            cursor += step
        }
        reds.sort(0, sample)
        greens.sort(0, sample)
        blues.sort(0, sample)
        return Rgb(
            red = reds[sample / 2],
            green = greens[sample / 2],
            blue = blues[sample / 2],
        )
    }

    private fun rejected(
        base: BooleanArray,
        semanticPixels: Int,
        baseCoverage: Int,
    ) = Result(
        mask = base,
        strategy = Strategy.REJECTED,
        reliable = false,
        semanticPixels = semanticPixels,
        coveredSemanticPixels = baseCoverage,
        boundarySamples = 0,
        boundaryInlierFraction = 0f,
        boundaryColorSpread = Float.POSITIVE_INFINITY,
    )

    private fun countIntersection(first: BooleanArray, second: BooleanArray): Int {
        var count = 0
        first.indices.forEach { index -> if (first[index] && second[index]) count++ }
        return count
    }

    private data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    private fun maskBounds(mask: BooleanArray, width: Int, height: Int): Bounds? {
        var left = width
        var top = height
        var right = 0
        var bottom = 0
        var found = false
        mask.indices.forEach { index ->
            if (!mask[index]) return@forEach
            val x = index % width
            val y = index / width
            left = minOf(left, x)
            top = minOf(top, y)
            right = maxOf(right, x + 1)
            bottom = maxOf(bottom, y + 1)
            found = true
        }
        return if (found) Bounds(left, top, right, bottom) else null
    }

    private val GROWTH_FRACTIONS = listOf(0.25f, 0.5f, 0.75f)
    private const val COMPLETE_COVERAGE_FRACTION = 0.98f
    private const val MIN_RING_RADIUS = 1
    private const val MAX_RING_RADIUS = 4
    private const val MIN_BOUNDARY_SAMPLES = 12
    private const val MAX_BOUNDARY_SAMPLES = 4096
    private const val BOUNDARY_INLIER_DISTANCE = 28
    private const val MIN_BOUNDARY_INLIER_FRACTION = 0.82f
    private const val MAX_BOUNDARY_COLOR_SPREAD = 42f
    private const val BOUNDARY_SPREAD_PERCENTILE_NUMERATOR = 9
    private const val BOUNDARY_SPREAD_PERCENTILE_DENOMINATOR = 10
}
