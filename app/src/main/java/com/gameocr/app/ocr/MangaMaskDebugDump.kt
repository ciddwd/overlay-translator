package com.gameocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.gameocr.app.ocr.BubbleClusterer.Bubble
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object MangaMaskDebugDumpPolicy {
    const val MAX_SETS_PER_PROCESS: Int = 4
    const val MAX_RETAINED_SETS: Int = 4

    data class StoredFile(
        val name: String,
        val length: Long,
    )

    data class BatchId(
        val timestampMs: Long,
        val setIndex: Int,
    )

    fun isEnabled(
        developerOptionsEnabled: Boolean,
        screenshotSavingEnabled: Boolean,
    ): Boolean = developerOptionsEnabled && screenshotSavingEnabled

    fun shouldDump(
        developerOptionsEnabled: Boolean,
        screenshotSavingEnabled: Boolean,
        setIndex: Int,
    ): Boolean = isEnabled(developerOptionsEnabled, screenshotSavingEnabled) &&
        setIndex in 1..MAX_SETS_PER_PROCESS

    fun fileName(
        batchTimestampMs: Long,
        setIndex: Int,
        label: String,
        width: Int,
        height: Int,
    ): String {
        val safeLabel = label
            .lowercase(Locale.US)
            .map { character -> if (character.isLetterOrDigit()) character else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "mask" }
        return "manga-mask-$batchTimestampMs-s$setIndex-$safeLabel-${width}x$height.png"
    }

    fun batchId(fileName: String): BatchId? {
        val match = FILE_PATTERN.matchEntire(fileName) ?: return null
        return BatchId(
            timestampMs = match.groupValues[1].toLongOrNull() ?: return null,
            setIndex = match.groupValues[2].toIntOrNull() ?: return null,
        )
    }

    fun filesToDeleteBeforeWriting(
        files: List<StoredFile>,
        incomingBatch: BatchId,
        maximumRetainedSets: Int = MAX_RETAINED_SETS,
    ): List<StoredFile> {
        require(maximumRetainedSets >= 1)
        val batches = files.mapNotNull { file -> batchId(file.name)?.let { it to file } }
            .groupBy({ it.first }, { it.second })
        val previousToKeep = batches.keys
            .asSequence()
            .filter { it != incomingBatch }
            .sortedWith(compareByDescending<BatchId> { it.timestampMs }.thenByDescending { it.setIndex })
            .take(maximumRetainedSets - 1)
            .toSet()
        val keep = previousToKeep + incomingBatch
        return batches.asSequence()
            .filter { (batch, _) -> batch !in keep }
            .flatMap { (_, batchFiles) -> batchFiles.asSequence() }
            .sortedBy(StoredFile::name)
            .toList()
    }

    fun shouldRunLegacyBubbleHeuristic(
        saveCurrentDebugSet: Boolean,
    ): Boolean = saveCurrentDebugSet

    private val FILE_PATTERN =
        Regex("""manga-mask-(\d+)-s(\d+)-.+-\d+x\d+\.png""")
}

internal object MangaShapeAwareFramePolicy {
    data class Decision(
        val analyzeFrame: Boolean,
        val createDelayedSession: Boolean,
        val saveDebugArtifacts: Boolean,
        val useDetectorGuidedPatches: Boolean,
        val runBoxDetector: Boolean,
        val runLegacySegmentation: Boolean,
    )

    fun decide(
        shapeAwareRenderingEnabled: Boolean,
        developerOptionsEnabled: Boolean,
        screenshotSavingEnabled: Boolean,
        bubbleDetectorAvailable: Boolean,
        localSegmentationModelAvailable: Boolean,
    ): Decision {
        val saveDebugArtifacts = false
        val useDetectorGuidedPatches =
            shapeAwareRenderingEnabled && bubbleDetectorAvailable
        val useLegacyPrototype =
            !useDetectorGuidedPatches &&
                developerOptionsEnabled &&
                localSegmentationModelAvailable
        val createDelayedSession = useDetectorGuidedPatches || useLegacyPrototype
        return Decision(
            analyzeFrame = createDelayedSession,
            createDelayedSession = createDelayedSession,
            saveDebugArtifacts = saveDebugArtifacts,
            useDetectorGuidedPatches = useDetectorGuidedPatches,
            runBoxDetector = useDetectorGuidedPatches,
            runLegacySegmentation = useLegacyPrototype,
        )
    }
}

internal data class MangaMaskDebugArtifact(
    val label: String,
    val file: File,
)

internal data class MangaMaskDebugReport(
    val analysis: MangaMaskDebugAnalyzer.Analysis,
    val boxDetection: MangaBubbleDetectionDebugEngine.Output? = null,
    val detectorGuidedMasks: DetectorGuidedBubbleMaskExtractor.Result? = null,
    val detectorGuidedMemberAssociations: List<BubbleMaskAssociator.Association> =
        emptyList(),
    val detectorGuidedExcludedMemberIndices: Set<Int> = emptySet(),
    val detectorGuidedRegroupedGroups: List<BubbleModelRegrouper.Group> = emptyList(),
    val modelSegmentation: MangaBubbleSegmentationDebugEngine.Output? = null,
    val modelAssociations: List<BubbleMaskAssociator.Association> = emptyList(),
    val modelMemberAssociations: List<BubbleMaskAssociator.Association> = emptyList(),
    val modelRegroupedGroups: List<BubbleModelRegrouper.Group> = emptyList(),
    val delayedMaskInput: MangaDelayedMaskDebugSessionManager.Input? = null,
)

