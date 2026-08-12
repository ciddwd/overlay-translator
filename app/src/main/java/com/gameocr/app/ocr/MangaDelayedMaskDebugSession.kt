package com.gameocr.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Singleton

internal fun reusableBackgroundPatches(
    shapeTranslationPatches: List<ShapeAwareBubblePatch>,
    modelBackgroundPatches: List<ShapeAwareBubblePatch>,
    textBackgroundPatches: List<ShapeAwareBubblePatch>,
): List<ShapeAwareBubblePatch> {
    val translatedModelIndices = shapeTranslationPatches.asSequence()
        .filter { it.role == ShapeAwareBubblePatch.Role.SHAPE_TRANSLATION }
        .mapNotNull { it.modelBubbleIndex }
        .toSet()
    return modelBackgroundPatches.filter { it.modelBubbleIndex !in translatedModelIndices } +
        textBackgroundPatches
}

/**
 * Keeps at most one manga mask prototype frame alive between OCR and translation completion.
 *
 * A session is consumed when the corresponding translated block list is claimed. This bounds the
 * retained debug memory and prevents a late translation from accidentally using a newer frame.
 */
@Singleton
class ShapeAwareBubbleSessionStore @Inject constructor() {
    internal val manager = MangaDelayedMaskDebugSessionManager()
}

internal class MangaDelayedMaskDebugSessionManager {

    data class Input(
        val width: Int,
        val height: Int,
        val sourceArgb: IntArray,
        val candidateTextMask: BooleanArray,
        val memberBounds: List<IntRect>,
        val modelGroups: List<BubbleModelRegrouper.Group>,
        val modelMasks: List<BubbleSegmentationPostprocessor.InstanceMask>,
        val modelMaskQualities: List<BubbleShapeMaskQuality> =
            List(modelMasks.size) { BubbleShapeMaskQuality.TRUSTED },
    ) {
        init {
            require(width > 0 && height > 0)
            require(sourceArgb.size == width * height)
            require(candidateTextMask.size == width * height)
            require(modelMaskQualities.size == modelMasks.size)
        }
    }

    data class Batch(
        internal val sessionId: Long,
        internal val input: Input,
        internal val blocks: List<DelayedTextEraseMaskBuilder.ConfirmedBlock>,
        internal val textBlocks: List<TextBlock>,
        internal val coordinateScale: Float,
    ) {
        val blockCount: Int
            get() = blocks.size
    }

    data class Prepared(
        internal val batch: Batch,
        internal val confirmedBlocks: List<DelayedTextEraseMaskBuilder.ConfirmedBlock>,
        val result: DelayedTextEraseMaskBuilder.Result,
        val repairResult: MaskedBackgroundRepairer.Result,
        val localRepairResult: LocalBubbleBackgroundRepairer.Result,
        val repairDurationMs: Long,
        val textMaskResult: TextPixelMaskBuilder.Result,
        val textMaskDurationUs: Long,
        val textRepairResult: LocalTextBackgroundRepairer.Result,
        val textRepairCoreDurationUs: Long,
        val textRepairDurationMs: Long,
        val modelBackgroundPatches: List<ShapeAwareBubblePatch>,
        val textBackgroundPatches: List<ShapeAwareBubblePatch>,
    ) {
        val backgroundPatches: List<ShapeAwareBubblePatch>
            get() = modelBackgroundPatches + textBackgroundPatches

        val blockIndices: Set<Int>
            get() = confirmedBlocks.mapTo(linkedSetOf()) { it.blockIndex }
    }

    data class Dump(
        val result: DelayedTextEraseMaskBuilder.Result,
        val repairResult: MaskedBackgroundRepairer.Result,
        val localRepairResult: LocalBubbleBackgroundRepairer.Result,
        val repairDurationMs: Long,
        val textMaskResult: TextPixelMaskBuilder.Result,
        val textMaskDurationUs: Long,
        val textRepairResult: LocalTextBackgroundRepairer.Result,
        val textRepairCoreDurationUs: Long,
        val textRepairDurationMs: Long,
        val shapeLayoutDecisions: List<ShapeLayoutDecision>,
        val shapeLayoutDurationMs: Long,
        val shapeAwarePatches: List<ShapeAwareBubblePatch>,
        val modelBackgroundPatches: List<ShapeAwareBubblePatch>,
        val textBackgroundPatches: List<ShapeAwareBubblePatch>,
        val displayedPatchCount: Int,
        val translatedBlockCount: Int,
    )

