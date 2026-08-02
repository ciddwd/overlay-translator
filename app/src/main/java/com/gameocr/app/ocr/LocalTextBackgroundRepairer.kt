package com.gameocr.app.ocr

/** Runs coverage-first repair independently for each model-free local glyph mask. */
internal object LocalTextBackgroundRepairer {

    data class RejectionDiagnostic(
        val blockIndex: Int,
        val componentIndex: Int,
        val reason: MaskedBackgroundRepairer.Reason,
        val erasePixels: Int,
        val boundarySamples: Int,
        val dominantInlierFraction: Float,
        val colorSpread: Float,
    )

    data class BlockRepair(
        val blockIndex: Int,
        val mask: TextPixelMaskBuilder.BlockMask,
        val feathering: TextRepairFeathering.Plan,
        val repairedPixels: Int,
        val acceptedComponentCount: Int,
        val componentCount: Int,
        val patchPixels: IntArray?,
        val decisions: List<MaskedBackgroundRepairer.ComponentDecision>,
    ) {
        val fullyRepaired: Boolean
            get() = componentCount > 0 &&
                acceptedComponentCount == componentCount &&
                repairedPixels > 0 &&
                patchPixels != null

        val publishable: Boolean
            get() = acceptedComponentCount > 0 && repairedPixels > 0 && patchPixels != null
    }

    data class Result(
        val blocks: List<BlockRepair>,
    ) {
        val fullyRepairedBlockCount: Int
            get() = blocks.count { it.fullyRepaired }

        val publishableBlockCount: Int
            get() = blocks.count { it.publishable }

        val repairedPixelCount: Int
            get() = blocks.sumOf { it.repairedPixels }

        val totalWorkingPixels: Int
            get() = blocks.sumOf { it.mask.pixels.size }
    }

    fun rejectionDiagnostics(result: Result): List<RejectionDiagnostic> =
        result.blocks.flatMap { block ->
            block.decisions
                .asSequence()
                .filterNot { it.accepted }
                .map { decision ->
                    RejectionDiagnostic(
                        blockIndex = block.blockIndex,
                        componentIndex = decision.componentIndex,
                        reason = decision.reason,
                        erasePixels = decision.erasePixels,
                        boundarySamples = decision.boundarySamples,
                        dominantInlierFraction = decision.dominantInlierFraction,
                        colorSpread = decision.colorSpread,
                    )
                }
                .toList()
        }

    fun repair(
        imageWidth: Int,
        imageHeight: Int,
        sourceArgb: IntArray,
        masks: List<TextPixelMaskBuilder.BlockMask>,
        coordinateScale: Float = 1f,
    ): Result {
        require(imageWidth > 0 && imageHeight > 0)
        require(sourceArgb.size == imageWidth * imageHeight)
        require(coordinateScale > 0f)
        val blocks = masks.map { mask ->
            val bounds = mask.bounds
            val localSource = IntArray(bounds.width * bounds.height)
            for (localY in 0 until bounds.height) {
                val sourceOffset = (bounds.top + localY) * imageWidth + bounds.left
                val targetOffset = localY * bounds.width
                sourceArgb.copyInto(
                    destination = localSource,
                    destinationOffset = targetOffset,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + bounds.width,
                )
            }
            val feathering = TextRepairFeathering.plan(
                width = bounds.width,
                height = bounds.height,
                baseMask = mask.pixels,
                coreMask = mask.corePixels,
                coordinateScale = coordinateScale,
            )
            val repair = MaskedBackgroundRepairer.repair(
                width = bounds.width,
                height = bounds.height,
                sourceArgb = localSource,
                eraseMask = feathering.repairMask,
                allowedSampleMask = BooleanArray(localSource.size) { true },
                allowDirectionalInterpolation = true,
                allowComplexBackgroundInterpolation = true,
                foregroundReferenceMask = mask.corePixels,
            )
            val publishable = repair.acceptedComponentCount > 0 && repair.repairedPixelCount > 0
            val patch = if (publishable) {
                IntArray(localSource.size) { index ->
                    if (repair.repairedMask[index]) {
                        TextRepairFeathering.applyAlpha(
                            color = repair.pixels[index],
                            maskAlpha = feathering.alpha[index],
                        )
                    } else {
                        0
                    }
                }
            } else {
                null
            }
            BlockRepair(
                blockIndex = mask.blockIndex,
                mask = mask,
                feathering = feathering,
                repairedPixels = repair.repairedPixelCount,
                acceptedComponentCount = repair.acceptedComponentCount,
                componentCount = repair.decisions.size,
                patchPixels = patch,
                decisions = repair.decisions,
            )
        }
        return Result(blocks)
    }
}