private val dumpedMangaMaskSetCount = AtomicInteger(0)

internal suspend fun dumpMangaMaskDebugSet(
    context: Context,
    bitmap: Bitmap,
    quads: List<DBPostprocessor.Quad>,
    bubbles: List<Bubble>,
    probabilityTextMask: BooleanArray,
    bubbleClusterGap: Int,
    cropPaddingPx: Int,
    analyzeFrame: Boolean,
    createDelayedSession: Boolean,
    useDetectorGuidedPatches: Boolean,
    runBoxDetector: Boolean,
    runLegacySegmentation: Boolean,
): MangaMaskDebugReport? {
    if (!analyzeFrame) return null

    val width = bitmap.width
    val height = bitmap.height
    val argb = IntArray(width * height)
    bitmap.getPixels(argb, 0, width, 0, 0, width, height)
    val polygons = quads.map { quad ->
        MangaMaskDebugAnalyzer.Polygon(
            listOf(
                MangaMaskDebugAnalyzer.Point(quad.p0.x, quad.p0.y),
                MangaMaskDebugAnalyzer.Point(quad.p1.x, quad.p1.y),
                MangaMaskDebugAnalyzer.Point(quad.p2.x, quad.p2.y),
                MangaMaskDebugAnalyzer.Point(quad.p3.x, quad.p3.y),
            )
        )
    }
    val analysis = MangaMaskDebugAnalyzer.analyze(
        width = width,
        height = height,
        argb = argb,
        probabilityTextMask = probabilityTextMask,
        polygons = polygons,
        bubbles = if (
            MangaMaskDebugDumpPolicy.shouldRunLegacyBubbleHeuristic(
                saveCurrentDebugSet = false,
            )
        ) {
            bubbles.map { bubble ->
                MangaMaskDebugAnalyzer.BubbleInput(
                    contentBounds = bubble.contentRect,
                    memberIndices = bubble.memberIndices,
                )
            }
        } else {
            emptyList()
        },
    )
    val shouldRunBoxDetector = runBoxDetector && useDetectorGuidedPatches
    val boxDetection = if (shouldRunBoxDetector) {
        runCatching {
            MangaBubbleDetectionDebugEngine.runIfInstalled(context, bitmap)
        }.onFailure { error ->
            timber.log.Timber.w(error, "Manga bubble detection inference failed")
        }.getOrNull()
    } else {
        null
    }
    boxDetection?.let { detection ->
        timber.log.Timber.i(
            "Manga bubble detection debug bubbles=%d text=%d session=%dms preprocess=%dms inference=%dms postprocess=%dms total=%dms",
            detection.detections.size,
            detection.textDetections.size,
            detection.sessionPrepareMs,
            detection.preprocessMs,
            detection.inferenceMs,
            detection.postprocessMs,
            detection.totalMs,
        )
        detection.detections.forEachIndexed { index, bubble ->
            timber.log.Timber.i(
                "Manga bubble detection box[%d] confidence=%.3f box=%.1f,%.1f,%.1f,%.1f",
                index,
                bubble.confidence,
                bubble.left,
                bubble.top,
                bubble.right,
                bubble.bottom,
            )
        }
        detection.textDetections.forEachIndexed { index, text ->
            timber.log.Timber.i(
                "Manga model text[%d] kind=%s confidence=%.3f box=%.1f,%.1f,%.1f,%.1f",
                index,
                text.kind,
                text.confidence,
                text.left,
                text.top,
                text.right,
                text.bottom,
            )
        }
    }
    val detectorGuidedMasks = boxDetection?.let { detection ->
        DetectorGuidedBubbleMaskExtractor.extract(
            width = width,
            height = height,
            argb = argb,
            polygons = polygons,
            boxDetections = detection.detections,
        )
    }
    detectorGuidedMasks?.let { result ->
        timber.log.Timber.i(
            "Manga detector-guided masks accepted=%d rejected=%d total=%d duration=%dms",
            result.acceptedCount,
            result.decisions.size - result.acceptedCount,
            result.decisions.size,
            result.durationMs,
        )
        result.decisions.forEach { decision ->
            timber.log.Timber.i(
                "Manga detector-guided mask[%d] accepted=%s reason=%s confidence=%.3f members=%s roi=%s pixels=%d",
                decision.detectionIndex,
                decision.accepted,
                decision.diagnostic.reason,
                decision.diagnostic.confidence,
                decision.memberIndices.joinToString(","),
                decision.diagnostic.roi,
                decision.diagnostic.regionPixels,
            )
        }
    }
    val guidedMemberAssociations = detectorGuidedMasks?.let { guided ->
        BubbleMaskAssociator.associate(
            width = width,
            height = height,
            modelBubbles = guided.detections,
            instanceMasks = guided.instanceMasks,
            ocrGroups = polygons.map { polygon ->
                BubbleMaskAssociator.OcrGroup(
                    contentBounds = polygon.bounds,
                    memberBounds = listOf(polygon.bounds),
                )
            },
        )
    }.orEmpty()
    val refinedGuidedMemberAssignmentResult = detectorGuidedMasks?.let { guided ->
        MangaBubbleTextAssignmentRefiner.refine(
            memberBounds = polygons.map { polygon -> polygon.bounds },
            bubbleDetections = boxDetection?.detections.orEmpty(),
            textDetections = boxDetection?.textDetections.orEmpty(),
            modelByMember = guided.memberDetectionIndices,
        )
    }
    val refinedGuidedMemberAssignments =
        refinedGuidedMemberAssignmentResult?.assignments.orEmpty()
    val excludedGuidedMemberIndices =
        refinedGuidedMemberAssignmentResult?.excludedMemberIndices.orEmpty()
    refinedGuidedMemberAssignmentResult?.let { result ->
        timber.log.Timber.i(
            "Manga text guard freeExcluded=%s freeAmbiguous=%s kindConflicts=%s",
            result.freeTextExcludedMemberIndices,
            result.ambiguousFreeTextMemberIndices,
            result.conflictingTextKindMemberIndices,
        )
    }
    val guidedRegroupedGroups = detectorGuidedMasks?.let { guided ->
        BubbleModelRegrouper.regroupByModelAssignments(
            width = width,
            height = height,
            memberBounds = polygons.map { it.bounds },
            modelBounds = guided.detections.map { detection ->
                BubbleClusterer.IntRect(
                    left = floor(detection.left).toInt(),
                    top = floor(detection.top).toInt(),
                    right = ceil(detection.right).toInt(),
                    bottom = ceil(detection.bottom).toInt(),
                )
            },
            modelByMember = refinedGuidedMemberAssignments,
            fallbackPadding = cropPaddingPx,
            fallbackGap = bubbleClusterGap,
            excludedMemberIndices = excludedGuidedMemberIndices,
        )
    }.orEmpty()
    detectorGuidedMasks?.let { guided ->
        timber.log.Timber.i(
            "Manga guided regroup detectorMatched=%d/%d textRefined=%d excludedByText=%d maskMatched=%d/%d modelGroups=%d fallbackGroups=%d",
            guided.memberDetectionIndices.count { it != null },
            guided.memberDetectionIndices.size,
            refinedGuidedMemberAssignments.count { it != null },
            guided.memberDetectionIndices.indices.count { index ->
                guided.memberDetectionIndices[index] != null &&
                    refinedGuidedMemberAssignments.getOrNull(index) == null
            },
            guidedMemberAssociations.count { it.matched },
            guidedMemberAssociations.size,
            guidedRegroupedGroups.count { it.source == BubbleModelRegrouper.Source.MODEL },
            guidedRegroupedGroups.count {
                it.source == BubbleModelRegrouper.Source.LEGACY_FALLBACK
            },
        )
        if (useDetectorGuidedPatches) {
            timber.log.Timber.i(
                "Manga shape-aware provider=RT_DETR_GUIDED acceptedMasks=%d/%d",
                guided.acceptedCount,
                guided.decisions.size,
            )
        }
    }
    val shouldRunLegacySegmentation =
        runLegacySegmentation && createDelayedSession && !useDetectorGuidedPatches
    val modelSegmentation = if (shouldRunLegacySegmentation) {
        runCatching {
            MangaBubbleSegmentationDebugEngine.runIfInstalled(context, bitmap)
        }.onFailure { error ->
            timber.log.Timber.w(error, "Manga bubble segmentation debug inference failed")
        }.getOrNull()
    } else {
        null
    }
    modelSegmentation?.let { segmentation ->
        timber.log.Timber.i(
            "Manga bubble segmentation debug detections=%d session=%dms preprocess=%dms inputTensor=%dms inference=%dms outputRead=%dms postprocess=%dms total=%dms",
            segmentation.detections.size,
            segmentation.sessionPrepareMs,
            segmentation.preprocessMs,
            segmentation.inputTensorMs,
            segmentation.inferenceMs,
            segmentation.outputReadMs,
            segmentation.postprocessMs,
            segmentation.totalMs,
        )
        segmentation.detections.forEachIndexed { index, detection ->
            timber.log.Timber.i(
                "Manga bubble segmentation detection[%d] confidence=%.3f box=%.1f,%.1f,%.1f,%.1f",
                index,
                detection.confidence,
                detection.left,
                detection.top,
                detection.right,
                detection.bottom,
            )
        }
    }
    val modelAssociations = modelSegmentation?.let { segmentation ->
        BubbleMaskAssociator.associate(
            width = width,
            height = height,
            modelBubbles = segmentation.detections,
            instanceMasks = segmentation.instanceMasks,
            ocrGroups = bubbles.map { bubble ->
                BubbleMaskAssociator.OcrGroup(
                    contentBounds = bubble.contentRect,
                    memberBounds = bubble.memberIndices.mapNotNull { memberIndex ->
                        polygons.getOrNull(memberIndex)?.bounds
                    },
                )
            },
        )
    }.orEmpty()
    val modelMemberAssociations = modelSegmentation?.let { segmentation ->
        BubbleMaskAssociator.associate(
            width = width,
            height = height,
            modelBubbles = segmentation.detections,
            instanceMasks = segmentation.instanceMasks,
            ocrGroups = polygons.map { polygon ->
                BubbleMaskAssociator.OcrGroup(
                    contentBounds = polygon.bounds,
                    memberBounds = listOf(polygon.bounds),
                )
            },
        )
    }.orEmpty()
    val modelRegroupedGroups = modelSegmentation?.let { segmentation ->
        BubbleModelRegrouper.regroup(
            width = width,
            height = height,
            memberBounds = polygons.map { it.bounds },
            modelBounds = segmentation.detections.map { detection ->
                BubbleClusterer.IntRect(
                    left = floor(detection.left).toInt(),
                    top = floor(detection.top).toInt(),
                    right = ceil(detection.right).toInt(),
                    bottom = ceil(detection.bottom).toInt(),
                )
            },
            associations = modelMemberAssociations,
            fallbackPadding = cropPaddingPx,
            fallbackGap = bubbleClusterGap,
        )
    }.orEmpty()
    if (modelSegmentation != null) {
        timber.log.Timber.i(
            "Manga bubble association summary matched=%d unmatched=%d ocrGroups=%d modelBubbles=%d modelBubblesUsed=%d",
            modelAssociations.count { it.matched },
            modelAssociations.count { !it.matched },
            modelAssociations.size,
            modelSegmentation.detections.size,
            modelAssociations.mapNotNull { it.modelBubbleIndex }.distinct().size,
        )
        modelAssociations.forEach { association ->
            timber.log.Timber.i(
                "Manga bubble association ocr[%d] model=%s reason=%s score=%.3f memberCoverage=%.3f boxCoverage=%.3f centerInside=%s",
                association.ocrGroupIndex,
                association.modelBubbleIndex?.toString() ?: "none",
                association.reason.name,
                association.score,
                association.memberMaskCoverage,
                association.contentBoxCoverage,
                association.centerInsideMask,
            )
        }
        timber.log.Timber.i(
            "Manga bubble member association summary matched=%d unmatched=%d ocrMembers=%d modelBubblesUsed=%d",
            modelMemberAssociations.count { it.matched },
            modelMemberAssociations.count { !it.matched },
            modelMemberAssociations.size,
            modelMemberAssociations.mapNotNull { it.modelBubbleIndex }.distinct().size,
        )
        modelMemberAssociations.forEach { association ->
            timber.log.Timber.i(
                "Manga bubble member association member[%d] model=%s reason=%s score=%.3f maskCoverage=%.3f boxCoverage=%.3f centerInside=%s",
                association.ocrGroupIndex,
                association.modelBubbleIndex?.toString() ?: "none",
                association.reason.name,
                association.score,
                association.memberMaskCoverage,
                association.contentBoxCoverage,
                association.centerInsideMask,
            )
        }
        timber.log.Timber.i(
            "Manga bubble regroup summary groups=%d modelGroups=%d fallbackGroups=%d members=%d uniqueMembers=%d",
            modelRegroupedGroups.size,
            modelRegroupedGroups.count { it.source == BubbleModelRegrouper.Source.MODEL },
            modelRegroupedGroups.count {
                it.source == BubbleModelRegrouper.Source.LEGACY_FALLBACK
            },
            modelRegroupedGroups.sumOf { it.memberIndices.size },
            modelRegroupedGroups.flatMap { it.memberIndices }.distinct().size,
        )
        modelRegroupedGroups.forEachIndexed { index, group ->
            timber.log.Timber.i(
                "Manga bubble regroup group[%d] source=%s model=%s members=%s crop=%d,%d,%d,%d",
                index,
                group.source.name,
                group.modelBubbleIndex?.toString() ?: "none",
                group.memberIndices.joinToString(","),
                group.cropBounds.left,
                group.cropBounds.top,
                group.cropBounds.right,
                group.cropBounds.bottom,
            )
        }
    }

    val selectedGroups = if (useDetectorGuidedPatches) {
        guidedRegroupedGroups
    } else {
        modelRegroupedGroups
    }
    val selectedMasks = if (useDetectorGuidedPatches) {
        detectorGuidedMasks?.instanceMasks
    } else {
        modelSegmentation?.instanceMasks
    }
    val delayedMaskInput = if (createDelayedSession && selectedMasks != null) {
        MangaDelayedMaskDebugSessionManager.Input(
            width = width,
            height = height,
            sourceArgb = argb,
            candidateTextMask = analysis.textEraseMask,
            memberBounds = polygons.map { polygon -> polygon.bounds },
            modelGroups = selectedGroups,
            modelMasks = selectedMasks,
        )
    } else {
        null
    }
    val report = MangaMaskDebugReport(
        analysis = analysis,
        boxDetection = boxDetection,
        detectorGuidedMasks = detectorGuidedMasks,
        detectorGuidedMemberAssociations = guidedMemberAssociations,
        detectorGuidedExcludedMemberIndices = excludedGuidedMemberIndices,
        detectorGuidedRegroupedGroups = guidedRegroupedGroups,
        modelSegmentation = modelSegmentation,
        modelAssociations = modelAssociations,
        modelMemberAssociations = modelMemberAssociations,
        modelRegroupedGroups = modelRegroupedGroups,
        delayedMaskInput = delayedMaskInput,
    )
    return report
}

