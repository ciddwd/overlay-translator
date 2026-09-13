package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Builds an erase mask only for OCR members whose translations completed successfully.
 *
 * This is deliberately pure Kotlin and debug-only at the current stage. A rejected block is
 * reported as [Decision.useLegacyFallback] so production rendering can keep its existing safe
 * rectangle path until the mask has been validated on representative captures.
 */
internal object DelayedTextEraseMaskBuilder {

    data class ConfirmedBlock(
        val blockIndex: Int,
        val sourceBoxes: List<IntRect>,
        val semanticBounds: IntRect? = null,
    )

    enum class Reason {
        ACCEPTED,
        NO_SOURCE_BOXES,
        MEMBER_UNMATCHED,
        MEMBER_AMBIGUOUS,
        MODEL_GROUP_UNAVAILABLE,
        MODEL_MASK_UNAVAILABLE,
        TEXT_CORE_EMPTY,
        MODEL_MASK_COVERAGE_LOW,
    }

    data class Decision(
        val blockIndex: Int,
        val accepted: Boolean,
        val reason: Reason,
        val memberIndices: List<Int> = emptyList(),
        val modelBubbleIndices: List<Int> = emptyList(),
        val selectedCorePixels: Int = 0,
        val outputPixels: Int = 0,
        val minimumModelCoverage: Float = 0f,
    ) {
        val useLegacyFallback: Boolean
            get() = !accepted
    }

    data class Result(
        val mask: BooleanArray,
        val decisions: List<Decision>,
    ) {
        val acceptedBlockCount: Int
            get() = decisions.count { it.accepted }

        val fallbackBlockCount: Int
            get() = decisions.count { it.useLegacyFallback }
    }

    fun build(
        width: Int,
        height: Int,
        candidateTextMask: BooleanArray,
        memberBounds: List<IntRect>,
        modelGroups: List<BubbleModelRegrouper.Group>,
        modelMasks: List<BubbleSegmentationPostprocessor.InstanceMask>,
        confirmedBlocks: List<ConfirmedBlock>,
    ): Result {
        require(width > 0 && height > 0)
        require(candidateTextMask.size == width * height)
        if (confirmedBlocks.isEmpty()) {
            return Result(BooleanArray(candidateTextMask.size), emptyList())
        }

        val components = labelComponents(
            width = width,
            height = height,
            candidate = candidateTextMask,
        )
        val memberToModel = buildMemberToModelMap(modelGroups)
        val output = BooleanArray(candidateTextMask.size)
        val decisions = confirmedBlocks.map { block ->
            buildBlock(
                width = width,
                height = height,
                block = block,
                memberBounds = memberBounds,
                memberToModel = memberToModel,
                modelMasks = modelMasks,
                components = components,
                output = output,
            )
        }
        return Result(mask = output, decisions = decisions)
    }