    data class ShapeLayoutDecision(
        val modelBubbleIndex: Int,
        val accepted: Boolean,
        val reason: String,
        val orientation: ShapeAwareTextLayout.Orientation,
        val fontSizePx: Int,
        val runCount: Int,
        val textLength: Int,
    )

    private data class ShapeLayoutPreview(
        val decisions: List<ShapeLayoutDecision>,
        val patches: List<ShapeAwareBubblePatch>,
    )

    private val lock = Any()
    private val sequence = AtomicLong(0L)
    private var pending: Pair<Long, Input>? = null

    fun publish(input: Input) {
        synchronized(lock) {
            pending = sequence.incrementAndGet() to input
        }
    }

    fun claim(
        imageWidth: Int,
        imageHeight: Int,
        coordinateScale: Float,
        blocks: List<TextBlock>,
    ): Batch? {
        if (blocks.isEmpty() || coordinateScale <= 0f) return null
        return synchronized(lock) {
            val current = pending ?: return@synchronized null
            pending = null
            val input = current.second
            if (input.width != imageWidth || input.height != imageHeight) {
                return@synchronized null
            }
            Batch(
                sessionId = current.first,
                input = input,
                blocks = blocks.mapIndexed { index, block ->
                    DelayedTextEraseMaskBuilder.ConfirmedBlock(
                        blockIndex = index,
                        sourceBoxes = block.sourceBoxes.map { source ->
                            IntRect(
                                left = floor(source.left * coordinateScale).toInt(),
                                top = floor(source.top * coordinateScale).toInt(),
                                right = ceil(source.right * coordinateScale).toInt(),
                                bottom = ceil(source.bottom * coordinateScale).toInt(),
                            )
                        },
                    )
                },
                textBlocks = blocks.toList(),
                coordinateScale = coordinateScale,
            )
        }
    }

    suspend fun prepare(
        batch: Batch,
        blockIndices: Set<Int>,
    ): Prepared = withContext(Dispatchers.Default) {
        val confirmed = batch.blocks.filter { block ->
            block.blockIndex in blockIndices
        }
        val input = batch.input
        val result = DelayedTextEraseMaskBuilder.build(
            width = input.width,
            height = input.height,
            candidateTextMask = input.candidateTextMask,
            memberBounds = input.memberBounds,
            modelGroups = input.modelGroups,
            modelMasks = input.modelMasks,
            confirmedBlocks = confirmed,
        )
        val repairStartedNs = System.nanoTime()
        val localRepairResult = LocalBubbleBackgroundRepairer.repair(
            width = input.width,
            height = input.height,
            sourceArgb = input.sourceArgb,
            eraseMask = result.mask,
            regions = buildLocalRepairRegions(input, result),
        )
        val repairResult = localRepairResult.repairResult
        val repairDurationMs = (System.nanoTime() - repairStartedNs) / 1_000_000L
        val modelBackgroundPatches = buildModelBackgroundPatches(
            input = input,
            batch = batch,
            result = result,
            localRepairResult = localRepairResult,
        )
        val modelBackgroundBlockIndices = modelBackgroundPatches
            .flatMapTo(linkedSetOf()) { it.blockIndices }
        val textRepairStartedNs = System.nanoTime()
        val textMaskStartedNs = System.nanoTime()
        val textMaskResult = TextPixelMaskBuilder.build(
            width = input.width,
            height = input.height,
            candidateTextMask = input.candidateTextMask,
            confirmedBlocks = confirmed.filter { it.blockIndex !in modelBackgroundBlockIndices },
        )
        val textMaskDurationUs = (System.nanoTime() - textMaskStartedNs) / NANOS_PER_MICROSECOND
        val textRepairCoreStartedNs = System.nanoTime()
        val textRepairResult = LocalTextBackgroundRepairer.repair(
            imageWidth = input.width,
            imageHeight = input.height,
            sourceArgb = input.sourceArgb,
            masks = textMaskResult.masks,
            coordinateScale = batch.coordinateScale,
        )
        val textRepairCoreDurationUs =
            (System.nanoTime() - textRepairCoreStartedNs) / NANOS_PER_MICROSECOND
        val textRepairDurationMs =
            (System.nanoTime() - textRepairStartedNs) / 1_000_000L
        val textBackgroundPatches = buildTextBackgroundPatches(
            repairResult = textRepairResult,
            coordinateScale = batch.coordinateScale,
        )
        Prepared(
            batch = batch,
            confirmedBlocks = confirmed,
            result = result,
            repairResult = repairResult,
            localRepairResult = localRepairResult,
            repairDurationMs = repairDurationMs,
            textMaskResult = textMaskResult,
            textMaskDurationUs = textMaskDurationUs,
            textRepairResult = textRepairResult,
            textRepairCoreDurationUs = textRepairCoreDurationUs,
            textRepairDurationMs = textRepairDurationMs,
            modelBackgroundPatches = modelBackgroundPatches,
            textBackgroundPatches = textBackgroundPatches,
        )
    }

