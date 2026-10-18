package com.gameocr.app.ocr

import kotlin.math.abs

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
        val firstPassAddedPixels: Int = 0,
        val firstPassReason: TextInitialForegroundPolicy.Reason = TextInitialForegroundPolicy.Reason.INSUFFICIENT_SAMPLES,
        val regionAuditAddedPixels: Int = 0,
        val regionAuditReason: TextRegionInkAudit.Reason = TextRegionInkAudit.Reason.NOT_CHECKED,
        val regionAuditUncertainPixels: Int = 0,
        val regionAuditInkColor: Int? = null,
        val regionAuditInteriorSeeds: Int = 0,
        val regionAuditBorderPixels: Int = 0,
        val regionAuditOutlinePixels: Int = 0,
        val regionAuditInkColorCount: Int = 0,
        val strokeMaskPixels: Int = 0,
    ) {
        val fullyRepaired: Boolean
            get() = displayable && (regionAuditReason == TextRegionInkAudit.Reason.VERIFIED ||
                repairedSemanticPixels >= completion.semanticPixels)

        val publishable: Boolean
            get() = acceptedComponentCount > 0 && repairedPixels > 0 && patchPixels != null

        val displayable: Boolean
            // Decisions include failed earlier attempts that later passes may have covered.
            // Final pixel coverage, rather than attempt history, determines patch usability.
            get() = publishable &&
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

    private fun repairRecognizedBoxes(
        mask: TextPixelMaskBuilder.BlockMask,
        source: IntArray,
        includeLowContrastOutline: Boolean,
    ): BlockRepair? {
        val width = mask.bounds.width
        val height = mask.bounds.height
        val boxes = mask.recognizedSourceBoxes.map { box ->
            BubbleClusterer.IntRect(box.left-mask.bounds.left,box.top-mask.bounds.top,
                box.right-mask.bounds.left,box.bottom-mask.bounds.top)
        }
        val flat = SourceTextInkPolicy.plan(width,height,source,mask.corePixels,boxes,includeLowContrastOutline)
        // Textured/outlined free text keeps the existing stroke-repair pipeline. A clean
        // balloon must not be sent through its interpolation path because of exterior artwork.
        if(flat.unresolved.any { it } || flat.erase.none { it }) return null
        val target=flat.erase
        val required = target.count { it }
        val patch = IntArray(target.size) { index ->
            if (target[index]) flat.colors[index] or 0xff000000.toInt() else 0
        }
        return BlockRepair(
            blockIndex = mask.blockIndex,
            mask = mask,
            coverage = TextRepairSolidCoverage.Plan(target, 0, 1),
            completion = AdaptiveTextEraseCompletionPolicy.Result(
                target, AdaptiveTextEraseCompletionPolicy.Strategy.SEMANTIC_REGION,
                true, required, required, 0, 1f, 0f,
            ),
            repairedPixels = required,
            acceptedComponentCount = 1,
            componentCount = 1,
            patchPixels = patch,
            decisions = listOf(MaskedBackgroundRepairer.ComponentDecision(0,true,
                MaskedBackgroundRepairer.Reason.REPAIRED,MaskedBackgroundRepairer.Mode.DOMINANT_FILL,
                required,0,1f,0f)),
            initialResidualPixels = 0,
            residualRepairPixels = 0,
            residualRepairAttempted = false,
            residualRepairPasses = 0,
            residualPixels = 0,
            repairedSemanticPixels = required,
            requiredErasePixels = required,
            repairedRequiredErasePixels = required,
            regionForegroundAddedPixels = 0,
            regionForegroundReason = TextRegionForegroundRecovery.Reason.INSUFFICIENT_SAMPLES,
            regionAuditReason = TextRegionInkAudit.Reason.VERIFIED,
        )
    }

    fun repair(
        imageWidth: Int,
        imageHeight: Int,
        sourceArgb: IntArray,
        masks: List<TextPixelMaskBuilder.BlockMask>,
        coordinateScale: Float = 1f,
        texturedBlockIndices: Set<Int> = emptySet(),
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

            if (mask.recognizedBoxPixels.isNotEmpty()) {
                repairRecognizedBoxes(mask, localSource,
                    includeLowContrastOutline = mask.blockIndex !in texturedBlockIndices,
                )?.let { return@map it }
            }

            stageStartedNs = System.nanoTime()
            var coverage = TextRepairSolidCoverage.plan(
                width = bounds.width,
                height = bounds.height,
                baseMask = mask.pixels,
                coreMask = mask.corePixels,
                coordinateScale = coordinateScale,
            )
            coverageUs += elapsedMicros(stageStartedNs)

            if (mask.barrierPixels.isNotEmpty()) {
                coverage = coverage.copy(repairMask = BooleanArray(localSource.size) {
                    coverage.repairMask[it] && !mask.barrierPixels[it]
                })
            }

            stageStartedNs = System.nanoTime()
            val flatAudit = TextRegionInkAudit.inspect(bounds.width, bounds.height, localSource,
                mask.corePixels, mask.semanticPixels, coverage.repairMask, mask.barrierPixels)
            val strokePlan = if (flatAudit.reason == TextRegionInkAudit.Reason.NO_DOMINANT_BACKGROUND ||
                (mask.blockIndex in texturedBlockIndices && flatAudit.reason == TextRegionInkAudit.Reason.PARTIAL)) {
                TextStrokeMaskPolicy.plan(bounds.width, bounds.height, localSource,
                    mask.corePixels, mask.semanticPixels,
                    allowChromaticInk = mask.recognizedBoxPixels.isNotEmpty())
            } else null
            val regionAudit = if (strokePlan == null) flatAudit else flatAudit.copy(
                foregroundPixels = null, protectedPixels = strokePlan.protected, backgroundColor = null,
            )
            foregroundCompleteUs += elapsedMicros(stageStartedNs)
            var repairCore = mask.corePixels
            if (strokePlan != null) {
                repairCore = strokePlan.ink
                coverage = coverage.copy(repairMask = strokePlan.erase)
            }
            // Once interior ink is confirmed, discard detector seeds on protected artwork too.
            // Keeping those original targets would repaint the hand/outline through interpolation.
            val auditedInk = regionAudit.foregroundPixels
            if (auditedInk != null && auditedInk.any { it }) {
                stageStartedNs = System.nanoTime()
                val retainedCore = BooleanArray(localSource.size) { index -> mask.corePixels[index] && auditedInk[index] }
                val retainedArea = BinarySquareDilation.dilate(retainedCore, bounds.width, bounds.height,
                    coverage.existingExpansionPx + coverage.solidExpansionPx)
                coverage = coverage.copy(repairMask = BooleanArray(localSource.size) { index ->
                    coverage.repairMask[index] && retainedArea[index] && !regionAudit.protectedPixels[index]
                })
                repairCore = if (retainedCore.any { it }) retainedCore else auditedInk
                coverageUs += elapsedMicros(stageStartedNs)
            }

            stageStartedNs = System.nanoTime()
            val initialForeground = if (strokePlan != null) TextInitialForegroundPolicy.Result(
                null, null, TextInitialForegroundPolicy.Reason.BACKGROUND_NOT_FLAT,
            ) else TextInitialForegroundPolicy.plan(bounds.width, bounds.height,
                localSource, repairCore, mask.supportPixels, mask.semanticPixels, coverage.repairMask)
            val firstPassAddedPixels = initialForeground.foreground?.let { foreground ->
                foreground.mask.indices.count { index -> foreground.mask[index] &&
                    !coverage.repairMask[index] && !regionAudit.protectedPixels[index] }
            } ?: 0
            foregroundCompleteUs += elapsedMicros(stageStartedNs)
            if (firstPassAddedPixels > 0) {
                stageStartedNs = System.nanoTime()
                val foreground = requireNotNull(initialForeground.foreground).mask
                val planned = TextRepairSolidCoverage.plan(bounds.width, bounds.height, foreground, foreground, coordinateScale)
                coverage = coverage.copy(repairMask = BooleanArray(localSource.size) { index ->
                    coverage.repairMask[index] ||
                        (planned.repairMask[index] && !regionAudit.protectedPixels[index] &&
                            (mask.supportPixels[index] || mask.semanticPixels[index]))
                })
                coverageUs += elapsedMicros(stageStartedNs)
            }

            if (auditedInk != null) {
                stageStartedNs = System.nanoTime()
                val added = BooleanArray(localSource.size) { index ->
                    auditedInk[index] && !coverage.repairMask[index]
                }
                if (added.any { it }) {
                    val planned = TextRepairSolidCoverage.plan(bounds.width, bounds.height, added, added, coordinateScale)
                    coverage = coverage.copy(repairMask = BooleanArray(localSource.size) { index ->
                        coverage.repairMask[index] ||
                            (planned.repairMask[index] && !regionAudit.protectedPixels[index] &&
                                (mask.supportPixels[index] || mask.semanticPixels[index]))
                    })
                }
                coverageUs += elapsedMicros(stageStartedNs)
            }
            val auditedSupport = BooleanArray(localSource.size) { index ->
                mask.supportPixels[index] &&
                    (!regionAudit.protectedPixels[index] || coverage.repairMask[index])
            }
            val auditedSemantic = BooleanArray(localSource.size) { index ->
                mask.semanticPixels[index] &&
                    (!regionAudit.protectedPixels[index] || coverage.repairMask[index])
            }
            // A protected panel/illustration is not a background sample for nearby text.
            // Sampling it would paint gray streaks through a clean white title strip.
            val detectorInterior = if (strokePlan != null)
                BinarySquareDilation.dilate(mask.corePixels, bounds.width, bounds.height, 3) else null
            val outlineSampleGuard = strokePlan?.let {
                BinarySquareDilation.dilate(it.erase, bounds.width, bounds.height, 3)
            }
            val backgroundSampleMask = BooleanArray(localSource.size) {
                !regionAudit.protectedPixels[it] && detectorInterior?.get(it) != true &&
                    outlineSampleGuard?.get(it) != true &&
                    (mask.barrierPixels.isEmpty() || !mask.barrierPixels[it])
            }

            stageStartedNs = System.nanoTime()
            val completion = AdaptiveTextEraseCompletionPolicy.refine(
                width = bounds.width,
                height = bounds.height,
                sourceArgb = localSource,
                seedMask = coverage.repairMask,
                supportMask = mask.supportPixels,
                semanticMask = mask.semanticPixels,
            )
            val flatCompletionMask = BooleanArray(localSource.size) { index -> strokePlan == null &&
                completion.mask[index] && !regionAudit.protectedPixels[index] &&
                    (coverage.repairMask[index] || regionAudit.backgroundColor?.let { background ->
                        colorDistance(localSource[index], background) <= MAX_COMPLETION_BACKGROUND_DISTANCE
                    } ?: false)
            }
            completionRefineUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val firstRepair = MaskedBackgroundRepairer.repair(
                width = bounds.width,
                height = bounds.height,
                sourceArgb = localSource,
                eraseMask = coverage.repairMask,
                allowedSampleMask = backgroundSampleMask,
                // Additional flat completion may smooth verified background pixels, but must
                // never repaint a differently colored area outside the balloon. Actual glyphs,
                // outlines and detached strokes are explicit coverage targets from the audit.
                flatCompletionMask = flatCompletionMask,
                allowDirectionalInterpolation = true,
                allowComplexBackgroundInterpolation = true,
                foregroundReferenceMask = if (strokePlan == null) repairCore else null,
                preferSpatialInterpolation = strokePlan != null,
            )
            backgroundRepairUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val reuseInitialForeground = initialForeground.foreground != null &&
                regionAudit.protectedPixels.none { it } && firstRepair.decisions.isNotEmpty() &&
                firstRepair.decisions.all { decision -> decision.accepted &&
                    decision.mode == MaskedBackgroundRepairer.Mode.DOMINANT_FILL &&
                    decision.referenceColor == initialForeground.backgroundColor }
            val firstBackgroundSamples = if (reuseInitialForeground) IntArray(0) else repairedBackgroundSamples(firstRepair)
            backgroundSampleUs += elapsedMicros(stageStartedNs)

            stageStartedNs = System.nanoTime()
            val supportedForeground = if (strokePlan != null) strokePlan.erase else if (reuseInitialForeground) requireNotNull(initialForeground.foreground).mask else TextForegroundMaskCompleter.complete(
                width = bounds.width,
                height = bounds.height,
                argb = localSource,
                strongMask = repairCore,
                supportMask = auditedSupport,
                backgroundSamples = firstBackgroundSamples,
            ).mask
            val regionForeground = if (strokePlan != null) TextRegionForegroundRecovery.Result(
                strokePlan.erase, 0, TextRegionForegroundRecovery.Reason.BACKGROUND_NOT_FLAT,
            ) else if (reuseInitialForeground) requireNotNull(initialForeground.foreground) else TextRegionForegroundRecovery.recover(
                width = bounds.width,
                height = bounds.height,
                argb = localSource,
                knownForeground = supportedForeground,
                textRegion = auditedSemantic,
                backgroundSamples = firstBackgroundSamples,
                flatRepair = firstRepair.decisions.isNotEmpty() && firstRepair.decisions.all {
                    it.mode == MaskedBackgroundRepairer.Mode.DOMINANT_FILL
                },
            )
            val completedForeground = regionForeground.mask
            val foregroundSupport = BooleanArray(localSource.size) { index ->
                auditedSupport[index] || auditedSemantic[index]
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
                        (strokePlan == null || !strokePlan.protected[index]) &&
                        (mask.barrierPixels.isEmpty() || !mask.barrierPixels[index]) &&
                        (foregroundSupport[index] || finalRepair.repairedMask[index])
                }
                val residualRepair = MaskedBackgroundRepairer.repair(
                    width = bounds.width,
                    height = bounds.height,
                    sourceArgb = finalRepair.pixels,
                    eraseMask = residualEraseMask,
                    allowedSampleMask = backgroundSampleMask,
                    allowDirectionalInterpolation = true,
                    allowComplexBackgroundInterpolation = true,
                    foregroundReferenceMask = if (strokePlan == null) currentResidualMask else null,
                    preferSpatialInterpolation = strokePlan != null,
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
                val finalCompletedForeground = if (strokePlan != null) strokePlan.erase else TextForegroundMaskCompleter.complete(
                    width = bounds.width,
                    height = bounds.height,
                    argb = localSource,
                    strongMask = repairCore,
                    supportMask = auditedSupport,
                    backgroundSamples = finalBackgroundSamples,
                ).mask
                finalCompletedForeground.indices.forEach { index ->
                    if (finalCompletedForeground[index] && auditedSupport[index]) {
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
                firstPassAddedPixels = firstPassAddedPixels,
                firstPassReason = initialForeground.reason,
                regionAuditAddedPixels = regionAudit.addedPixels,
                regionAuditReason = regionAudit.reason,
                regionAuditUncertainPixels = regionAudit.uncertainPixels,
                regionAuditInkColor = regionAudit.inkColor,
                regionAuditInteriorSeeds = regionAudit.interiorSeedPixels,
                regionAuditBorderPixels = regionAudit.borderPixels,
                regionAuditOutlinePixels = regionAudit.outlinePixels,
                regionAuditInkColorCount = regionAudit.inkColorCount,
                strokeMaskPixels = strokePlan?.erase?.count { it } ?: 0,
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

    private fun colorDistance(a: Int, b: Int): Int = maxOf(
        abs((a ushr 16 and 255) - (b ushr 16 and 255)),
        abs((a ushr 8 and 255) - (b ushr 8 and 255)),
        abs((a and 255) - (b and 255)),
    )

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
    private const val MAX_COMPLETION_BACKGROUND_DISTANCE: Int = 12
}
