package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.max
import kotlin.math.min

/**
 * Rebuilds OCR recognition groups from validated model associations.
 *
 * Members assigned to the same model bubble become one recognition crop. Every unassigned,
 * ambiguous, or otherwise invalid member remains in the existing geometric cluster path. The
 * caller validates that the resulting groups form a complete partition of all non-excluded
 * members before using them for recognition.
 */
internal object BubbleModelRegrouper {

    enum class Source {
        MODEL,
        LEGACY_FALLBACK,
    }

    data class Group(
        val source: Source,
        val modelBubbleIndex: Int?,
        val cropBounds: IntRect,
        val contentBounds: IntRect,
        val memberIndices: List<Int>,
    )

    fun regroup(
        width: Int,
        height: Int,
        memberBounds: List<IntRect>,
        modelBounds: List<IntRect>,
        associations: List<BubbleMaskAssociator.Association>,
        fallbackPadding: Int,
        fallbackGap: Int,
    ): List<Group> {
        val modelByMember = MutableList<Int?>(memberBounds.size) { null }
        val assigned = BooleanArray(memberBounds.size)
        associations.forEach { association ->
            val memberIndex = association.ocrGroupIndex
            require(memberIndex in memberBounds.indices)
            require(!assigned[memberIndex]) {
                "Duplicate association for OCR member $memberIndex"
            }
            assigned[memberIndex] = true
            modelByMember[memberIndex] = association.modelBubbleIndex
        }
        return regroupByModelAssignments(
            width = width,
            height = height,
            memberBounds = memberBounds,
            modelBounds = modelBounds,
            modelByMember = modelByMember,
            fallbackPadding = fallbackPadding,
            fallbackGap = fallbackGap,
        )
    }

    fun regroupByModelAssignments(
        width: Int,
        height: Int,
        memberBounds: List<IntRect>,
        modelBounds: List<IntRect>,
        modelByMember: List<Int?>,
        fallbackPadding: Int,
        fallbackGap: Int,
        excludedMemberIndices: Set<Int> = emptySet(),
    ): List<Group> {
        require(width > 0 && height > 0)
        require(modelByMember.size == memberBounds.size)
        require(excludedMemberIndices.all(memberBounds.indices::contains))

        val membersByModel = linkedMapOf<Int, MutableList<Int>>()
        val fallbackMemberIndices = mutableListOf<Int>()
        memberBounds.indices.forEach { memberIndex ->
            if (memberIndex in excludedMemberIndices) return@forEach
            val modelIndex = modelByMember[memberIndex]
            val usableModelIndex = modelIndex?.takeIf { index ->
                index in modelBounds.indices && isValid(clamp(modelBounds[index], width, height))
            }
            if (usableModelIndex == null) {
                fallbackMemberIndices += memberIndex
            } else {
                membersByModel.getOrPut(usableModelIndex) { mutableListOf() } += memberIndex
            }
        }

        val modelGroups = membersByModel.map { (modelIndex, memberIndices) ->
            val content = union(memberIndices.map { memberBounds[it] })
            val model = clamp(modelBounds[modelIndex], width, height)
            Group(
                source = Source.MODEL,
                modelBubbleIndex = modelIndex,
                cropBounds = clamp(union(listOf(model, content)), width, height),
                contentBounds = clamp(content, width, height),
                memberIndices = memberIndices.sorted(),
            )
        }
        val fallbackGroups = if (fallbackMemberIndices.isEmpty()) {
            emptyList()
        } else {
            BubbleClusterer.cluster(
                rects = fallbackMemberIndices.map { memberBounds[it] },
                imgW = width,
                imgH = height,
                pad = fallbackPadding,
                gap = fallbackGap,
            ).map { bubble ->
                Group(
                    source = Source.LEGACY_FALLBACK,
                    modelBubbleIndex = null,
                    cropBounds = bubble.rect,
                    contentBounds = bubble.contentRect,
                    memberIndices = bubble.memberIndices.map { fallbackMemberIndices[it] }.sorted(),
                )
            }
        }
        return (modelGroups + fallbackGroups).sortedWith(
            compareBy<Group>(
                { it.contentBounds.top },
                { it.contentBounds.left },
                { it.source.ordinal },
            )
        )
    }

    private fun union(rects: List<IntRect>): IntRect {
        require(rects.isNotEmpty())
        return IntRect(
            left = rects.minOf { it.left },
            top = rects.minOf { it.top },
            right = rects.maxOf { it.right },
            bottom = rects.maxOf { it.bottom },
        )
    }

    private fun clamp(rect: IntRect, width: Int, height: Int): IntRect = IntRect(
        left = min(rect.left.coerceAtLeast(0), width),
        top = min(rect.top.coerceAtLeast(0), height),
        right = max(0, rect.right.coerceAtMost(width)),
        bottom = max(0, rect.bottom.coerceAtMost(height)),
    )

    private fun isValid(rect: IntRect): Boolean = rect.width > 0 && rect.height > 0
}
