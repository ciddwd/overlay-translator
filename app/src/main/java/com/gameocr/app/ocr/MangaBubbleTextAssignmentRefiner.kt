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
        val freeTextExcludedMemberIndices: Set<Int>,
        val ambiguousFreeTextMemberIndices: Set<Int>,
        val conflictingTextKindMemberIndices: Set<Int>,
    )

    fun refine(
        memberBounds: List<IntRect>,
        bubbleDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        textDetections: List<MangaBubbleDetectionPostprocessor.Detection>,
        modelByMember: List<Int?>,
    ): Result {
        require(memberBounds.size == modelByMember.size)
        val memberDecisions = MangaTextEvidenceMatcher.classifyMembers(
            memberBounds = memberBounds,
            bubbleDetections = bubbleDetections,
            textDetections = textDetections,
            modelByMember = modelByMember,
        )
        val freeTextExcludedMemberIndices = memberDecisions
            .filter { decision ->
                decision.action == MangaTextEvidenceMatcher.MemberAction.EXCLUDE_FREE_ONLY
            }
            .mapTo(mutableSetOf(), MangaTextEvidenceMatcher.MemberDecision::memberIndex)
        val ambiguousFreeTextMemberIndices = memberDecisions
            .filter { decision ->
                decision.action ==
                    MangaTextEvidenceMatcher.MemberAction.KEEP_AMBIGUOUS_INSIDE_BUBBLE
            }
            .mapTo(mutableSetOf(), MangaTextEvidenceMatcher.MemberDecision::memberIndex)
        val conflictingTextKindMemberIndices = memberDecisions
            .filter { decision ->
                decision.action == MangaTextEvidenceMatcher.MemberAction.KEEP_KIND_CONFLICT
            }
            .mapTo(mutableSetOf(), MangaTextEvidenceMatcher.MemberDecision::memberIndex)
        val evidenceByBubble = bubbleDetections.indices.associateWith { bubbleIndex ->
            val bubble = bubbleDetections[bubbleIndex]
            textDetections.filter { text ->
                text.kind == MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE &&
                    overlaps(bubble, text)
            }
        }
        val excludedMemberIndices = freeTextExcludedMemberIndices.toMutableSet()
        val assignments = modelByMember.mapIndexed { memberIndex, modelIndex ->
            if (memberIndex in freeTextExcludedMemberIndices) {
                return@mapIndexed null
            }
            val usableModelIndex = modelIndex?.takeIf(bubbleDetections.indices::contains)
                ?: return@mapIndexed null
            val evidence = evidenceByBubble[usableModelIndex].orEmpty()
            if (evidence.isEmpty()) {
                usableModelIndex
            } else {
                if (evidence.any { text ->
                        MangaTextEvidenceMatcher.supports(memberBounds[memberIndex], text)
                    }
                ) {
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
            freeTextExcludedMemberIndices = freeTextExcludedMemberIndices,
            ambiguousFreeTextMemberIndices = ambiguousFreeTextMemberIndices,
            conflictingTextKindMemberIndices = conflictingTextKindMemberIndices,
        )
    }

    private fun overlaps(
        first: MangaBubbleDetectionPostprocessor.Detection,
        second: MangaBubbleDetectionPostprocessor.Detection,
    ): Boolean =
        minOf(first.right, second.right) > maxOf(first.left, second.left) &&
            minOf(first.bottom, second.bottom) > maxOf(first.top, second.top)
}
