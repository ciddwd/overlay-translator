package com.gameocr.app.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal data class MlKitJapaneseShadowVariant(
    val rotationDegrees: Int,
    val blocks: List<TextBlock>,
)

internal data class MlKitJapaneseShadowVariantEvaluation(
    val rotationDegrees: Int,
    val retainedBlocks: List<TextBlock>,
    val nonVerticalBlocks: List<TextBlock>,
    val annotationBlocks: List<TextBlock>,
    val edgeFragmentBlocks: List<TextBlock>,
    val verticalCharacterRatio: Float,
    val completeness: Float,
    val confidence: Float,
    val score: Float,
    val accepted: Boolean,
)

internal data class MlKitJapaneseShadowSelection(
    val selected: MlKitJapaneseShadowVariantEvaluation?,
    val evaluations: List<MlKitJapaneseShadowVariantEvaluation>,
)

internal enum class MlKitJapaneseRotationFallback {
    STOP,
    CLOCKWISE,
    COUNTERCLOCKWISE,
}

/**
 * Schedules rotated recognition only when the isolated upright crop cannot produce an accepted
 * vertical result. Two consecutive orientations that both contain only horizontal rows are
 * treated as structural evidence that the crop is not recoverable by trying the opposite rotation.
 */
internal object MlKitJapaneseRotationFallbackPolicy {
    fun next(selection: MlKitJapaneseShadowSelection): MlKitJapaneseRotationFallback {
        if (selection.selected != null) return MlKitJapaneseRotationFallback.STOP

        val attemptedRotations = selection.evaluations.map { evaluation -> evaluation.rotationDegrees }.toSet()
        if (0 !in attemptedRotations) return MlKitJapaneseRotationFallback.STOP
        if (90 !in attemptedRotations) return MlKitJapaneseRotationFallback.CLOCKWISE
        if (-90 in attemptedRotations) return MlKitJapaneseRotationFallback.STOP

        val hasNoRecognizedText = selection.evaluations.all { evaluation ->
            evaluation.retainedBlocks.isEmpty() && evaluation.nonVerticalBlocks.isEmpty()
        }
        val hasPartialVerticalEvidence = selection.evaluations.any { evaluation ->
            evaluation.verticalCharacterRatio > 0f
        }
        return if (hasNoRecognizedText || hasPartialVerticalEvidence) {
            MlKitJapaneseRotationFallback.COUNTERCLOCKWISE
        } else {
            MlKitJapaneseRotationFallback.STOP
        }
    }
}

/**
 * Chooses a repaired vertical-Japanese result without inspecting particular characters.
 *
 * Confidence alone is unsafe: a rotated crop can receive a slightly higher confidence after
 * losing most of its text. The decision therefore combines retained character coverage, restored
 * vertical-line geometry and confidence. Small overlapping side text and short crop-edge fragments
 * are excluded only when their geometry is relative to the other lines in the same crop.
 */
internal object MlKitJapaneseShadowSelectionPolicy {
    private const val MIN_VERTICAL_ASPECT = 1.25f
    private const val MIN_VERTICAL_CHARACTER_RATIO = 0.45f
    private const val MIN_EVIDENCE_COVERAGE = 0.50f
    private const val ROTATION_COST = 0.004f

    fun select(
        variants: List<MlKitJapaneseShadowVariant>,
        evidenceGlyphCount: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): MlKitJapaneseShadowSelection {
        require(evidenceGlyphCount > 0)
        require(cropWidth > 0 && cropHeight > 0)
        val evaluations = variants.map { variant ->
            evaluate(
                variant = variant,
                evidenceGlyphCount = evidenceGlyphCount,
                cropWidth = cropWidth,
                cropHeight = cropHeight,
            )
        }
        return MlKitJapaneseShadowSelection(
            selected = evaluations.filter(MlKitJapaneseShadowVariantEvaluation::accepted)
                .maxWithOrNull(
                    compareBy<MlKitJapaneseShadowVariantEvaluation> { it.score }
                        .thenBy { it.rotationDegrees == 0 },
                ),
            evaluations = evaluations,
        )
    }