private fun pruneMangaMaskDebugArtifacts(
    directory: File,
    incomingBatch: MangaMaskDebugDumpPolicy.BatchId,
) {
    val filesByName = directory.listFiles()
        ?.filter(File::isFile)
        ?.associateBy(File::getName)
        .orEmpty()
    val stale = MangaMaskDebugDumpPolicy.filesToDeleteBeforeWriting(
        files = filesByName.values.map { file ->
            MangaMaskDebugDumpPolicy.StoredFile(
                name = file.name,
                length = file.length(),
            )
        },
        incomingBatch = incomingBatch,
    )
    var removedFiles = 0
    var removedBytes = 0L
    stale.forEach { stored ->
        val file = filesByName[stored.name] ?: return@forEach
        if (file.delete()) {
            removedFiles++
            removedBytes += stored.length
        } else {
            timber.log.Timber.w("Unable to prune manga mask debug artifact: %s", file)
        }
    }
    if (removedFiles > 0) {
        timber.log.Timber.i(
            "Pruned manga mask debug artifacts files=%d bytes=%d retainedSets=%d",
            removedFiles,
            removedBytes,
            MangaMaskDebugDumpPolicy.MAX_RETAINED_SETS,
        )
    }
}

private fun renderDetectorGuidedMaskOverlay(
    source: Bitmap,
    result: DetectorGuidedBubbleMaskExtractor.Result,
): Bitmap {
    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bitmap)
    drawTintedMask(
        canvas = canvas,
        width = source.width,
        height = source.height,
        mask = result.unionMask,
        color = Color.argb(118, 245, 158, 11),
    )
    val strokeWidth = (minOf(source.width, source.height) / 360f).coerceAtLeast(2.5f)
    val textSize = (minOf(source.width, source.height) / 64f).coerceAtLeast(15f)
    val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        this.textSize = textSize
    }
    val labelBackgroundPaint = Paint().apply {
        color = Color.argb(225, 24, 24, 27)
        style = Paint.Style.FILL
    }
    result.decisions.forEach { decision ->
        val detection = result.detections[decision.detectionIndex]
        boxPaint.color = if (decision.accepted) {
            Color.rgb(34, 197, 94)
        } else {
            Color.rgb(239, 68, 68)
        }
        canvas.drawRect(
            detection.left,
            detection.top,
            detection.right,
            detection.bottom,
            boxPaint,
        )
        drawOverlayLabel(
            canvas = canvas,
            sourceWidth = source.width,
            text = "G${decision.detectionIndex} " +
                if (decision.accepted) "OK" else decision.diagnostic.reason,
            anchorX = detection.left,
            anchorY = detection.top - LABEL_GAP_PX,
            textSize = textSize,
            labelPaint = labelPaint,
            backgroundPaint = labelBackgroundPaint,
        )
    }
    return bitmap
}