    suspend fun finish(
        prepared: Prepared,
        successfulBlockIndices: Set<Int>,
        translatedBlockTexts: Map<Int, String>,
        outputOrientation: TextOrientation,
        followBlockOrientations: Boolean,
        displayPatches: suspend (List<ShapeAwareBubblePatch>) -> Int,
    ): Dump = withContext(Dispatchers.Default) {
        val shapeLayoutStartedNs = System.nanoTime()
        val shapeLayoutPreview = renderShapeAwareLayoutPreview(
            input = prepared.batch.input,
            batch = prepared.batch,
            delayedMaskResult = prepared.result,
            localRepairResult = prepared.localRepairResult,
            successfulBlockIndices = successfulBlockIndices,
            translatedBlockTexts = translatedBlockTexts,
            outputOrientation = outputOrientation,
            followBlockOrientations = followBlockOrientations,
        )
        val shapeLayoutDurationMs =
            (System.nanoTime() - shapeLayoutStartedNs) / 1_000_000L
        val retainedBackgroundPatches = reusableBackgroundPatches(
            shapeTranslationPatches = shapeLayoutPreview.patches,
            modelBackgroundPatches = prepared.modelBackgroundPatches,
            textBackgroundPatches = prepared.textBackgroundPatches,
        )
        val displayedPatchCount = displayPatches(
            shapeLayoutPreview.patches + retainedBackgroundPatches,
        )
        Dump(
            result = prepared.result,
            repairResult = prepared.repairResult,
            localRepairResult = prepared.localRepairResult,
            repairDurationMs = prepared.repairDurationMs,
            textMaskResult = prepared.textMaskResult,
            textMaskDurationUs = prepared.textMaskDurationUs,
            textRepairResult = prepared.textRepairResult,
            textRepairCoreDurationUs = prepared.textRepairCoreDurationUs,
            textRepairDurationMs = prepared.textRepairDurationMs,
            shapeLayoutDecisions = shapeLayoutPreview.decisions,
            shapeLayoutDurationMs = shapeLayoutDurationMs,
            shapeAwarePatches = shapeLayoutPreview.patches,
            modelBackgroundPatches = prepared.modelBackgroundPatches,
            textBackgroundPatches = prepared.textBackgroundPatches,
            displayedPatchCount = displayedPatchCount,
            translatedBlockCount = successfulBlockIndices.size,
        )
    }

    suspend fun finish(
        batch: Batch,
        successfulBlockIndices: Set<Int>,
        translatedBlockTexts: Map<Int, String>,
        outputOrientation: TextOrientation,
        followBlockOrientations: Boolean,
        displayPatches: suspend (List<ShapeAwareBubblePatch>) -> Int,
    ): Dump {
        val prepared = prepare(batch, successfulBlockIndices)
        return finish(
            prepared = prepared,
            successfulBlockIndices = successfulBlockIndices,
            translatedBlockTexts = translatedBlockTexts,
            outputOrientation = outputOrientation,
            followBlockOrientations = followBlockOrientations,
            displayPatches = displayPatches,
        )
    }

