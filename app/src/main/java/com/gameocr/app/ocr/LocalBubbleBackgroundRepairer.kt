package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import kotlin.math.roundToInt

/**
 * Runs the conservative background repairer on small, per-bubble working images.
 *
 * Only the final debug preview and repaired mask are full-frame. Component labelling, boundary
 * sampling, completion and all other scratch buffers are allocated for one bubble crop at a time.
 */
internal object LocalBubbleBackgroundRepairer {

    data class Region(
        val modelBubbleIndex: Int,
        val memberBounds: List<IntRect>,
        val modelMask: BubbleSegmentationPostprocessor.InstanceMask,
    ) {
        init {
            require(memberBounds.isNotEmpty())
        }
    }

    data class CropMetric(
        val modelBubbleIndex: Int,
        val bounds: IntRect,
        val erasePixels: Int,
        val repairedPixels: Int,
        val acceptedComponentCount: Int,
        val componentCount: Int,
    ) {
        val workingPixels: Int
            get() = bounds.width * bounds.height

        val fullyRepaired: Boolean
            get() = componentCount > 0 &&
                acceptedComponentCount == componentCount &&
                repairedPixels > 0
    }

    data class Result(
        val repairResult: MaskedBackgroundRepairer.Result,
        val crops: List<CropMetric>,
        val fullFramePixels: Int,
    ) {
        val cropCount: Int
            get() = crops.size

        val totalWorkingPixels: Int
            get() = crops.sumOf { it.workingPixels }

        val maximumCropPixels: Int
            get() = crops.maxOfOrNull { it.workingPixels } ?: 0

        val workingPixelRatio: Float
            get() = if (fullFramePixels == 0) {
                0f
            } else {
                totalWorkingPixels.toFloat() / fullFramePixels
            }
    }

    internal fun fullyRepairedModelIndices(crops: List<CropMetric>): Set<Int> =
        crops.asSequence()
            .filter(CropMetric::fullyRepaired)
            .map(CropMetric::modelBubbleIndex)
            .toSet()

    fun repair(
        width: Int,
        height: Int,
        sourceArgb: IntArray,
        eraseMask: BooleanArray,
        regions: List<Region>,
    ): Result {
        require(width > 0 && height > 0)
        require(sourceArgb.size == width * height)
        require(eraseMask.size == sourceArgb.size)
        require(regions.map { it.modelBubbleIndex }.distinct().size == regions.size) {
            "Each model bubble must have exactly one local repair region"
        }

        val output = sourceArgb.copyOf()
        val repairedMask = BooleanArray(sourceArgb.size)
        val decisions = mutableListOf<MaskedBackgroundRepairer.ComponentDecision>()
        val cropMetrics = mutableListOf<CropMetric>()
        var componentOffset = 0

        regions.sortedBy { it.modelBubbleIndex }.forEach { region ->
            val cropBounds = planCrop(
                imageWidth = width,
                imageHeight = height,
                memberBounds = region.memberBounds,
                modelMask = region.modelMask,
            ) ?: return@forEach
            val cropWidth = cropBounds.width
            val cropHeight = cropBounds.height
            val cropPixels = cropWidth * cropHeight
            val localSource = IntArray(cropPixels)
            val localErase = BooleanArray(cropPixels)
            val localAllowed = BooleanArray(cropPixels)

            var erasePixelCount = 0
            for (localY in 0 until cropHeight) {
                val globalY = cropBounds.top + localY
                val globalRow = globalY * width
                val localRow = localY * cropWidth
                for (localX in 0 until cropWidth) {
                    val globalX = cropBounds.left + localX
                    val globalIndex = globalRow + globalX
                    val localIndex = localRow + localX
                    localSource[localIndex] = sourceArgb[globalIndex]
                    val allowed = region.modelMask.contains(globalX, globalY)
                    localAllowed[localIndex] = allowed
                    if (allowed && eraseMask[globalIndex]) {
                        localErase[localIndex] = true
                        erasePixelCount++
                    }
                }
            }
            if (erasePixelCount == 0) return@forEach

            val localCompletion = buildCompletionMask(
                cropBounds = cropBounds,
                eraseMask = localErase,
                allowedMask = localAllowed,
                memberBounds = region.memberBounds,
            )
            val localResult = MaskedBackgroundRepairer.repair(
                width = cropWidth,
                height = cropHeight,
                sourceArgb = localSource,
                eraseMask = localErase,
                allowedSampleMask = localAllowed,
                flatCompletionMask = localCompletion,
            )
            for (localY in 0 until cropHeight) {
                val globalRow = (cropBounds.top + localY) * width
                val localRow = localY * cropWidth
                for (localX in 0 until cropWidth) {
                    val localIndex = localRow + localX
                    if (!localResult.repairedMask[localIndex]) continue
                    val globalIndex = globalRow + cropBounds.left + localX
                    if (!repairedMask[globalIndex]) {
                        output[globalIndex] = localResult.pixels[localIndex]
                        repairedMask[globalIndex] = true
                    }
                }
            }
            decisions += localResult.decisions.map { decision ->
                decision.copy(componentIndex = componentOffset + decision.componentIndex)
            }
            componentOffset += localResult.decisions.size
            cropMetrics += CropMetric(
                modelBubbleIndex = region.modelBubbleIndex,
                bounds = cropBounds,
                erasePixels = erasePixelCount,
                repairedPixels = localResult.repairedPixelCount,
                acceptedComponentCount = localResult.acceptedComponentCount,
                componentCount = localResult.decisions.size,
            )
        }

        return Result(
            repairResult = MaskedBackgroundRepairer.Result(
                pixels = output,
                repairedMask = repairedMask,
                decisions = decisions,
            ),
            crops = cropMetrics,
            fullFramePixels = width * height,
        )
    }

