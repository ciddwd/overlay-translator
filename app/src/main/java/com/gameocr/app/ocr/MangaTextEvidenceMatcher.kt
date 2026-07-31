package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

/**
 * Matches RT-DETR text evidence to DBNet members and final OCR groups.
 *
 * TEXT_FREE is excluded only when it has no competing bubble-text evidence and the member is not
 * plausibly inside a detected bubble. TEXT_BUBBLE evidence is assigned to one OCR group so a
 * slight overlap cannot expand multiple neighboring recognition crops.
 */
internal object MangaTextEvidenceMatcher {
    enum class MemberAction {
        KEEP,
        KEEP_KIND_CONFLICT,
        KEEP_AMBIGUOUS_INSIDE_BUBBLE,
        EXCLUDE_FREE_ONLY,
    }

    data class MemberDecision(
        val memberIndex: Int,
        val action: MemberAction,
        val textBubbleDetectionIndices: List<Int>,
        val textFreeDetectionIndices: List<Int>,
    )

    data class EntryAssignment(
        val detectionIndex: Int,
        val entryIndex: Int,
        val detectionCoverage: Float,
        val entryCoverage: Float,
        val expansionArea: Double,
    )

    fun classifyMembers(
        memberBounds: List<IntRect>,
        bubbleDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        textDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        modelByMember: List<Int?>,
    ): List<MemberDecision> {
        require(memberBounds.size == modelByMember.size)
        return memberBounds.mapIndexed { memberIndex, member ->
            val textBubbleIndices = supportingDetectionIndices(
                bounds = member,
                detections = textDetections,
                kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE,
            )
            val textFreeIndices = supportingDetectionIndices(
                bounds = member,
                detections = textDetections,
                kind = MangaBubbleDetectionPostprocessor.Kind.TEXT_FREE,
            )
            val insideBubble =
                modelByMember[memberIndex]?.let(bubbleDetections.indices::contains) == true ||
                    bubbleDetections.any { detection ->
                        detection.kind == MangaBubbleDetectionPostprocessor.Kind.BUBBLE &&
                            supportsEitherDirection(member, detection)
                    }
            val action = when {
                textFreeIndices.isEmpty() -> MemberAction.KEEP
                textBubbleIndices.isNotEmpty() -> MemberAction.KEEP_KIND_CONFLICT
                insideBubble -> MemberAction.KEEP_AMBIGUOUS_INSIDE_BUBBLE
                else -> MemberAction.EXCLUDE_FREE_ONLY
            }
            MemberDecision(
                memberIndex = memberIndex,
                action = action,
                textBubbleDetectionIndices = textBubbleIndices,
                textFreeDetectionIndices = textFreeIndices,
            )
        }
    }

    fun assignTextBubbleDetections(
        entries: List<MangaOcrBubbleGroupingPolicy.Entry>,
        textDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
    ): List<EntryAssignment> = textDetections.mapIndexedNotNull { detectionIndex, detection ->
        if (detection.kind != MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE) {
            return@mapIndexedNotNull null
        }
        entries.mapIndexedNotNull { entryIndex, entry ->
            if (entry.guidedSource != BubbleModelRegrouper.Source.MODEL) {
                return@mapIndexedNotNull null
            }
            matchScore(entry.bubble.contentRect, detection)?.let { score ->
                EntryAssignment(
                    detectionIndex = detectionIndex,
                    entryIndex = entryIndex,
                    detectionCoverage = score.detectionCoverage,
                    entryCoverage = score.entryCoverage,
                    expansionArea = score.expansionArea,
                )
            }
        }.sortedWith(
            compareByDescending<EntryAssignment>(EntryAssignment::detectionCoverage)
                .thenByDescending(EntryAssignment::entryCoverage)
                .thenBy(EntryAssignment::expansionArea)
                .thenBy(EntryAssignment::entryIndex),
        ).firstOrNull()
    }