    private fun buildTextBackgroundPatches(
        repairResult: LocalTextBackgroundRepairer.Result,
        coordinateScale: Float,
    ): List<ShapeAwareBubblePatch> = repairResult.blocks.mapNotNull { block ->
        if (!block.displayable) return@mapNotNull null
        val pixels = block.patchPixels ?: return@mapNotNull null
        ShapeAwareBubblePatch(
            modelBubbleIndex = null,
            bounds = block.mask.bounds,
            pixels = pixels,
            coordinateScale = coordinateScale,
            blockIndices = listOf(block.blockIndex),
            role = ShapeAwareBubblePatch.Role.TEXT_BACKGROUND,
        )
    }

    private fun buildModelBackgroundPatches(
        input: Input,
        batch: Batch,
        result: DelayedTextEraseMaskBuilder.Result,
        localRepairResult: LocalBubbleBackgroundRepairer.Result,
    ): List<ShapeAwareBubblePatch> {
        val repairedModels = LocalBubbleBackgroundRepairer.fullyRepairedModelIndices(
            localRepairResult.crops,
        )
        return acceptedBlockIndicesByModel(result).mapNotNull { (modelIndex, blockIndices) ->
            if (modelIndex !in repairedModels) return@mapNotNull null
            if (
                input.modelMaskQualities.getOrNull(modelIndex) !=
                BubbleShapeMaskQuality.TRUSTED
            ) {
                return@mapNotNull null
            }
            val modelMask = input.modelMasks.getOrNull(modelIndex) ?: return@mapNotNull null
            ShapeAwareBubblePatch(
                modelBubbleIndex = modelIndex,
                bounds = IntRect(
                    left = modelMask.left,
                    top = modelMask.top,
                    right = modelMask.left + modelMask.width,
                    bottom = modelMask.top + modelMask.height,
                ),
                pixels = ShapeAwareBubblePatchComposer.composeBackground(
                    imageWidth = input.width,
                    imageHeight = input.height,
                    repairedPixels = localRepairResult.repairResult.pixels,
                    repairedMask = localRepairResult.repairResult.repairedMask,
                    modelMask = modelMask,
                ),
                coordinateScale = batch.coordinateScale,
                blockIndices = blockIndices.sorted(),
                role = ShapeAwareBubblePatch.Role.TEXT_BACKGROUND,
            )
        }
    }

    private fun acceptedBlockIndicesByModel(
        result: DelayedTextEraseMaskBuilder.Result,
        includedBlockIndices: Set<Int>? = null,
    ): Map<Int, Set<Int>> {
        val blockIndicesByModel = linkedMapOf<Int, MutableSet<Int>>()
        result.decisions.asSequence()
            .filter { it.accepted }
            .filter { includedBlockIndices == null || it.blockIndex in includedBlockIndices }
            .forEach { decision ->
                decision.modelBubbleIndices.forEach { modelIndex ->
                    blockIndicesByModel.getOrPut(modelIndex) { linkedSetOf() } += decision.blockIndex
                }
            }
        return blockIndicesByModel
    }

    private fun buildLocalRepairRegions(
        input: Input,
        result: DelayedTextEraseMaskBuilder.Result,
    ): List<LocalBubbleBackgroundRepairer.Region> {
        val memberToModel = mutableMapOf<Int, Int>()
        input.modelGroups.forEach { group ->
            val modelIndex = group.modelBubbleIndex
            if (group.source != BubbleModelRegrouper.Source.MODEL || modelIndex == null) {
                return@forEach
            }
            group.memberIndices.forEach { memberIndex ->
                memberToModel.putIfAbsent(memberIndex, modelIndex)
            }
        }
        val membersByModel = linkedMapOf<Int, MutableSet<Int>>()
        result.decisions.asSequence()
            .filter { it.accepted }
            .flatMap { it.memberIndices.asSequence() }
            .distinct()
            .forEach { memberIndex ->
                val modelIndex = memberToModel[memberIndex] ?: return@forEach
                membersByModel.getOrPut(modelIndex) { linkedSetOf() } += memberIndex
            }
        return membersByModel.mapNotNull { (modelIndex, memberIndices) ->
            if (
                input.modelMaskQualities.getOrNull(modelIndex) !=
                BubbleShapeMaskQuality.TRUSTED
            ) {
                return@mapNotNull null
            }
            val modelMask = input.modelMasks.getOrNull(modelIndex) ?: return@mapNotNull null
            val bounds = memberIndices.mapNotNull(input.memberBounds::getOrNull)
            if (bounds.isEmpty()) return@mapNotNull null
            LocalBubbleBackgroundRepairer.Region(
                modelBubbleIndex = modelIndex,
                memberBounds = bounds,
                modelMask = modelMask,
            )
        }
    }