    private fun buildBlock(
        width: Int,
        height: Int,
        block: ConfirmedBlock,
        memberBounds: List<IntRect>,
        memberToModel: Map<Int, Int>,
        modelMasks: List<BubbleSegmentationPostprocessor.InstanceMask>,
        components: Components,
        output: BooleanArray,
    ): Decision {
        if (block.sourceBoxes.isEmpty()) {
            return rejected(block, Reason.NO_SOURCE_BOXES)
        }

        val matches = mutableListOf<MemberMatch>()
        block.sourceBoxes.forEach { sourceBox ->
            when (val match = matchMember(sourceBox, memberBounds)) {
                is MemberMatchResult.Matched -> matches += MemberMatch(
                    sourceBox = clamp(sourceBox, width, height),
                    memberIndex = match.memberIndex,
                )
                MemberMatchResult.Ambiguous -> return rejected(block, Reason.MEMBER_AMBIGUOUS)
                MemberMatchResult.Unmatched -> return rejected(block, Reason.MEMBER_UNMATCHED)
            }
        }

        val modelIndices = matches.map { match ->
            memberToModel[match.memberIndex]
                ?: return rejected(
                    block = block,
                    reason = Reason.MODEL_GROUP_UNAVAILABLE,
                    memberIndices = matches.map(MemberMatch::memberIndex),
                )
        }
        if (modelIndices.any { it !in modelMasks.indices }) {
            return rejected(
                block = block,
                reason = Reason.MODEL_MASK_UNAVAILABLE,
                memberIndices = matches.map(MemberMatch::memberIndex),
                modelBubbleIndices = modelIndices,
            )
        }

        val selections = mutableListOf<ComponentSelection>()
        var minimumCoverage = 1f
        matches.zip(modelIndices).forEach { (match, modelIndex) ->
            val source = match.sourceBox
            if (source.width <= 0 || source.height <= 0) {
                return rejected(
                    block = block,
                    reason = Reason.MEMBER_UNMATCHED,
                    memberIndices = matches.map(MemberMatch::memberIndex),
                    modelBubbleIndices = modelIndices,
                )
            }
            val searchRadius = componentSearchRadius(source)
            val searchBounds = expand(source, searchRadius, width, height)
            val labels = collectLabels(
                labels = components.labels,
                width = width,
                bounds = searchBounds,
            )
            val selected = labels.mapNotNull { label ->
                val component = components.items.getOrNull(label) ?: return@mapNotNull null
                if (!isComponentRelated(component, source, searchRadius)) return@mapNotNull null
                ComponentSelection(
                    label = label,
                    dilationRadius = dilationRadius(source),
                    modelIndex = modelIndex,
                )
            }
            if (selected.isEmpty()) {
                return rejected(
                    block = block,
                    reason = Reason.TEXT_CORE_EMPTY,
                    memberIndices = matches.map(MemberMatch::memberIndex),
                    modelBubbleIndices = modelIndices,
                )
            }
            val modelMask = modelMasks[modelIndex]
            val selectedCoreCount = selected.sumOf { selection ->
                components.items[selection.label].pixelCount
            }
            val insideModelCount = selected.sumOf { selection ->
                countComponentInsideModelMask(
                    component = components.items[selection.label],
                    componentLabel = selection.label,
                    labels = components.labels,
                    width = width,
                    modelMask = modelMask,
                )
            }
            val coverage = if (selectedCoreCount == 0) {
                0f
            } else {
                insideModelCount.toFloat() / selectedCoreCount
            }
            minimumCoverage = minOf(minimumCoverage, coverage)
            if (coverage < MIN_MODEL_CORE_COVERAGE) {
                return rejected(
                    block = block,
                    reason = Reason.MODEL_MASK_COVERAGE_LOW,
                    memberIndices = matches.map(MemberMatch::memberIndex),
                    modelBubbleIndices = modelIndices,
                    minimumModelCoverage = minimumCoverage,
                )
            }
            selections += selected
        }

        val uniqueSelections = selections.distinctBy { selection ->
            selection.label to selection.modelIndex
        }
        val beforePixels = output.countTrue()
        uniqueSelections.forEach { selection ->
            dilateComponentInto(
                output = output,
                width = width,
                height = height,
                labels = components.labels,
                component = components.items[selection.label],
                componentLabel = selection.label,
                radius = selection.dilationRadius,
                modelMask = modelMasks[selection.modelIndex],
            )
        }
        val outputPixels = output.countTrue() - beforePixels
        return Decision(
            blockIndex = block.blockIndex,
            accepted = true,
            reason = Reason.ACCEPTED,
            memberIndices = matches.map(MemberMatch::memberIndex).distinct(),
            modelBubbleIndices = modelIndices.distinct(),
            selectedCorePixels = uniqueSelections.sumOf { selection ->
                components.items[selection.label].pixelCount
            },
            outputPixels = outputPixels.coerceAtLeast(0),
            minimumModelCoverage = minimumCoverage,
        )
    }