private fun renderBoxDetectionOverlay(
    source: Bitmap,
    output: MangaBubbleDetectionDebugEngine.Output,
): Bitmap {
    val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(bitmap)
    val strokeWidth = (minOf(source.width, source.height) / 360f).coerceAtLeast(2.5f)
    val textSize = (minOf(source.width, source.height) / 64f).coerceAtLeast(15f)
    val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(168, 85, 247)
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        this.textSize = textSize
    }
    val labelBackgroundPaint = Paint().apply {
        color = Color.argb(225, 24, 24, 27)
        style = Paint.Style.FILL
    }
    output.detections.forEachIndexed { index, detection ->
        canvas.drawRect(
            detection.left,
            detection.top,
            detection.right,
            detection.bottom,
            boxPaint,
        )
        drawOverlayLabel(
            canvas = canvas,
            sourceWidth = source.width,
            text = "D$index ${String.format(Locale.US, "%.2f", detection.confidence)}",
            anchorX = detection.left,
            anchorY = detection.top - LABEL_GAP_PX,
            textSize = textSize,
            labelPaint = labelPaint,
            backgroundPaint = labelBackgroundPaint,
        )
    }
    return bitmap
}

private fun renderModelRegroupingOverlay(
    source: Bitmap,
    segmentation: MangaBubbleSegmentationDebugEngine.Output,
    polygons: List<MangaMaskDebugAnalyzer.Polygon>,
    groups: List<BubbleModelRegrouper.Group>,
): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    drawTintedMask(
        canvas = canvas,
        width = source.width,
        height = source.height,
        mask = segmentation.mask,
        color = Color.argb(64, 6, 182, 212),
    )
    val strokeWidth = (minOf(source.width, source.height) / 360f).coerceAtLeast(2.5f)
    val memberStrokeWidth = (strokeWidth * 0.65f).coerceAtLeast(1.5f)
    val textSize = (minOf(source.width, source.height) / 64f).coerceAtLeast(15f)
    val groupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val memberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = memberStrokeWidth
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        this.textSize = textSize
    }
    val labelBackgroundPaint = Paint().apply {
        color = Color.argb(225, 24, 24, 27)
        style = Paint.Style.FILL
    }
    groups.forEachIndexed { groupIndex, group ->
        val color = if (group.source == BubbleModelRegrouper.Source.MODEL) {
            Color.rgb(34, 197, 94)
        } else {
            Color.rgb(249, 115, 22)
        }
        groupPaint.color = color
        memberPaint.color = color
        val crop = group.cropBounds
        canvas.drawRect(
            crop.left.toFloat(),
            crop.top.toFloat(),
            crop.right.toFloat(),
            crop.bottom.toFloat(),
            groupPaint,
        )
        group.memberIndices.forEach { memberIndex ->
            val polygon = polygons.getOrNull(memberIndex) ?: return@forEach
            val path = Path().apply {
                polygon.points.firstOrNull()?.let { first ->
                    moveTo(first.x, first.y)
                    polygon.points.drop(1).forEach { point -> lineTo(point.x, point.y) }
                    close()
                }
            }
            canvas.drawPath(path, memberPaint)
        }
        val label = if (group.source == BubbleModelRegrouper.Source.MODEL) {
            "G$groupIndex M${group.modelBubbleIndex} P${group.memberIndices.joinToString(",")}"
        } else {
            "G$groupIndex fallback P${group.memberIndices.joinToString(",")}"
        }
        drawOverlayLabel(
            canvas = canvas,
            sourceWidth = source.width,
            text = label,
            anchorX = crop.left.toFloat(),
            anchorY = (crop.top - LABEL_GAP_PX).toFloat(),
            textSize = textSize,
            labelPaint = labelPaint,
            backgroundPaint = labelBackgroundPaint,
        )
    }
    return output
}

