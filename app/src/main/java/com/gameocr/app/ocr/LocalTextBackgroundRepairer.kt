package com.gameocr.app.ocr

/** Runs coverage-first repair independently for each model-free local glyph mask. */
internal object LocalTextBackgroundRepairer {

    data class Timing(
        val totalUs: Long = 0L,
        val cropCopyUs: Long = 0L,
        val coverageUs: Long = 0L,
        val completionRefineUs: Long = 0L,
        val backgroundRepairUs: Long = 0L,
        val backgroundSampleUs: Long = 0L,
        val foregroundCompleteUs: Long = 0L,
        val residualRepairUs: Long = 0L,
        val residualScanUs: Long = 0L,
        val patchBuildUs: Long = 0L,
        val otherUs: Long = 0L,
    ) {
        val measuredStageUs: Long
            get() = cropCopyUs + coverageUs + completionRefineUs +
                backgroundRepairUs + backgroundSampleUs +
                foregroundCompleteUs + residualRepairUs + residualScanUs + patchBuildUs

        fun toLogString(): String =
            "total=$totalUs,cropCopy=$cropCopyUs,coverage=$coverageUs," +
            "completion=$completionRefineUs," +
            "background=$backgroundRepairUs,samples=$backgroundSampleUs," +
                "foreground=$foregroundCompleteUs,residualRepair=$residualRepairUs," +
                "residual=$residualScanUs," +
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
        val completion: AdaptiveTextEraseCompletionPolicy.Result,
        val repairedPixels: Int,
        val acceptedComponentCount: Int,
        val componentCount: Int,
        val patchPixels: IntArray?,
        val decisions: List<MaskedBackgroundRepairer.ComponentDecision>,
        val initialResidualPixels: Int,
        val residualRepairPixels: Int,
        val residualRepairAttempted: Boolean,
        val residualRepairPasses: Int,
        val residualPixels: Int,
        val repairedSemanticPixels: Int,
        val requiredErasePixels: Int,
        val repairedRequiredErasePixels: Int,
        val regionForegroundAddedPixels: Int,
        val regionForegroundReason: TextRegionForegroundRecovery.Reason,
    ) {
        val fullyRepaired: Boolean
            get() = displayable

        val publishable: Boolean
            get() = acceptedComponentCount > 0 && repairedPixels > 0 && patchPixels != null

        val displayable: Boolean
            get() = publishable && acceptedComponentCount == componentCount &&
                TextRepairPatchCoveragePolicy.canDisplay(
                repairedPixels = repairedPixels,
                residualPixels = residualPixels,
                hasPatchPixels = patchPixels != null,
                requiredCoveragePixels = requiredErasePixels,
                repairedRequiredCoveragePixels = repairedRequiredErasePixels,
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

        val residualRepairBlockCount: Int
            get() = blocks.count { it.residualRepairAttempted }

        val residualRepairPixelCount: Int
            get() = blocks.sumOf { it.residualRepairPixels }

        val residualPixelCount: Int
            get() = blocks.sumOf { it.residualPixels }

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
        var completionRefineUs = 0L
        var backgroundRepairUs = 0L
        var backgroundSampleUs = 0L
        var foregroundCompleteUs = 0L
        var residualRepairUs = 0L
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
            val completion = AdaptiveTextEraseCompletionPolicy.refine(
                width = bounds.width,
                height = bounds.height,
                sourceArgb = localSource,
                seedMask = coverage.repairMask,
                supportMask = mask.supportPixels,
                semanticMask = mask.semanticPixels,
            )
            val flatCompletionMask = completion.mask
            completionRefineUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val firstRepair = MaskedBackgroundRepairer.repair(
                width = bounds.width,
                height = bounds.height,
                sourceArgb = localSource,
                eraseMask = coverage.repairMask,
                allowedSampleMask = BooleanArray(localSource.size) { true },
                // When every connected glyph component has a genuinely flat background,
                // cover the complete OCR support area. This removes antialiasing and detached
                // stroke corners that no foreground threshold can reliably distinguish from the
                // background. Directional/spatial repairs ignore this completion area.
                flatCompletionMask = flatCompletionMask,
                allowDirectionalInterpolation = true,
                allowComplexBackgroundInterpolation = true,
                foregroundReferenceMask = mask.corePixels,
            )
            backgroundRepairUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val firstBackgroundSamples = repairedBackgroundSamples(firstRepair)
            backgroundSampleUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val supportedForeground = TextForegroundMaskCompleter.complete(
                width = bounds.width,
                height = bounds.height,
                argb = localSource,
                strongMask = mask.corePixels,
                supportMask = mask.supportPixels,
                backgroundSamples = firstBackgroundSamples,
            ).mask
            val regionForeground = TextRegionForegroundRecovery.recover(
                width = bounds.width,
                height = bounds.height,
                argb = localSource,
                knownForeground = supportedForeground,
                textRegion = mask.semanticPixels,
                backgroundSamples = firstBackgroundSamples,
                flatRepair = firstRepair.decisions.isNotEmpty() && firstRepair.decisions.all {
                    it.mode == MaskedBackgroundRepairer.Mode.DOMINANT_FILL
                },
            )
            val completedForeground = regionForeground.mask
            val foregroundSupport = BooleanArray(localSource.size) { index ->
                mask.supportPixels[index] || mask.semanticPixels[index]
            }
            foregroundCompleteUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val initialResidualMask = BooleanArray(completedForeground.size) { index ->
                completedForeground[index] &&
                    foregroundSupport[index] &&
                    !firstRepair.repairedMask[index]
            }
            val initialResidualPixels = initialResidualMask.count { it }
            // Whole text rectangles contain background gaps and artwork. Only confirmed erase
            // targets must be painted; optional flat-background expansion is not a coverage test.
            val requiredEraseMask = BooleanArray(completedForeground.size) { index ->
                coverage.repairMask[index] ||
                    (completedForeground[index] && foregroundSupport[index])
            }
            residualScanUs += elapsedMicros(stageStartedNs)

            var finalRepair = firstRepair
            var currentResidualMask = initialResidualMask
            var residualPixels = initialResidualPixels
            var residualRepairPasses = 0
            var residualRepairPixels = 0
            while (
                residualPixels > 0 &&
                finalRepair.repairedPixelCount > 0 &&
                residualRepairPasses < MAX_RESIDUAL_REPAIR_PASSES
            ) {
                residualRepairPasses++
                stageStartedNs = System.nanoTime()
                val residualCoverage = TextRepairSolidCoverage.plan(
                    width = bounds.width,
                    height = bounds.height,
                    baseMask = currentResidualMask,
                    coreMask = currentResidualMask,
                    coordinateScale = coordinateScale,
                )
                val residualEraseMask = BooleanArray(currentResidualMask.size) { index ->
                    residualCoverage.repairMask[index] &&
                        (foregroundSupport[index] || finalRepair.repairedMask[index])
                }
                val residualRepair = MaskedBackgroundRepairer.repair(
                    width = bounds.width,
                    height = bounds.height,
                    sourceArgb = finalRepair.pixels,
                    eraseMask = residualEraseMask,
                    allowedSampleMask = BooleanArray(localSource.size) { true },
                    allowDirectionalInterpolation = true,
                    allowComplexBackgroundInterpolation = true,
                    foregroundReferenceMask = currentResidualMask,
                )
                val previousRepairedPixels = finalRepair.repairedPixelCount
                if (residualRepair.repairedPixelCount > 0) {
                    val combinedRepairMask = BooleanArray(localSource.size) { index ->
                        finalRepair.repairedMask[index] || residualRepair.repairedMask[index]
                    }
                    val combinedRepairedPixels = combinedRepairMask.count { it }
                    residualRepairPixels += combinedRepairedPixels - previousRepairedPixels
                    finalRepair = MaskedBackgroundRepairer.Result(
                        pixels = residualRepair.pixels,
                        repairedMask = combinedRepairMask,
                        decisions = finalRepair.decisions + residualRepair.decisions,
                    )
                }
                residualRepairUs += elapsedMicros(stageStartedNs)
                if (finalRepair.repairedPixelCount <= previousRepairedPixels) break

                stageStartedNs = System.nanoTime()
                val finalBackgroundSamples = repairedBackgroundSamples(finalRepair)
                backgroundSampleUs += elapsedMicros(stageStartedNs)

                stageStartedNs = System.nanoTime()
                val finalCompletedForeground = TextForegroundMaskCompleter.complete(
                    width = bounds.width,
                    height = bounds.height,
                    argb = localSource,
                    strongMask = mask.corePixels,
                    supportMask = mask.supportPixels,
                    backgroundSamples = finalBackgroundSamples,
                ).mask
                finalCompletedForeground.indices.forEach { index ->
                    if (finalCompletedForeground[index] && mask.supportPixels[index]) {
                        requiredEraseMask[index] = true
                    }
                }
                foregroundCompleteUs += elapsedMicros(stageStartedNs)

                stageStartedNs = System.nanoTime()
                currentResidualMask = BooleanArray(finalCompletedForeground.size) { index ->
                    requiredEraseMask[index] &&
                        !finalRepair.repairedMask[index]
                }
                residualPixels = currentResidualMask.count { it }
                residualScanUs += elapsedMicros(stageStartedNs)
            }

            val repairedPixelCount = finalRepair.repairedPixelCount
            val repairedSemanticPixels = finalRepair.repairedMask.indices.count { index ->
                finalRepair.repairedMask[index] && mask.semanticPixels[index]
            }
            val publishable = finalRepair.acceptedComponentCount > 0 && repairedPixelCount > 0

            stageStartedNs = System.nanoTime()
            val patch = if (publishable) {
                IntArray(localSource.size) { index ->
                    if (finalRepair.repairedMask[index]) {
                        finalRepair.pixels[index] or OPAQUE_ALPHA
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
                completion = completion,
                repairedPixels = repairedPixelCount,
                acceptedComponentCount = finalRepair.acceptedComponentCount,
                componentCount = finalRepair.decisions.size,
                patchPixels = patch,
                decisions = finalRepair.decisions,
                initialResidualPixels = initialResidualPixels,
                residualRepairPixels = residualRepairPixels,
                residualRepairAttempted = residualRepairPasses > 0,
                residualRepairPasses = residualRepairPasses,
                residualPixels = residualPixels,
                repairedSemanticPixels = repairedSemanticPixels,
                requiredErasePixels = requiredEraseMask.count { it },
                repairedRequiredErasePixels = requiredEraseMask.indices.count { index ->
                    requiredEraseMask[index] && finalRepair.repairedMask[index]
                },
                regionForegroundAddedPixels = regionForeground.addedPixels,
                regionForegroundReason = regionForeground.reason,
            )
        }
        val totalUs = elapsedMicros(totalStartedNs)
        val measuredStageUs = cropCopyUs + coverageUs + completionRefineUs +
            backgroundRepairUs + backgroundSampleUs +
            foregroundCompleteUs + residualRepairUs + residualScanUs + patchBuildUs
        return Result(
            blocks = blocks,
            timing = Timing(
                totalUs = totalUs,
                cropCopyUs = cropCopyUs,
                coverageUs = coverageUs,
                completionRefineUs = completionRefineUs,
                backgroundRepairUs = backgroundRepairUs,
                backgroundSampleUs = backgroundSampleUs,
                foregroundCompleteUs = foregroundCompleteUs,
                residualRepairUs = residualRepairUs,
                residualScanUs = residualScanUs,
                patchBuildUs = patchBuildUs,
                otherUs = (totalUs - measuredStageUs).coerceAtLeast(0L),
            ),
        )
    }

    private fun elapsedMicros(startedNs: Long): Long =
        (System.nanoTime() - startedNs) / NANOS_PER_MICROSECOND

    private fun repairedBackgroundSamples(
        repair: MaskedBackgroundRepairer.Result,
    ): IntArray {
        val samples = IntArray(repair.repairedPixelCount)
        var count = 0
        repair.repairedMask.indices.forEach { index ->
            if (repair.repairedMask[index]) samples[count++] = repair.pixels[index]
        }
        return samples
    }

    private const val OPAQUE_ALPHA: Int = -0x1000000
    private const val NANOS_PER_MICROSECOND: Long = 1_000L
    private const val MAX_RESIDUAL_REPAIR_PASSES: Int = 2
}
