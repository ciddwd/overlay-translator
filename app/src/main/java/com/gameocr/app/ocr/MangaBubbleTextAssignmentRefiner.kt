package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect

/**
 * Uses RT-DETR's text-in-bubble class to stop a broad bubble box from absorbing nearby sound
 * effects. A bubble without model text evidence keeps its original assignments.
 */
internal object MangaBubbleTextAssignmentRefiner {
    data class Result(
        val assignments: List<Int?>,
        val excludedMemberIndices: Set<Int>,
    )

    fun refine(
        memberBounds: List<IntRect>,
        bubbleDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        textDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        modelByMember: List<Int?>,
    ): Result {
        require(memberBounds.size == modelByMember.size)
        val evidenceByBubble = bubbleDetections.indices.associateWith { bubbleIndex ->
            val bubble = bubbleDetections[bubbleIndex]
            textDetections.filter { text ->
                text.kind == MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE &&
                    overlaps(bubble, text)
            }
        }
        val excludedMemberIndices = mutableSetOf<Int>()
        val assignments = modelByMember.mapIndexed { memberIndex, modelIndex ->
            val usableModelIndex = modelIndex?.takeIf(bubbleDetections.indices::contains)
                ?: return@mapIndexed null
            val evidence = evidenceByBubble[usableModelIndex].orEmpty()
            if (evidence.isEmpty()) {
                usableModelIndex
            } else {
                if (evidence.any { text -> supports(memberBounds[memberIndex], text) }) {
                    usableModelIndex
                } else {
                    // This member was captured only by a broad bubble shape. Treating it as
                    // unassigned would send it through free-text OCR and reintroduce the exact
                    // sound-effect/panel fragment that the text evidence rejected.
                    excludedMemberIndices += memberIndex
                    null
                }
            }
        }
        return Result(
            assignments = assignments,
            excludedMemberIndices = excludedMemberIndices,
        )
    }

    private fun supports(
        member: IntRect,
        text: MangaBubbleDetectionPostprocessor.Detection,
    ): Boolean {
        val centerX = (member.left + member.right) / 2f
        val centerY = (member.top + member.bottom) / 2f
        val centerInside =
            centerX >= text.left && centerX < text.right &&
                centerY >= text.top && centerY < text.bottom
        val intersectionWidth =
            (minOf(member.right.toFloat(), text.right) - maxOf(member.left.toFloat(), text.left))
                .coerceAtLeast(0f)
        val intersectionHeight =
            (minOf(member.bottom.toFloat(), text.bottom) - maxOf(member.top.toFloat(), text.top))
                .coerceAtLeast(0f)
        val memberArea = (member.width.toFloat() * member.height).coerceAtLeast(1f)
        val coverage = intersectionWidth * intersectionHeight / memberArea
        return centerInside || coverage >= MIN_MEMBER_TEXT_COVERAGE
    }

    private fun overlaps(
        first: MangaBubbleDetectionPostprocessor.Detection,
        second: MangaBubbleDetectionPostprocessor.Detection,
    ): Boolean =
        minOf(first.right, second.right) > maxOf(first.left, second.left) &&
            minOf(first.bottom, second.bottom) > maxOf(first.top, second.top)

    private const val MIN_MEMBER_TEXT_COVERAGE: Float = 0.35f
}