private fun renderModelMemberAssociationOverlay(
    source: Bitmap,
    segmentation: MangaBubbleSegmentationDebugEngine.Output,
    polygons: List<MangaMaskDebugAnalyzer.Polygon>,
    associations: List<BubbleMaskAssociator.Association>,
): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    drawTintedMask(
        canvas = canvas,
        width = source.width,
        height = source.height,
        mask = segmentation.mask,
        color = Color.argb(72, 6, 182, 212),
    )
    val strokeWidth = (minOf(source.width, source.height) / 420f).coerceAtLeast(2f)
    val textSize = (minOf(source.width, source.height) / 68f).coerceAtLeast(14f)
    val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(6, 182, 212)
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val memberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        this.textSize = textSize
    }
    val labelBackgroundPaint = Paint().apply {
        color = Color.argb(225, 24, 24, 27)
        style = Paint.Style.FILL
    }
    segmentation.detections.forEach { detection ->
        canvas.drawRect(
            detection.left,
            detection.top,
            detection.right,
            detection.bottom,
            modelPaint,
        )
    }
    associations.forEach { association ->
        val polygon = polygons.getOrNull(association.ocrGroupIndex) ?: return@forEach
        memberPaint.color = if (association.matched) {
            Color.rgb(34, 197, 94)
        } else {
            Color.rgb(249, 115, 22)
        }
        val path = Path().apply {
            polygon.points.firstOrNull()?.let { first ->
                moveTo(first.x, first.y)
                polygon.points.drop(1).forEach { point -> lineTo(point.x, point.y) }
                close()
            }
        }
        canvas.drawPath(path, memberPaint)
        val bounds = polygon.bounds
        association.modelBubbleIndex?.let { modelIndex ->
            segmentation.detections.getOrNull(modelIndex)?.let { detection ->
                canvas.drawLine(
                    (bounds.left + bounds.right) / 2f,
                    (bounds.top + bounds.bottom) / 2f,
                    (detection.left + detection.right) / 2f,
                    (detection.top + detection.bottom) / 2f,
                    memberPaint,
                )
            }
        }
        val label = if (association.matched) {
            "P${association.ocrGroupIndex}->M${association.modelBubbleIndex} " +
                "c${(association.memberMaskCoverage * 100).roundToInt()}"
        } else {
            "P${association.ocrGroupIndex} NO ${shortAssociationReason(association.reason)}"
        }
        drawOverlayLabel(
            canvas = canvas,
            sourceWidth = source.width,
            text = label,
            anchorX = bounds.left.toFloat(),
            anchorY = (bounds.top - LABEL_GAP_PX).toFloat(),
            textSize = textSize,
            labelPaint = labelPaint,
            backgroundPaint = labelBackgroundPaint,
        )
    }
    return output
}