    private fun buildMemberToModelMap(
        groups: List<BubbleModelRegrouper.Group>,
    ): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        groups.forEach { group ->
            val modelIndex = group.modelBubbleIndex
            if (group.source != BubbleModelRegrouper.Source.MODEL || modelIndex == null) {
                return@forEach
            }
            group.memberIndices.forEach { memberIndex ->
                if (memberIndex !in result) result[memberIndex] = modelIndex
            }
        }
        return result
    }

    private sealed interface MemberMatchResult {
        data class Matched(val memberIndex: Int) : MemberMatchResult
        data object Ambiguous : MemberMatchResult
        data object Unmatched : MemberMatchResult
    }

    private fun matchMember(
        source: IntRect,
        members: List<IntRect>,
    ): MemberMatchResult {
        val scored = members.mapIndexedNotNull { index, member ->
            val coverage = smallerRectCoverage(source, member)
            if (coverage < MIN_MEMBER_MATCH_COVERAGE) null else index to coverage
        }.sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return MemberMatchResult.Unmatched
        val second = scored.getOrNull(1)
        if (second != null && best.second - second.second < MIN_MEMBER_SCORE_MARGIN) {
            return MemberMatchResult.Ambiguous
        }
        return MemberMatchResult.Matched(best.first)
    }

    private fun smallerRectCoverage(first: IntRect, second: IntRect): Float {
        val intersectionWidth = (
            minOf(first.right, second.right) - maxOf(first.left, second.left)
            ).coerceAtLeast(0)
        val intersectionHeight = (
            minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)
            ).coerceAtLeast(0)
        val intersection = intersectionWidth.toLong() * intersectionHeight
        val denominator = minOf(first.area(), second.area())
        return if (denominator <= 0L) 0f else intersection.toFloat() / denominator
    }

    private data class MemberMatch(
        val sourceBox: IntRect,
        val memberIndex: Int,
    )

    private data class ComponentSelection(
        val label: Int,
        val dilationRadius: Int,
        val modelIndex: Int,
    )

    private data class Component(
        val bounds: IntRect,
        val pixelCount: Int,
    )

    private data class Components(
        val labels: IntArray,
        val items: List<Component>,
    )

    private fun labelComponents(
        width: Int,
        height: Int,
        candidate: BooleanArray,
    ): Components {
        val labels = IntArray(candidate.size) { UNLABELED }
        val queue = IntArray(candidate.size)
        val items = mutableListOf<Component>()
        for (start in candidate.indices) {
            if (!candidate[start] || labels[start] != UNLABELED) continue
            val label = items.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            var left = start % width
            var right = left + 1
            var top = start / width
            var bottom = top + 1
            var pixels = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                pixels++
                left = minOf(left, x)
                right = maxOf(right, x + 1)
                top = minOf(top, y)
                bottom = maxOf(bottom, y + 1)
                if (x > 0) tail = enqueue(index - 1, label, candidate, labels, queue, tail)
                if (x + 1 < width) tail = enqueue(index + 1, label, candidate, labels, queue, tail)
                if (y > 0) tail = enqueue(index - width, label, candidate, labels, queue, tail)
                if (y + 1 < height) tail = enqueue(index + width, label, candidate, labels, queue, tail)
            }
            items += Component(
                bounds = IntRect(left, top, right, bottom),
                pixelCount = pixels,
            )
        }
        return Components(labels = labels, items = items)
    }

    private fun enqueue(
        index: Int,
        label: Int,
        candidate: BooleanArray,
        labels: IntArray,
        queue: IntArray,
        tail: Int,
    ): Int {
        if (!candidate[index] || labels[index] != UNLABELED) return tail
        labels[index] = label
        queue[tail] = index
        return tail + 1
    }

    private fun collectLabels(
        labels: IntArray,
        width: Int,
        bounds: IntRect,
    ): Set<Int> {
        val result = linkedSetOf<Int>()
        for (y in bounds.top until bounds.bottom) {
            val row = y * width
            for (x in bounds.left until bounds.right) {
                val label = labels[row + x]
                if (label != UNLABELED) result += label
            }
        }
        return result
    }

    private fun isComponentRelated(
        component: Component,
        source: IntRect,
        searchRadius: Int,
    ): Boolean {
        val expanded = IntRect(
            source.left - searchRadius,
            source.top - searchRadius,
            source.right + searchRadius,
            source.bottom + searchRadius,
        )
        val intersects = component.bounds.left < expanded.right &&
            component.bounds.right > expanded.left &&
            component.bounds.top < expanded.bottom &&
            component.bounds.bottom > expanded.top
        if (!intersects) return false
        return component.pixelCount <= max(
            MAX_COMPONENT_ABSOLUTE_AREA,
            ceil(source.area() * MAX_COMPONENT_TO_SOURCE_AREA_RATIO).toInt(),
        )
    }

    private fun countComponentInsideModelMask(
        component: Component,
        componentLabel: Int,
        labels: IntArray,
        width: Int,
        modelMask: BubbleSegmentationPostprocessor.InstanceMask,
    ): Int {
        var count = 0
        for (y in component.bounds.top until component.bounds.bottom) {
            val row = y * width
            for (x in component.bounds.left until component.bounds.right) {
                if (labels[row + x] == componentLabel && modelMask.contains(x, y)) count++
            }
        }
        return count
    }

    private fun dilateComponentInto(
        output: BooleanArray,
        width: Int,
        height: Int,
        labels: IntArray,
        component: Component,
        componentLabel: Int,
        radius: Int,
        modelMask: BubbleSegmentationPostprocessor.InstanceMask,
    ) {
        for (y in component.bounds.top until component.bounds.bottom) {
            val row = y * width
            for (x in component.bounds.left until component.bounds.right) {
                if (labels[row + x] != componentLabel) continue
                for (dy in -radius..radius) {
                    val targetY = y + dy
                    if (targetY !in 0 until height) continue
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > radius * radius) continue
                        val targetX = x + dx
                        if (targetX !in 0 until width) continue
                        if (modelMask.contains(targetX, targetY)) {
                            output[targetY * width + targetX] = true
                        }
                    }
                }
            }
        }
    }

    private fun componentSearchRadius(source: IntRect): Int =
        (minOf(source.width, source.height) * COMPONENT_SEARCH_RATIO)
            .roundToInt()
            .coerceIn(MIN_COMPONENT_SEARCH_PX, MAX_COMPONENT_SEARCH_PX)

    private fun dilationRadius(source: IntRect): Int =
        (minOf(source.width, source.height) * DILATION_RATIO)
            .roundToInt()
            .coerceIn(MIN_DILATION_PX, MAX_DILATION_PX)

    private fun expand(
        rect: IntRect,
        margin: Int,
        width: Int,
        height: Int,
    ): IntRect = IntRect(
        left = (rect.left - margin).coerceIn(0, width),
        top = (rect.top - margin).coerceIn(0, height),
        right = (rect.right + margin).coerceIn(0, width),
        bottom = (rect.bottom + margin).coerceIn(0, height),
    )

    private fun clamp(rect: IntRect, width: Int, height: Int): IntRect = IntRect(
        left = rect.left.coerceIn(0, width),
        top = rect.top.coerceIn(0, height),
        right = rect.right.coerceIn(0, width),
        bottom = rect.bottom.coerceIn(0, height),
    )

    private fun IntRect.area(): Long =
        width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0)

    private fun BooleanArray.countTrue(): Int {
        var count = 0
        forEach { value -> if (value) count++ }
        return count
    }

    private fun rejected(
        block: ConfirmedBlock,
        reason: Reason,
        memberIndices: List<Int> = emptyList(),
        modelBubbleIndices: List<Int> = emptyList(),
        minimumModelCoverage: Float = 0f,
    ): Decision = Decision(
        blockIndex = block.blockIndex,
        accepted = false,
        reason = reason,
        memberIndices = memberIndices.distinct(),
        modelBubbleIndices = modelBubbleIndices.distinct(),
        minimumModelCoverage = minimumModelCoverage,
    )

    private const val UNLABELED = -1
    private const val MIN_MEMBER_MATCH_COVERAGE = 0.72f
    private const val MIN_MEMBER_SCORE_MARGIN = 0.08f
    private const val MIN_MODEL_CORE_COVERAGE = 0.86f
    private const val COMPONENT_SEARCH_RATIO = 0.08f
    private const val MIN_COMPONENT_SEARCH_PX = 1
    private const val MAX_COMPONENT_SEARCH_PX = 12
    private const val DILATION_RATIO = 0.075f
    private const val MIN_DILATION_PX = 1
    private const val MAX_DILATION_PX = 8
    private const val MAX_COMPONENT_ABSOLUTE_AREA = 64
    private const val MAX_COMPONENT_TO_SOURCE_AREA_RATIO = 1.5f
}
