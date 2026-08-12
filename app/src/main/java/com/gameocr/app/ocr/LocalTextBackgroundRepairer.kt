package com.gameocr.app.ocr

/** Runs coverage-first repair independently for each model-free local glyph mask. */
internal object LocalTextBackgroundRepairer {

    data class Timing(
        val totalUs: Long = 0L,
        val cropCopyUs: Long = 0L,
        val coverageUs: Long = 0L,
        val backgroundRepairUs: Long = 0L,
        val backgroundSampleUs: Long = 0L,
        val foregroundCompleteUs: Long = 0L,
        val residualScanUs: Long = 0L,
        val patchBuildUs: Long = 0L,
        val otherUs: Long = 0L,
    ) {
        val measuredStageUs: Long
            get() = cropCopyUs + coverageUs + backgroundRepairUs + backgroundSampleUs +
                foregroundCompleteUs + residualScanUs + patchBuildUs

        fun toLogString(): String =
            "total=$totalUs,cropCopy=$cropCopyUs,coverage=$coverageUs," +
                "background=$backgroundRepairUs,samples=$backgroundSampleUs," +
                "foreground=$foregroundCompleteUs,residual=$residualScanUs," +
                "patch=$patchBuildUs,other=$otherUs"
    }

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
        val coverage: TextRepairSolidCoverage.Plan,
        val repairedPixels: Int,
        val acceptedComponentCount: Int,
        val componentCount: Int,
        val patchPixels: IntArray?,
        val decisions: List<MaskedBackgroundRepairer.ComponentDecision>,
        val residualPixels: Int,
    ) {
        val fullyRepaired: Boolean
            get() = componentCount > 0 &&
                acceptedComponentCount == componentCount &&
                repairedPixels > 0 &&
                patchPixels != null &&
                residualPixels == 0

        val publishable: Boolean
            get() = acceptedComponentCount > 0 && repairedPixels > 0 && patchPixels != null

        val displayable: Boolean
            get() = publishable && TextRepairPatchCoveragePolicy.canDisplay(
                repairedPixels = repairedPixels,
                residualPixels = residualPixels,
                hasPatchPixels = patchPixels != null,
            )
    }

    data class Result(
        val blocks: List<BlockRepair>,
        val timing: Timing = Timing(),
    ) {
        val fullyRepairedBlockCount: Int
            get() = blocks.count { it.fullyRepaired }

        val publishableBlockCount: Int
            get() = blocks.count { it.publishable }

        val displayableBlockCount: Int
            get() = blocks.count { it.displayable }

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
        val totalStartedNs = System.nanoTime()
        var cropCopyUs = 0L
        var coverageUs = 0L
        var backgroundRepairUs = 0L
        var backgroundSampleUs = 0L
        var foregroundCompleteUs = 0L
        var residualScanUs = 0L
        var patchBuildUs = 0L
        val blocks = masks.map { mask ->
            val bounds = mask.bounds
            var stageStartedNs = System.nanoTime()
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
            cropCopyUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val coverage = TextRepairSolidCoverage.plan(
                width = bounds.width,
                height = bounds.height,
                baseMask = mask.pixels,
                coreMask = mask.corePixels,
                coordinateScale = coordinateScale,
            )
            coverageUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val repair = MaskedBackgroundRepairer.repair(
                width = bounds.width,
                height = bounds.height,
                sourceArgb = localSource,
                eraseMask = coverage.repairMask,
                allowedSampleMask = BooleanArray(localSource.size) { true },
                allowDirectionalInterpolation = true,
                allowComplexBackgroundInterpolation = true,
                foregroundReferenceMask = mask.corePixels,
            )
            backgroundRepairUs += elapsedMicros(stageStartedNs)
            val repairedPixelCount = repair.repairedPixelCount
            val publishable = repair.acceptedComponentCount > 0 && repairedPixelCount > 0

            stageStartedNs = System.nanoTime()
            val repairedBackgroundSamples = IntArray(repairedPixelCount)
            var repairedSampleCount = 0
            repair.repairedMask.indices.forEach { index ->
                if (repair.repairedMask[index]) {
                    repairedBackgroundSamples[repairedSampleCount++] = repair.pixels[index]
                }
            }
            backgroundSampleUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val completedForeground = TextForegroundMaskCompleter.complete(
                width = bounds.width,
                height = bounds.height,
                argb = localSource,
                strongMask = mask.corePixels,
                supportMask = mask.supportPixels,
                backgroundSamples = repairedBackgroundSamples,
            ).mask
            foregroundCompleteUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val residualPixels = completedForeground.indices.count { index ->
                completedForeground[index] &&
                    mask.supportPixels[index] &&
                    !repair.repairedMask[index]
            }
            residualScanUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val patch = if (publishable) {
                IntArray(localSource.size) { index ->
                    if (repair.repairedMask[index]) {
                        repair.pixels[index] or OPAQUE_ALPHA
                    } else {
                        0
                    }
                }
            } else {
                null
            }
            patchBuildUs += elapsedMicros(stageStartedNs)
            BlockRepair(
                blockIndex = mask.blockIndex,
                mask = mask,
                coverage = coverage,
                repairedPixels = repairedPixelCount,
                acceptedComponentCount = repair.acceptedComponentCount,
                componentCount = repair.decisions.size,
                patchPixels = patch,
                decisions = repair.decisions,
                residualPixels = residualPixels,
            )
        }
        val totalUs = elapsedMicros(totalStartedNs)
        val measuredStageUs = cropCopyUs + coverageUs + backgroundRepairUs + backgroundSampleUs +
            foregroundCompleteUs + residualScanUs + patchBuildUs
        return Result(
            blocks = blocks,
            timing = Timing(
                totalUs = totalUs,
                cropCopyUs = cropCopyUs,
                coverageUs = coverageUs,
                backgroundRepairUs = backgroundRepairUs,
                backgroundSampleUs = backgroundSampleUs,
                foregroundCompleteUs = foregroundCompleteUs,
                residualScanUs = residualScanUs,
                patchBuildUs = patchBuildUs,
                otherUs = (totalUs - measuredStageUs).coerceAtLeast(0L),
            ),
        )
    }

    private fun elapsedMicros(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / NANOS_PER_MICROSECOND

    private const val OPAQUE_ALPHA: Int = -0x1000000
    private const val NANOS_PER_MICROSECOND: Long = 1_000L
}