    internal fun planCrop(
        imageWidth: Int,
        imageHeight: Int,
        memberBounds: List<IntRect>,
        modelMask: BubbleSegmentationPostprocessor.InstanceMask,
    ): IntRect? {
        require(imageWidth > 0 && imageHeight > 0)
        if (memberBounds.isEmpty() || modelMask.width == 0 || modelMask.height == 0) return null
        val content = IntRect(
            left = memberBounds.minOf { it.left },
            top = memberBounds.minOf { it.top },
            right = memberBounds.maxOf { it.right },
            bottom = memberBounds.maxOf { it.bottom },
        )
        val left = maxOf(0, modelMask.left, content.left - SAMPLE_MARGIN_PX)
        val top = maxOf(0, modelMask.top, content.top - SAMPLE_MARGIN_PX)
        val right = minOf(imageWidth, modelMask.right, content.right + SAMPLE_MARGIN_PX)
        val bottom = minOf(imageHeight, modelMask.bottom, content.bottom + SAMPLE_MARGIN_PX)
        return if (right > left && bottom > top) {
            IntRect(left, top, right, bottom)
        } else {
            null
        }
    }

    private fun buildCompletionMask(
        cropBounds: IntRect,
        eraseMask: BooleanArray,
        allowedMask: BooleanArray,
        memberBounds: List<IntRect>,
    ): BooleanArray {
        val width = cropBounds.width
        val height = cropBounds.height
        val completion = eraseMask.copyOf()
        memberBounds.forEach { bounds ->
            val margin = (minOf(bounds.width, bounds.height) * COMPLETION_MARGIN_RATIO)
                .roundToInt()
                .coerceIn(MIN_COMPLETION_MARGIN_PX, MAX_COMPLETION_MARGIN_PX)
            val left = maxOf(cropBounds.left, bounds.left - margin) - cropBounds.left
            val top = maxOf(cropBounds.top, bounds.top - margin) - cropBounds.top
            val right = minOf(cropBounds.right, bounds.right + margin) - cropBounds.left
            val bottom = minOf(cropBounds.bottom, bounds.bottom + margin) - cropBounds.top
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(height)) {
                val row = y * width
                for (x in left.coerceAtLeast(0) until right.coerceAtMost(width)) {
                    val index = row + x
                    if (allowedMask[index]) completion[index] = true
                }
            }
        }
        return completion
    }

    private const val SAMPLE_MARGIN_PX = 12
    private const val COMPLETION_MARGIN_RATIO = 0.04f
    private const val MIN_COMPLETION_MARGIN_PX = 1
    private const val MAX_COMPLETION_MARGIN_PX = 6
}
