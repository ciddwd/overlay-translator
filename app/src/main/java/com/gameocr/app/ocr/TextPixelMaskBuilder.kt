package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Extracts local glyph masks from the detector probability mask without bubble semantics.
 *
 * Every decision is based on connected text pixels and OCR geometry. Model groups, region kinds,
 * translated content and detector failure reasons are intentionally absent from this API.
 */
internal object TextPixelMaskBuilder {

    enum class Reason {
        ACCEPTED,
        NO_SOURCE_BOXES,
        TEXT_CORE_EMPTY,
    }

    data class BlockMask(
        val blockIndex: Int,
        val bounds: IntRect,
        val pixels: BooleanArray,
        val selectedCorePixels: Int,
        val corePixels: BooleanArray = pixels,
        val supportPixels: BooleanArray = pixels,
    ) {
        init {
            require(bounds.width > 0 && bounds.height > 0)
            require(pixels.size == bounds.width * bounds.height)
            require(corePixels.size == pixels.size)
            require(supportPixels.size == pixels.size)
            require(selectedCorePixels > 0)
            require(corePixels.count { it } == selectedCorePixels)
        }

        val outputPixels: Int
            get() = pixels.count { it }
    }

    data class Decision(
        val blockIndex: Int,
        val accepted: Boolean,
        val reason: Reason,
        val selectedCorePixels: Int = 0,
        val outputPixels: Int = 0,
    )

    data class Result(
        val masks: List<BlockMask>,
        val decisions: List<Decision>,
    ) {
        val acceptedBlockCount: Int
            get() = masks.size

        val fallbackBlockCount: Int
            get() = decisions.count { !it.accepted }
    }