    private fun evaluate(
        variant: MlKitJapaneseShadowVariant,
        evidenceGlyphCount: Int,
        cropWidth: Int,
        cropHeight: Int,
    ): MlKitJapaneseShadowVariantEvaluation {
        val totalCharacterCount = variant.blocks.sumOf { block -> block.text.contentLength() }
        val (verticalBlocks, nonVerticalBlocks) = variant.blocks.partition { block ->
            val box = block.boundingBox
            box.rectHeight() >= box.rectWidth().coerceAtLeast(1) * MIN_VERTICAL_ASPECT
        }
        val verticalCharacterCount = verticalBlocks.sumOf { block -> block.text.contentLength() }
        val widths = verticalBlocks.map { block -> block.boundingBox.rectWidth().coerceAtLeast(1) }
        val heights = verticalBlocks.map { block -> block.boundingBox.rectHeight().coerceAtLeast(1) }
        val medianWidth = median(widths)
        val medianHeight = median(heights)

        val annotationBlocks = if (verticalBlocks.size >= 2) {
            verticalBlocks.filter { candidate ->
                val box = candidate.boundingBox
                box.rectWidth() <= medianWidth * 0.90f &&
                    box.rectHeight() <= medianHeight * 0.65f &&
                    verticalBlocks.any { main ->
                        main !== candidate &&
                            main.boundingBox.rectWidth() >= box.rectWidth() / 0.90f &&
                            verticalOverlapRatio(box, main.boundingBox) >= 0.80f &&
                            horizontalGap(box, main.boundingBox) <= medianWidth * 0.20f
                    }
            }
        } else {
            emptyList()
        }
        val withoutAnnotations = verticalBlocks - annotationBlocks.toSet()
        val edgeFragmentBlocks = withoutAnnotations.filter { candidate ->
            val box = candidate.boundingBox
            val edgeDistance = min(box.left, cropWidth - box.right).coerceAtLeast(0)
            edgeDistance <= medianWidth * 0.65f &&
                box.rectWidth() <= medianWidth * 0.90f &&
                box.rectHeight() <= medianHeight * 0.65f
        }
        val retained = withoutAnnotations - edgeFragmentBlocks.toSet()
        val retainedCharacterCount = retained.sumOf { block -> block.text.contentLength() }
        val excludedEvidenceCharacters = (annotationBlocks + edgeFragmentBlocks)
            .sumOf { block -> block.text.contentLength() }
        val effectiveEvidenceGlyphCount = (evidenceGlyphCount - excludedEvidenceCharacters)
            .coerceAtLeast(1)
        val verticalRatio = if (totalCharacterCount == 0) {
            0f
        } else {
            verticalCharacterCount.toFloat() / totalCharacterCount
        }
        val completeness = min(retainedCharacterCount, effectiveEvidenceGlyphCount).toFloat() /
            max(retainedCharacterCount, effectiveEvidenceGlyphCount).coerceAtLeast(1)
        val confidence = retained.weightedConfidence()
        val rotationCost = if (variant.rotationDegrees == 0) 0f else ROTATION_COST
        val score = completeness * 0.55f + verticalRatio * 0.30f + confidence * 0.15f - rotationCost
        val minimumCharacters = max(
            3,
            ceil(effectiveEvidenceGlyphCount * MIN_EVIDENCE_COVERAGE).toInt(),
        )
        val accepted = retainedCharacterCount >= minimumCharacters &&
            verticalRatio >= MIN_VERTICAL_CHARACTER_RATIO &&
            retained.isNotEmpty()

        return MlKitJapaneseShadowVariantEvaluation(
            rotationDegrees = variant.rotationDegrees,
            retainedBlocks = retained,
            nonVerticalBlocks = nonVerticalBlocks,
            annotationBlocks = annotationBlocks,
            edgeFragmentBlocks = edgeFragmentBlocks,
            verticalCharacterRatio = verticalRatio,
            completeness = completeness,
            confidence = confidence,
            score = score,
            accepted = accepted,
        )
    }

    private fun String.contentLength(): Int = count { character -> !character.isWhitespace() }

    private fun List<TextBlock>.weightedConfidence(): Float {
        val weights = map { block -> block.text.contentLength().coerceAtLeast(1) }
        val totalWeight = weights.sum()
        if (totalWeight == 0) return 0f
        return mapIndexed { index, block -> block.confidence * weights[index] }.sum() / totalWeight
    }

    private fun horizontalGap(first: android.graphics.Rect, second: android.graphics.Rect): Int = when {
        first.right < second.left -> second.left - first.right
        second.right < first.left -> first.left - second.right
        else -> 0
    }

    private fun verticalOverlapRatio(first: android.graphics.Rect, second: android.graphics.Rect): Float {
        val overlap = (min(first.bottom, second.bottom) - max(first.top, second.top)).coerceAtLeast(0)
        return overlap.toFloat() / min(first.rectHeight(), second.rectHeight()).coerceAtLeast(1)
    }

    private fun android.graphics.Rect.rectWidth(): Int = right - left

    private fun android.graphics.Rect.rectHeight(): Int = bottom - top

    private fun median(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle].toFloat()
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }
}