    private fun renderShapeAwareLayoutPreview(
        input: Input,
        batch: Batch,
        delayedMaskResult: DelayedTextEraseMaskBuilder.Result,
        localRepairResult: LocalBubbleBackgroundRepairer.Result,
        successfulBlockIndices: Set<Int>,
        translatedBlockTexts: Map<Int, String>,
        outputOrientation: TextOrientation,
        followBlockOrientations: Boolean,
    ): ShapeLayoutPreview {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
        }
        val repairedModels = LocalBubbleBackgroundRepairer.fullyRepairedModelIndices(
            localRepairResult.crops,
        )
        val blockIndicesByModel = acceptedBlockIndicesByModel(
            result = delayedMaskResult,
            includedBlockIndices = successfulBlockIndices,
        )
        val decisions = mutableListOf<ShapeLayoutDecision>()
        val patches = mutableListOf<ShapeAwareBubblePatch>()
        val ambiguousBlockIndices = delayedMaskResult.decisions.asSequence()
            .filter {
                it.accepted &&
                    it.blockIndex in successfulBlockIndices &&
                    it.modelBubbleIndices.size != 1
            }
            .map { it.blockIndex }
            .toSet()
        blockIndicesByModel.forEach { (modelIndex, blockIndices) ->
            val modelMask = input.modelMasks.getOrNull(modelIndex)
            val maskQuality = input.modelMaskQualities.getOrNull(modelIndex)
                ?: BubbleShapeMaskQuality.REJECTED
            val text = blockIndices.asSequence()
                .sorted()
                .mapNotNull(translatedBlockTexts::get)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString(" ")
            val orientation = resolveShapeLayoutOrientation(
                batch = batch,
                blockIndices = blockIndices,
                outputOrientation = outputOrientation,
                followBlockOrientations = followBlockOrientations,
            )
            if (modelMask == null) {
                decisions += shapeLayoutFallback(
                    modelIndex = modelIndex,
                    reason = "MODEL_MASK_UNAVAILABLE",
                    orientation = orientation,
                    textLength = text.length,
                )
                return@forEach
            }
            val maskQualityRejection = maskQuality.shapePatchRejectionReason
            if (maskQualityRejection != null) {
                decisions += shapeLayoutFallback(
                    modelIndex = modelIndex,
                    reason = maskQualityRejection,
                    orientation = orientation,
                    textLength = text.length,
                )
                return@forEach
            }
            if (modelIndex !in repairedModels) {
                decisions += shapeLayoutFallback(
                    modelIndex = modelIndex,
                    reason = "BACKGROUND_REPAIR_UNAVAILABLE",
                    orientation = orientation,
                    textLength = text.length,
                )
                return@forEach
            }
            if (text.isEmpty()) {
                decisions += shapeLayoutFallback(
                    modelIndex = modelIndex,
                    reason = "TRANSLATION_UNAVAILABLE",
                    orientation = orientation,
                    textLength = 0,
                )
                return@forEach
            }
            if (blockIndices.any { it in ambiguousBlockIndices }) {
                decisions += shapeLayoutFallback(
                    modelIndex = modelIndex,
                    reason = "AMBIGUOUS_BLOCK_MODEL_MAPPING",
                    orientation = orientation,
                    textLength = text.length,
                )
                return@forEach
            }

            val maximumFontSize = (
                minOf(modelMask.width, modelMask.height) * MAX_FONT_MINOR_AXIS_RATIO
            ).toInt().coerceIn(MIN_SHAPE_FONT_PX, MAX_SHAPE_FONT_PX)
            val layout = ShapeAwareTextLayout.layout(
                width = modelMask.width,
                height = modelMask.height,
                mask = modelMask.pixels,
                text = text,
                orientation = orientation,
                minimumFontSizePx = MIN_SHAPE_FONT_PX,
                maximumFontSizePx = maximumFontSize,
                textMeasurer = ShapeAwareTextLayout.TextMeasurer { candidate, fontSize ->
                    paint.textSize = fontSize.toFloat()
                    paint.measureText(candidate)
                },
            )
            if (!layout.accepted) {
                decisions += shapeLayoutFallback(
                    modelIndex = modelIndex,
                    reason = layout.reason.name,
                    orientation = orientation,
                    textLength = text.length,
                )
                return@forEach
            }

            paint.textSize = layout.fontSizePx.toFloat()
            paint.color = readableShapeTextColor(
                width = input.width,
                height = input.height,
                pixels = localRepairResult.repairResult.pixels,
                repairedMask = localRepairResult.repairResult.repairedMask,
                modelMask = modelMask,
            )
            val patchBitmap = renderPixels(
                width = modelMask.width,
                height = modelMask.height,
                pixels = ShapeAwareBubblePatchComposer.composeBackground(
                    imageWidth = input.width,
                    imageHeight = input.height,
                    repairedPixels = localRepairResult.repairResult.pixels,
                    repairedMask = localRepairResult.repairResult.repairedMask,
                    modelMask = modelMask,
                ),
            )
            val patchCanvas = Canvas(patchBitmap)
            when (layout.orientation) {
                ShapeAwareTextLayout.Orientation.HORIZONTAL ->
                    drawHorizontalShapeLayout(
                        canvas = patchCanvas,
                        paint = paint,
                        originX = 0,
                        originY = 0,
                        runs = layout.runs,
                    )
                ShapeAwareTextLayout.Orientation.VERTICAL_RIGHT_TO_LEFT,
                ShapeAwareTextLayout.Orientation.VERTICAL_LEFT_TO_RIGHT ->
                    drawVerticalShapeLayout(
                        canvas = patchCanvas,
                        paint = paint,
                        originX = 0,
                        originY = 0,
                        runs = layout.runs,
                    )
            }
            val patchPixels = IntArray(modelMask.width * modelMask.height)
            patchBitmap.getPixels(
                patchPixels,
                0,
                modelMask.width,
                0,
                0,
                modelMask.width,
                modelMask.height,
            )
            patchBitmap.recycle()
            patches += ShapeAwareBubblePatch(
                modelBubbleIndex = modelIndex,
                bounds = IntRect(
                    left = modelMask.left,
                    top = modelMask.top,
                    right = modelMask.left + modelMask.width,
                    bottom = modelMask.top + modelMask.height,
                ),
                pixels = patchPixels,
                coordinateScale = batch.coordinateScale,
                blockIndices = blockIndices.sorted(),
            )
            decisions += ShapeLayoutDecision(
                modelBubbleIndex = modelIndex,
                accepted = true,
                reason = layout.reason.name,
                orientation = orientation,
                fontSizePx = layout.fontSizePx,
                runCount = layout.runs.size,
                textLength = text.length,
            )
        }
        return ShapeLayoutPreview(
            decisions = decisions,
            patches = patches,
        )
    }

    private fun resolveShapeLayoutOrientation(
        batch: Batch,
        blockIndices: Set<Int>,
        outputOrientation: TextOrientation,
        followBlockOrientations: Boolean,
    ): ShapeAwareTextLayout.Orientation {
        val selected = if (followBlockOrientations) {
            val orientations = blockIndices.mapNotNull { index ->
                batch.textBlocks.getOrNull(index)?.layoutOrientation
            }
            val verticalCount = orientations.count {
                it == TextOrientation.VERTICAL_RTL || it == TextOrientation.VERTICAL_LTR
            }
            if (verticalCount > orientations.size / 2) {
                if (orientations.count { it == TextOrientation.VERTICAL_LTR } > verticalCount / 2) {
                    TextOrientation.VERTICAL_LTR
                } else {
                    TextOrientation.VERTICAL_RTL
                }
            } else {
                orientations.firstOrNull {
                    it == TextOrientation.HORIZONTAL_LTR || it == TextOrientation.HORIZONTAL_RTL
                } ?: outputOrientation
            }
        } else {
            outputOrientation
        }
        return when (selected) {
            TextOrientation.VERTICAL_RTL ->
                ShapeAwareTextLayout.Orientation.VERTICAL_RIGHT_TO_LEFT
            TextOrientation.VERTICAL_LTR ->
                ShapeAwareTextLayout.Orientation.VERTICAL_LEFT_TO_RIGHT
            else -> ShapeAwareTextLayout.Orientation.HORIZONTAL
        }
    }

    private fun drawHorizontalShapeLayout(
        canvas: Canvas,
        paint: Paint,
        originX: Int,
        originY: Int,
        runs: List<ShapeAwareTextLayout.Run>,
    ) {
        val metrics = paint.fontMetrics
        runs.forEach { run ->
            val centerX = originX + (run.bounds.left + run.bounds.right) / 2f
            val centerY = originY + (run.bounds.top + run.bounds.bottom) / 2f
            val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(run.text, centerX, baseline, paint)
        }
    }

    private fun drawVerticalShapeLayout(
        canvas: Canvas,
        paint: Paint,
        originX: Int,
        originY: Int,
        runs: List<ShapeAwareTextLayout.Run>,
    ) {
        val metrics = paint.fontMetrics
        runs.forEach { run ->
            val characters = graphemeClusters(run.text)
            if (characters.isEmpty()) return@forEach
            val cellHeight = run.bounds.height.toFloat() / characters.size
            val centerX = originX + (run.bounds.left + run.bounds.right) / 2f
            characters.forEachIndexed { index, character ->
                val centerY = originY + run.bounds.top + cellHeight * (index + 0.5f)
                val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
                canvas.drawText(character, centerX, baseline, paint)
            }
        }
    }

    private fun readableShapeTextColor(
        width: Int,
        height: Int,
        pixels: IntArray,
        repairedMask: BooleanArray,
        modelMask: BubbleSegmentationPostprocessor.InstanceMask,
    ): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (localY in 0 until modelMask.height) {
            val y = modelMask.top + localY
            if (y !in 0 until height) continue
            for (localX in 0 until modelMask.width) {
                if (!modelMask.pixels[localY * modelMask.width + localX]) continue
                val x = modelMask.left + localX
                if (x !in 0 until width) continue
                val index = y * width + x
                if (!repairedMask[index]) continue
                val color = pixels[index]
                red += Color.red(color)
                green += Color.green(color)
                blue += Color.blue(color)
                count++
            }
        }
        if (count == 0) return Color.BLACK
        val averageRed = red.toFloat() / count
        val averageGreen = green.toFloat() / count
        val averageBlue = blue.toFloat() / count
        val luminance = (
            averageRed * 0.2126f +
                averageGreen * 0.7152f +
                averageBlue * 0.0722f
        ) / 255f
        return if (luminance >= 0.55f) Color.BLACK else Color.WHITE
    }

    private fun graphemeClusters(text: String): List<String> {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val output = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            output += text.substring(start, end)
            start = end
            end = iterator.next()
        }
        return output
    }

    private fun shapeLayoutFallback(
        modelIndex: Int,
        reason: String,
        orientation: ShapeAwareTextLayout.Orientation,
        textLength: Int,
    ) = ShapeLayoutDecision(
        modelBubbleIndex = modelIndex,
        accepted = false,
        reason = reason,
        orientation = orientation,
        fontSizePx = 0,
        runCount = 0,
        textLength = textLength,
    )

    private fun renderPixels(
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun renderRepairOverlay(
        width: Int,
        height: Int,
        eraseMask: BooleanArray,
        repairResult: MaskedBackgroundRepairer.Result,
    ): Bitmap {
        val output = renderPixels(width, height, repairResult.pixels)
        val rejectedMask = BooleanArray(eraseMask.size) { index ->
            eraseMask[index] && !repairResult.repairedMask[index]
        }
        val canvas = Canvas(output)
        drawTintedMask(
            canvas = canvas,
            width = width,
            height = height,
            mask = repairResult.repairedMask,
            color = Color.argb(105, 14, 165, 233),
        )
        drawTintedMask(
            canvas = canvas,
            width = width,
            height = height,
            mask = rejectedMask,
            color = Color.argb(205, 236, 72, 153),
        )
        return output
    }

    private fun renderOverlay(
        input: Input,
        allBlocks: List<DelayedTextEraseMaskBuilder.ConfirmedBlock>,
        successfulBlockIndices: Set<Int>,
        result: DelayedTextEraseMaskBuilder.Result,
    ): Bitmap {
        val output = Bitmap.createBitmap(
            input.width,
            input.height,
            Bitmap.Config.ARGB_8888,
        ).apply {
            setPixels(
                input.sourceArgb,
                0,
                input.width,
                0,
                0,
                input.width,
                input.height,
            )
        }
        val canvas = Canvas(output)
        drawTintedMask(
            canvas = canvas,
            width = input.width,
            height = input.height,
            mask = result.mask,
            color = Color.argb(190, 34, 197, 94),
        )
        val decisionByBlock = result.decisions.associateBy { it.blockIndex }
        val strokeWidth = (minOf(input.width, input.height) / 420f).coerceAtLeast(2f)
        val textSize = (minOf(input.width, input.height) / 68f).coerceAtLeast(14f)
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
        allBlocks.forEach { block ->
            val successful = block.blockIndex in successfulBlockIndices
            val decision = decisionByBlock[block.blockIndex]
            boxPaint.color = when {
                !successful -> Color.rgb(100, 116, 139)
                decision?.accepted == true -> Color.rgb(34, 197, 94)
                else -> Color.rgb(249, 115, 22)
            }
            val bounds = union(block.sourceBoxes) ?: return@forEach
            canvas.drawRect(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                boxPaint,
            )
            val label = when {
                !successful -> "B${block.blockIndex + 1} translation-failed"
                decision?.accepted == true ->
                    "B${block.blockIndex + 1} mask-ok " +
                        "c${(decision.minimumModelCoverage * 100).toInt()}"
                else -> "B${block.blockIndex + 1} fallback " +
                    (decision?.reason?.name?.lowercase(Locale.US) ?: "no-decision")
            }
            drawLabel(
                canvas = canvas,
                imageWidth = input.width,
                text = label,
                anchorX = bounds.left.toFloat(),
                anchorY = bounds.top.toFloat(),
                textSize = textSize,
                textPaint = labelPaint,
                backgroundPaint = labelBackgroundPaint,
            )
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

    private fun drawTintedMask(
        canvas: Canvas,
        width: Int,
        height: Int,
        mask: BooleanArray,
        color: Int,
    ) {
        val pixels = IntArray(mask.size) { index ->
            if (mask[index]) color else Color.TRANSPARENT
        }
        val tint = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            canvas.drawBitmap(tint, 0f, 0f, null)
        } finally {
            tint.recycle()
        }
    }

    private fun drawLabel(
        canvas: Canvas,
        imageWidth: Int,
        text: String,
        anchorX: Float,
        anchorY: Float,
        textSize: Float,
        textPaint: Paint,
        backgroundPaint: Paint,
    ) {
        val padding = 5f
        val width = textPaint.measureText(text)
        val left = anchorX.coerceIn(0f, (imageWidth - width - padding * 2).coerceAtLeast(0f))
        val baseline = (anchorY - 4f).coerceAtLeast(textSize + padding)
        canvas.drawRect(
            left,
            baseline - textSize - padding,
            (left + width + padding * 2).coerceAtMost(imageWidth.toFloat()),
            baseline + padding,
            backgroundPaint,
        )
        canvas.drawText(text, left + padding, baseline, textPaint)
    }

    private fun union(rects: List<IntRect>): IntRect? {
        if (rects.isEmpty()) return null
        return IntRect(
            left = rects.minOf { it.left },
            top = rects.minOf { it.top },
            right = rects.maxOf { it.right },
            bottom = rects.maxOf { it.bottom },
        )
    }

    private companion object {
        const val NANOS_PER_MICROSECOND: Long = 1_000L
        const val MIN_SHAPE_FONT_PX = 10
        const val MAX_SHAPE_FONT_PX = 96
        const val MAX_FONT_MINOR_AXIS_RATIO = 0.48f
    }

}