private fun renderModelAssociationOverlay(
    source: Bitmap,
    segmentation: MangaBubbleSegmentationDebugEngine.Output,
    bubbles: List<Bubble>,
    associations: List<BubbleMaskAssociator.Association>,
): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    drawTintedMask(
        canvas = canvas,
        width = source.width,
        height = source.height,
        mask = segmentation.mask,
        color = Color.argb(82, 6, 182, 212),
    )
    val strokeWidth = (minOf(source.width, source.height) / 420f).coerceAtLeast(2f)
    val textSize = (minOf(source.width, source.height) / 62f).coerceAtLeast(15f)
    val modelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(6, 182, 212)
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val matchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        this.textSize = textSize
    }
    val labelBackgroundPaint = Paint().apply {
        color = Color.argb(225, 24, 24, 27)
        style = Paint.Style.FILL
    }

    segmentation.detections.forEachIndexed { index, detection ->
        canvas.drawRect(
            detection.left,
            detection.top,
            detection.right,
            detection.bottom,
            modelPaint,
        )
        drawOverlayLabel(
            canvas = canvas,
            sourceWidth = source.width,
            text = "M$index ${(detection.confidence * 100).roundToInt()}%",
            anchorX = detection.left,
            anchorY = detection.bottom + textSize + LABEL_GAP_PX,
            textSize = textSize,
            labelPaint = labelPaint,
            backgroundPaint = labelBackgroundPaint,
        )
    }

    associations.forEach { association ->
        val bubble = bubbles.getOrNull(association.ocrGroupIndex) ?: return@forEach
        val content = bubble.contentRect
        matchPaint.color = if (association.matched) {
            Color.rgb(34, 197, 94)
        } else {
            Color.rgb(249, 115, 22)
        }
        canvas.drawRect(
            content.left.toFloat(),
            content.top.toFloat(),
            content.right.toFloat(),
            content.bottom.toFloat(),
            matchPaint,
        )
        association.modelBubbleIndex?.let { modelIndex ->
            segmentation.detections.getOrNull(modelIndex)?.let { detection ->
                canvas.drawLine(
                    (content.left + content.right) / 2f,
                    (content.top + content.bottom) / 2f,
                    (detection.left + detection.right) / 2f,
                    (detection.top + detection.bottom) / 2f,
                    matchPaint,
                )
            }
        }
        val label = if (association.matched) {
            "O${association.ocrGroupIndex}->M${association.modelBubbleIndex} " +
                "c${(association.memberMaskCoverage * 100).roundToInt()} " +
                "b${(association.contentBoxCoverage * 100).roundToInt()}"
        } else {
            "O${association.ocrGroupIndex} NO ${shortAssociationReason(association.reason)} " +
                "c${(association.memberMaskCoverage * 100).roundToInt()}"
        }
        drawOverlayLabel(
            canvas = canvas,
            sourceWidth = source.width,
            text = label,
            anchorX = content.left.toFloat(),
            anchorY = (content.top - LABEL_GAP_PX).toFloat(),
            textSize = textSize,
            labelPaint = labelPaint,
            backgroundPaint = labelBackgroundPaint,
        )
    }
    return output
}