    fun build(
        width: Int,
        height: Int,
        candidateTextMask: BooleanArray,
        confirmedBlocks: List<DelayedTextEraseMaskBuilder.ConfirmedBlock>,
    ): Result {
        require(width > 0 && height > 0)
        require(candidateTextMask.size == width * height)
        if (confirmedBlocks.isEmpty()) return Result(emptyList(), emptyList())

        val components = labelComponents(width, height, candidateTextMask)
        val masks = mutableListOf<BlockMask>()
        val decisions = confirmedBlocks.map { block ->
            val sourceBoxes = block.sourceBoxes
                .map { clamp(it, width, height) }
                .filter { it.width > 0 && it.height > 0 }
            if (sourceBoxes.isEmpty()) {
                return@map Decision(
                    blockIndex = block.blockIndex,
                    accepted = false,
                    reason = Reason.NO_SOURCE_BOXES,
                )
            }

            val selected = linkedMapOf<Int, Int>()
            sourceBoxes.forEach { source ->
                val searchRadius = componentSearchRadius(source)
                val searchBounds = expand(source, searchRadius, width, height)
                collectLabels(components.labels, width, searchBounds).forEach labelLoop@ { label ->
                    val component = components.items[label]
                    if (!isComponentRelated(component, source, searchRadius)) return@labelLoop
                    selected[label] = maxOf(selected[label] ?: 0, dilationRadius(source))
                }
            }
            if (selected.isEmpty()) {
                return@map Decision(
                    blockIndex = block.blockIndex,
                    accepted = false,
                    reason = Reason.TEXT_CORE_EMPTY,
                )
            }

            val cropBounds = expand(
                rect = union(sourceBoxes),
                margin = sampleMargin(sourceBoxes),
                width = width,
                height = height,
            )
            val localCoreMask = BooleanArray(cropBounds.width * cropBounds.height)
            val localMask = BooleanArray(cropBounds.width * cropBounds.height)
            val localSupportMask = BooleanArray(cropBounds.width * cropBounds.height)
            sourceBoxes.forEach { source ->
                val support = expand(source, componentSearchRadius(source), width, height)
                for (y in support.top until support.bottom) {
                    val localY = y - cropBounds.top
                    if (localY !in 0 until cropBounds.height) continue
                    for (x in support.left until support.right) {
                        val localX = x - cropBounds.left
                        if (localX !in 0 until cropBounds.width) continue
                        localSupportMask[localY * cropBounds.width + localX] = true
                    }
                }
            }
            selected.forEach { (label, radius) ->
                dilateComponentInto(
                    output = localCoreMask,
                    outputBounds = cropBounds,
                    imageWidth = width,
                    imageHeight = height,
                    labels = components.labels,
                    component = components.items[label],
                    componentLabel = label,
                    radius = 0,
                )
                dilateComponentInto(
                    output = localMask,
                    outputBounds = cropBounds,
                    imageWidth = width,
                    imageHeight = height,
                    labels = components.labels,
                    component = components.items[label],
                    componentLabel = label,
                    radius = radius,
                )
            }
            val outputPixels = localMask.count { it }
            if (outputPixels == 0) {
                return@map Decision(
                    blockIndex = block.blockIndex,
                    accepted = false,
                    reason = Reason.TEXT_CORE_EMPTY,
                )
            }
            val corePixels = localCoreMask.count { it }
            masks += BlockMask(
                blockIndex = block.blockIndex,
                bounds = cropBounds,
                pixels = localMask,
                selectedCorePixels = corePixels,
                corePixels = localCoreMask,
                supportPixels = localSupportMask,
            )
            Decision(
                blockIndex = block.blockIndex,
                accepted = true,
                reason = Reason.ACCEPTED,
                selectedCorePixels = corePixels,
                outputPixels = outputPixels,
            )
        }
        return Result(masks = masks, decisions = decisions)
    }

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
            items += Component(IntRect(left, top, right, bottom), pixels)
        }
        return Components(labels, items)
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
        val output = linkedSetOf<Int>()
        for (y in bounds.top until bounds.bottom) {
            val row = y * width
            for (x in bounds.left until bounds.right) {
                val label = labels[row + x]
                if (label != UNLABELED) output += label
            }
        }
        return output
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
        val maximumRelatedPixels = maxOf(
            MIN_RELATED_COMPONENT_PIXELS,
            ceil(source.area().toDouble() * MAX_COMPONENT_TO_SOURCE_AREA_RATIO).toInt(),
        )
        return component.pixelCount <= maximumRelatedPixels
    }

    private fun dilateComponentInto(
        output: BooleanArray,
        outputBounds: IntRect,
        imageWidth: Int,
        imageHeight: Int,
        labels: IntArray,
        component: Component,
        componentLabel: Int,
        radius: Int,
    ) {
        for (y in component.bounds.top until component.bounds.bottom) {
            val row = y * imageWidth
            for (x in component.bounds.left until component.bounds.right) {
                if (labels[row + x] != componentLabel) continue
                for (dy in -radius..radius) {
                    val targetY = y + dy
                    if (targetY !in 0 until imageHeight || targetY !in outputBounds.top until outputBounds.bottom) {
                        continue
                    }
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > radius * radius) continue
                        val targetX = x + dx
                        if (targetX !in 0 until imageWidth || targetX !in outputBounds.left until outputBounds.right) {
                            continue
                        }
                        val localX = targetX - outputBounds.left
                        val localY = targetY - outputBounds.top
                        output[localY * outputBounds.width + localX] = true
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

    private fun sampleMargin(sourceBoxes: List<IntRect>): Int {
        val minorAxes = sourceBoxes
            .map { minOf(it.width, it.height) }
            .sorted()
        val medianMinorAxis = minorAxes[minorAxes.size / 2]
        return (medianMinorAxis * SAMPLE_MARGIN_RATIO)
            .roundToInt()
            .coerceIn(MIN_SAMPLE_MARGIN_PX, MAX_SAMPLE_MARGIN_PX)
    }

    private fun union(rects: List<IntRect>): IntRect = IntRect(
        left = rects.minOf { it.left },
        top = rects.minOf { it.top },
        right = rects.maxOf { it.right },
        bottom = rects.maxOf { it.bottom },
    )

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

    private const val UNLABELED = -1
    private const val COMPONENT_SEARCH_RATIO = 0.08f
    private const val MIN_COMPONENT_SEARCH_PX = 1
    private const val MAX_COMPONENT_SEARCH_PX = 12
    private const val DILATION_RATIO = 0.075f
    private const val MIN_DILATION_PX = 1
    private const val MAX_DILATION_PX = 8
    private const val SAMPLE_MARGIN_RATIO = 0.35f
    private const val MIN_SAMPLE_MARGIN_PX = 8
    private const val MAX_SAMPLE_MARGIN_PX = 64
    private const val MIN_RELATED_COMPONENT_PIXELS = 64
    private const val MAX_COMPONENT_TO_SOURCE_AREA_RATIO = 1.5
}