    fun supports(
        bounds: IntRect,
        detection: MangaBubbleDetectionPostprocessor.Detection,
    ): Boolean {
        val centerX = (bounds.left + bounds.right) / 2f
        val centerY = (bounds.top + bounds.bottom) / 2f
        val centerInside =
            centerX >= detection.left && centerX < detection.right &&
                centerY >= detection.top && centerY < detection.bottom
        return centerInside || entryCoverage(bounds, detection) >= MIN_BOUNDS_COVERAGE
    }

    private fun supportingDetectionIndices(
        bounds: IntRect,
        detections: List<MangaBubbleDetectionPostprocessor.Detection>,
        kind: MangaBubbleDetectionPostprocessor.Kind,
    ): List<Int> = detections.mapIndexedNotNull { index, detection ->
        index.takeIf {
            detection.kind == kind && supportsEitherDirection(bounds, detection)
        }
    }

    private fun supportsEitherDirection(
        bounds: IntRect,
        detection: MangaBubbleDetectionPostprocessor.Detection,
    ): Boolean =
        supports(bounds, detection) ||
            detectionCenterInside(detection, bounds) ||
            detectionCoverage(bounds, detection) >= MIN_BOUNDS_COVERAGE

    private fun matchScore(
        bounds: IntRect,
        detection: MangaBubbleDetectionPostprocessor.Detection,
    ): MatchScore? {
        if (!supportsEitherDirection(bounds, detection)) return null
        return MatchScore(
            detectionCoverage = detectionCoverage(bounds, detection),
            entryCoverage = entryCoverage(bounds, detection),
            expansionArea = expansionArea(bounds, detection),
        )
    }

    private fun detectionCenterInside(
        detection: MangaBubbleDetectionPostprocessor.Detection,
        bounds: IntRect,
    ): Boolean {
        val centerX = (detection.left + detection.right) / 2f
        val centerY = (detection.top + detection.bottom) / 2f
        return centerX >= bounds.left && centerX < bounds.right &&
            centerY >= bounds.top && centerY < bounds.bottom
    }

    private fun entryCoverage(
        bounds: IntRect,
        detection: MangaBubbleDetectionPostprocessor.Detection,
    ): Float = intersectionArea(bounds, detection) /
        (bounds.width.toFloat() * bounds.height).coerceAtLeast(1f)

    private fun detectionCoverage(
        bounds: IntRect,
        detection: MangaBubbleDetectionPostprocessor.Detection,
    ): Float = intersectionArea(bounds, detection) /
        ((detection.right - detection.left) * (detection.bottom - detection.top)).coerceAtLeast(1f)

    private fun intersectionArea(
        bounds: IntRect,
        detection: MangaBubbleDetectionPostprocessor.Detection,
    ): Float {
        val width =
            (minOf(bounds.right.toFloat(), detection.right) -
                maxOf(bounds.left.toFloat(), detection.left)).coerceAtLeast(0f)
        val height =
            (minOf(bounds.bottom.toFloat(), detection.bottom) -
                maxOf(bounds.top.toFloat(), detection.top)).coerceAtLeast(0f)
        return width * height
    }

    private fun expansionArea(
        bounds: IntRect,
        detection: MangaBubbleDetectionPostprocessor.Detection,
    ): Double {
        val unionWidth =
            maxOf(bounds.right.toFloat(), detection.right) -
                minOf(bounds.left.toFloat(), detection.left)
        val unionHeight =
            maxOf(bounds.bottom.toFloat(), detection.bottom) -
                minOf(bounds.top.toFloat(), detection.top)
        val boundsArea = bounds.width.toDouble() * bounds.height
        return (unionWidth.toDouble() * unionHeight - boundsArea).coerceAtLeast(0.0)
    }

    private data class MatchScore(
        val detectionCoverage: Float,
        val entryCoverage: Float,
        val expansionArea: Double,
    )

    internal const val MIN_BOUNDS_COVERAGE: Float = 0.35f
}