private fun drawOverlayLabel(
    canvas: Canvas,
    sourceWidth: Int,
    text: String,
    anchorX: Float,
    anchorY: Float,
    textSize: Float,
    labelPaint: Paint,
    backgroundPaint: Paint,
) {
    val labelWidth = labelPaint.measureText(text)
    val left = anchorX.coerceIn(
        0f,
        (sourceWidth - labelWidth - LABEL_PADDING_PX * 2).coerceAtLeast(0f),
    )
    val baseline = anchorY.coerceAtLeast(textSize + LABEL_PADDING_PX)
    canvas.drawRect(
        left,
        baseline - textSize - LABEL_PADDING_PX,
        (left + labelWidth + LABEL_PADDING_PX * 2).coerceAtMost(sourceWidth.toFloat()),
        baseline + LABEL_PADDING_PX,
        backgroundPaint,
    )
    canvas.drawText(text, left + LABEL_PADDING_PX, baseline, labelPaint)
}

private fun shortAssociationReason(reason: BubbleMaskAssociator.Reason): String = when (reason) {
    BubbleMaskAssociator.Reason.UNMATCHED_NO_MODEL_BUBBLE -> "no-model"
    BubbleMaskAssociator.Reason.UNMATCHED_LOW_COVERAGE -> "coverage"
    BubbleMaskAssociator.Reason.UNMATCHED_AMBIGUOUS -> "ambiguous"
    BubbleMaskAssociator.Reason.MATCHED_CENTER_AND_COVERAGE -> "center"
    BubbleMaskAssociator.Reason.MATCHED_COVERAGE -> "coverage"
}

private fun renderModelSegmentationOverlay(
    source: Bitmap,
    segmentation: MangaBubbleSegmentationDebugEngine.Output,
): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    drawTintedMask(
        canvas = canvas,
        width = source.width,
        height = source.height,
        mask = segmentation.mask,
        color = Color.argb(105, 6, 182, 212),
    )
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(6, 182, 212)
        style = Paint.Style.STROKE
        strokeWidth = (minOf(source.width, source.height) / 420f).coerceAtLeast(2f)
    }
    segmentation.detections.forEach { detection ->
        canvas.drawRect(
            detection.left,
            detection.top,
            detection.right,
            detection.bottom,
            stroke,
        )
    }
    return output
}

internal fun formatMangaMaskDiagnostics(
    diagnostics: List<MangaMaskDebugAnalyzer.BubbleDiagnostic>,
): String = diagnostics.mapIndexed { index, diagnostic ->
    val state = if (diagnostic.accepted) "accepted" else "rejected"
    "#$index $state reason=${diagnostic.reason} attempts=${diagnostic.attempts} " +
        "roi=${diagnostic.roi.left},${diagnostic.roi.top}," +
        "${diagnostic.roi.right},${diagnostic.roi.bottom} pixels=${diagnostic.regionPixels}"
}.joinToString(" | ")

private fun renderOcrQuads(
    source: Bitmap,
    quads: List<DBPostprocessor.Quad>,
): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(239, 68, 68)
        style = Paint.Style.STROKE
        strokeWidth = (minOf(source.width, source.height) / 360f).coerceAtLeast(2f)
    }
    quads.forEach { quad ->
        val path = Path().apply {
            moveTo(quad.p0.x, quad.p0.y)
            lineTo(quad.p1.x, quad.p1.y)
            lineTo(quad.p2.x, quad.p2.y)
            lineTo(quad.p3.x, quad.p3.y)
            close()
        }
        canvas.drawPath(path, paint)
    }
    return output
}

private fun renderBinaryMask(
    width: Int,
    height: Int,
    mask: BooleanArray,
): Bitmap {
    val colors = IntArray(mask.size) { index ->
        if (mask[index]) Color.WHITE else Color.BLACK
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(colors, 0, width, 0, 0, width, height)
    }
}

private fun renderDiagnosticOverlay(
    source: Bitmap,
    analysis: MangaMaskDebugAnalyzer.Analysis,
): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(output)
    drawTintedMask(
        canvas = canvas,
        width = source.width,
        height = source.height,
        mask = analysis.bubbleInteriorMask,
        color = Color.argb(92, 34, 197, 94),
    )
    drawTintedMask(
        canvas = canvas,
        width = source.width,
        height = source.height,
        mask = analysis.textEraseMask,
        color = Color.argb(190, 250, 204, 21),
    )

    val strokeWidth = (minOf(source.width, source.height) / 420f).coerceAtLeast(2f)
    val textSize = (minOf(source.width, source.height) / 60f).coerceAtLeast(16f)
    val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.textSize = textSize
        color = Color.WHITE
    }
    val labelBackgroundPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 24, 24, 27)
    }
    analysis.bubbles.forEachIndexed { index, diagnostic ->
        boxPaint.color = if (diagnostic.accepted) {
            Color.rgb(34, 197, 94)
        } else {
            Color.rgb(239, 68, 68)
        }
        val roi = diagnostic.roi
        canvas.drawRect(
            roi.left.toFloat(),
            roi.top.toFloat(),
            (roi.right - 1).coerceAtLeast(roi.left).toFloat(),
            (roi.bottom - 1).coerceAtLeast(roi.top).toFloat(),
            boxPaint,
        )

        val label = if (diagnostic.accepted) {
            "#$index OK ${String.format(Locale.US, "%.2f", diagnostic.confidence)} " +
                "c${(diagnostic.memberCoverage * 100).roundToInt()} x${diagnostic.attempts}"
        } else {
            "#$index NO ${shortDiagnosticReason(diagnostic.reason)} x${diagnostic.attempts}"
        }
        val labelWidth = labelPaint.measureText(label)
        val labelLeft = roi.left.toFloat()
            .coerceAtMost((source.width - labelWidth - LABEL_PADDING_PX).coerceAtLeast(0f))
        val labelBaseline = (roi.top - LABEL_GAP_PX).toFloat()
            .coerceAtLeast(textSize + LABEL_PADDING_PX)
        canvas.drawRect(
            labelLeft,
            labelBaseline - textSize - LABEL_PADDING_PX,
            (labelLeft + labelWidth + LABEL_PADDING_PX * 2).coerceAtMost(source.width.toFloat()),
            labelBaseline + LABEL_PADDING_PX,
            labelBackgroundPaint,
        )
        canvas.drawText(
            label,
            labelLeft + LABEL_PADDING_PX,
            labelBaseline,
            labelPaint,
        )
    }
    return output
}

private fun shortDiagnosticReason(reason: String): String = when (reason) {
    "content_too_small" -> "tiny"
    "region_too_small" -> "small"
    "region_leaked_to_roi" -> "leak"
    "region_too_large" -> "large"
    "member_coverage_low" -> "coverage"
    "background_too_dark" -> "dark"
    "no_background_seed" -> "no-seed"
    else -> reason
}

private fun drawTintedMask(
    canvas: Canvas,
    width: Int,
    height: Int,
    mask: BooleanArray,
    color: Int,
) {
    val pixels = IntArray(mask.size) { index -> if (mask[index]) color else Color.TRANSPARENT }
    val tint = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    try {
        canvas.drawBitmap(tint, 0f, 0f, null)
    } finally {
        tint.recycle()
    }
}

private const val LABEL_PADDING_PX = 6f
private const val LABEL_GAP_PX = 4f
