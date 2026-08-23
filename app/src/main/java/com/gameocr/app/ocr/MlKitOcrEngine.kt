package com.gameocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import com.gameocr.app.BuildConfig
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.Settings
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google ML Kit on-device Text Recognition v2. */
@Singleton
class MlKitOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : OcrEngine {

    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japanese: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val korean: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> =
        recognizeInternal(bitmap, kind)

    override suspend fun recognize(
        bitmap: Bitmap,
        kind: OcrEngineKind,
        settings: Settings,
    ): List<TextBlock> = recognizeInternal(bitmap, kind)

    private suspend fun recognizeInternal(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        return when (kind) {
            OcrEngineKind.ML_KIT_LATIN -> runRecognizer(latin, bitmap, "latin", "full")
            OcrEngineKind.ML_KIT_JAPANESE -> recognizeJapanese(bitmap)
            OcrEngineKind.ML_KIT_CHINESE -> runRecognizer(chinese, bitmap, "zh", "full")
            OcrEngineKind.ML_KIT_KOREAN -> runRecognizer(korean, bitmap, "ko", "full")
            OcrEngineKind.ML_KIT_AUTO -> autoRecognize(bitmap)
            OcrEngineKind.BAIDU,
            OcrEngineKind.TENCENT,
            OcrEngineKind.YOUDAO,
            OcrEngineKind.PADDLE_AI_STUDIO,
            OcrEngineKind.UMI_OCR,
            OcrEngineKind.LUNA_OCR,
            OcrEngineKind.PADDLE_ONNX,
            OcrEngineKind.MANGA_OCR_JA -> autoRecognize(bitmap)
        }
    }

    private suspend fun recognizeJapanese(bitmap: Bitmap): List<TextBlock> {
        val plan = MlKitJapaneseInputPolicy.plan(bitmap.width, bitmap.height)
        val prepared = if (plan.changed) {
            Bitmap.createScaledBitmap(bitmap, plan.outputWidth, plan.outputHeight, true)
        } else {
            bitmap
        }
        Timber.i(
            "ML Kit Japanese input input=%dx%d processed=%dx%d scaleX=%.3f scaleY=%.3f",
            plan.inputWidth,
            plan.inputHeight,
            plan.outputWidth,
            plan.outputHeight,
            plan.scaleX,
            plan.scaleY,
        )
        return try {
            val glyphs = mutableListOf<MlKitJapaneseGlyph>()
            val firstPass = runRecognizer(
                recognizer = japanese,
                bitmap = prepared,
                lang = "ja",
                origin = "full",
                japaneseGlyphCollector = glyphs,
            )
            val visibleResult = if (BuildConfig.DEBUG) {
                runJapaneseColumnShadowRecognition(
                    bitmap = prepared,
                    firstPass = firstPass,
                    glyphs = glyphs,
                )
            } else {
                firstPass
            }
            if (plan.changed) {
                visibleResult.map { block -> block.mapRects(plan::mapRectToInput) }
            } else {
                visibleResult
            }
        } finally {
            if (prepared !== bitmap && !prepared.isRecycled) prepared.recycle()
        }
    }

    private suspend fun autoRecognize(bitmap: Bitmap): List<TextBlock> {
        val ko = runRecognizer(korean, bitmap, "ko", "auto")
        if (ko.any { containsHangul(it.text) }) return ko
        val ja = runRecognizer(japanese, bitmap, "ja", "auto")
        if (ja.any { containsKana(it.text) }) return ja
        val latinResult = runRecognizer(latin, bitmap, "latin", "auto")
        if (latinResult.isNotEmpty()) return latinResult
        return runRecognizer(chinese, bitmap, "zh", "auto")
    }

    private suspend fun runRecognizer(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        lang: String,
        origin: String,
        japaneseGlyphCollector: MutableList<MlKitJapaneseGlyph>? = null,
    ): List<TextBlock> = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        val startedAt = SystemClock.elapsedRealtime()
        recognizer.process(input)
            .addOnSuccessListener { result ->
                var lineCount = 0
                var outputIndex = 0
                var elementCount = 0
                var symbolCount = 0
                val blocks = result.textBlocks.flatMapIndexed { blockIndex, block ->
                    elementCount += block.lines.sumOf { it.elements.size }
                    symbolCount += block.lines.sumOf { line -> line.elements.sumOf { it.symbols.size } }
                    val lines = block.lines.mapNotNull { line ->
                        val bbox = line.boundingBox ?: return@mapNotNull null
                        val text = line.text.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val sourceLineIndex = outputIndex++
                        val language = MlKitTextResultPolicy.effectiveLanguage(
                            detectedLineLanguage = line.recognizedLanguage,
                            detectedBlockLanguage = block.recognizedLanguage,
                            configuredLanguage = lang,
                        )
                        val symbolBounds = line.elements.flatMap { element ->
                            element.symbols.mapNotNull { symbol -> symbol.boundingBox?.let(::Rect) }
                        }
                        japaneseGlyphCollector?.addAll(
                            symbolBounds.map { symbolBoundsItem ->
                                MlKitJapaneseGlyph(
                                    bounds = symbolBoundsItem.toGeometryRect(),
                                    sourceLineIndex = sourceLineIndex,
                                )
                            },
                        )
                        val symbolCenters = symbolBounds.map { symbolBoundsItem ->
                            symbolBoundsItem.centerPoint()
                        }
                        val elementCenters = line.elements.mapNotNull { element ->
                            element.boundingBox?.centerPoint()
                        }
                        val geometryPoints = symbolCenters.takeIf { it.size >= 2 } ?: elementCenters
                        lineCount++
                        TextBlock(
                            text = text,
                            boundingBox = Rect(bbox),
                            confidence = MlKitTextResultPolicy.confidence(
                                lineConfidence = line.confidence,
                                elementConfidences = line.elements.map { it.confidence },
                            ),
                            recognizedLanguage = language,
                            layoutOrientation = MlKitTextResultPolicy.layoutOrientation(
                                width = bbox.width(),
                                height = bbox.height(),
                                angleDegrees = line.angle,
                                languageTag = language,
                                geometryPoints = geometryPoints,
                            ),
                            sourceBoxes = listOf(Rect(bbox)),
                            parentRegionId = blockIndex,
                            regionGranularity = TextRegionGranularity.LINE,
                        )
                    }
                    if (lines.isNotEmpty()) {
                        lines
                    } else {
                        val bbox = block.boundingBox
                        val text = block.text.takeIf { it.isNotBlank() }
                        if (bbox == null || text == null) {
                            emptyList()
                        } else {
                            val language = MlKitTextResultPolicy.effectiveLanguage(
                                detectedLineLanguage = null,
                                detectedBlockLanguage = block.recognizedLanguage,
                                configuredLanguage = lang,
                            )
                            listOf(
                                TextBlock(
                                    text = text,
                                    boundingBox = Rect(bbox),
                                    confidence = 0f,
                                    recognizedLanguage = language,
                                    layoutOrientation = MlKitTextResultPolicy.layoutOrientation(
                                        width = bbox.width(),
                                        height = bbox.height(),
                                        angleDegrees = 0f,
                                        languageTag = language,
                                    ),
                                    sourceBoxes = listOf(Rect(bbox)),
                                    parentRegionId = blockIndex,
                                    regionGranularity = TextRegionGranularity.PARAGRAPH,
                                )
                            ).also { outputIndex++ }
                        }
                    }
                }
                val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                val confidenceValues = blocks.map { it.confidence }
                Timber.i(
                    "ML Kit recognize done lang=%s origin=%s image=%dx%d elapsed=%dms blocks=%d lines=%d elements=%d symbols=%d output=%d confidenceMin=%.3f confidenceMean=%.3f confidenceMax=%.3f",
                    lang,
                    origin,
                    input.width,
                    input.height,
                    elapsedMs,
                    result.textBlocks.size,
                    lineCount,
                    elementCount,
                    symbolCount,
                    blocks.size,
                    confidenceValues.minOrNull() ?: 0f,
                    confidenceValues.average().takeIf { it.isFinite() } ?: 0.0,
                    confidenceValues.maxOrNull() ?: 0f,
                )
                if (cont.isActive) cont.resume(blocks)
            }
            .addOnFailureListener { error ->
                Timber.w(
                    error,
                    "ML Kit recognize failed lang=%s origin=%s image=%dx%d elapsed=%dms",
                    lang,
                    origin,
                    input.width,
                    input.height,
                    SystemClock.elapsedRealtime() - startedAt,
                )
                if (cont.isActive) cont.resumeWithException(error)
            }
    }

    /**
     * Debug-only repair path for pages whose symbol geometry strongly disagrees with the
     * first-pass line orientation. Every candidate gets one isolated upright pass; rotations are
     * attempted only when the structural output policy rejects that result.
     */
    private suspend fun runJapaneseColumnShadowRecognition(
        bitmap: Bitmap,
        firstPass: List<TextBlock>,
        glyphs: List<MlKitJapaneseGlyph>,
    ): List<TextBlock> {
        val columnPlan = MlKitJapaneseColumnPolicy.plan(
            glyphs = glyphs,
            lineOrientations = firstPass.map(TextBlock::layoutOrientation),
        )
        val regions = MlKitJapaneseRegionPolicy.expand(
            columns = columnPlan.columns,
            lineBounds = firstPass.map { block -> block.boundingBox.toGeometryRect() },
        )
        Timber.i(
            "ML Kit Japanese shadow plan glyphs=%d columns=%d regions=%d verticalCoverage=%.3f horizontalCoverage=%.3f verticalScore=%.3f horizontalScore=%.3f horizontalLineRatio=%.3f run=%s reason=%s firstPass=%s",
            glyphs.size,
            columnPlan.columns.size,
            regions.size,
            columnPlan.verticalCoverage,
            columnPlan.horizontalCoverage,
            columnPlan.verticalScore,
            columnPlan.horizontalScore,
            columnPlan.horizontalLineRatio,
            columnPlan.shouldRunShadowRecognition,
            columnPlan.reason,
            firstPass.debugText(),
        )
        if (!columnPlan.shouldRunShadowRecognition) return firstPass

        val outputDirectory = File(
            context.externalCacheDir ?: context.cacheDir,
            "mlkit_japanese_shadow",
        ).apply { mkdirs() }
        outputDirectory.listFiles()
            ?.filter { file -> file.isFile && file.extension.equals("png", ignoreCase = true) }
            ?.forEach { file -> runCatching { file.delete() } }

        val repairs = mutableListOf<MlKitJapaneseShadowRepair>()
        regions.forEachIndexed { index, candidate ->
            val repair = runCatching {
                runJapaneseColumnShadowCandidate(
                    bitmap = bitmap,
                    firstPass = firstPass,
                    candidate = candidate,
                    candidateIndex = index,
                    outputDirectory = outputDirectory,
                )
            }.onFailure { error ->
                Timber.w(error, "ML Kit Japanese shadow region failed index=%d", index)
            }.getOrNull()
            if (repair != null) repairs += repair
        }
        val repaired = MlKitJapaneseShadowRepairPolicy.apply(firstPass, repairs)
        Timber.i(
            "ML Kit Japanese shadow complete visibleResult=accepted-shadow-repairs repairedRegions=%d replacedLines=%d outputDir=%s",
            repairs.size,
            repairs.sumOf { repair -> repair.sourceLineIndices.size },
            outputDirectory.absolutePath,
        )
        return repaired
    }

    private suspend fun runJapaneseColumnShadowCandidate(
        bitmap: Bitmap,
        firstPass: List<TextBlock>,
        candidate: MlKitJapaneseColumnCandidate,
        candidateIndex: Int,
        outputDirectory: File,
    ): MlKitJapaneseShadowRepair? {
        val cropPlan = MlKitJapaneseColumnCropPolicy.plan(
            candidate = candidate,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
        )
        val source = cropPlan.sourceBounds
        val cropped = Bitmap.createBitmap(bitmap, source.left, source.top, source.width, source.height)
        val normalized = if (cropPlan.changed) {
            Bitmap.createScaledBitmap(cropped, cropPlan.outputWidth, cropPlan.outputHeight, true)
        } else {
            cropped
        }
        if (normalized !== cropped && cropped !== bitmap && !cropped.isRecycled) cropped.recycle()

        try {
            val variants = mutableListOf<MlKitJapaneseShadowVariant>()
            variants += recognizeJapaneseShadowVariant(
                normalized = normalized,
                rotation = 0,
                candidate = candidate,
                candidateIndex = candidateIndex,
                source = source,
                outputDirectory = outputDirectory,
            )
            var selection = MlKitJapaneseShadowSelectionPolicy.select(
                variants = variants,
                evidenceGlyphCount = candidate.memberIndices.size,
                cropWidth = normalized.width,
                cropHeight = normalized.height,
            )
            while (true) {
                val fallbackRotation = when (MlKitJapaneseRotationFallbackPolicy.next(selection)) {
                    MlKitJapaneseRotationFallback.CLOCKWISE -> 90
                    MlKitJapaneseRotationFallback.COUNTERCLOCKWISE -> -90
                    MlKitJapaneseRotationFallback.STOP -> break
                }
                variants += recognizeJapaneseShadowVariant(
                    normalized = normalized,
                    rotation = fallbackRotation,
                    candidate = candidate,
                    candidateIndex = candidateIndex,
                    source = source,
                    outputDirectory = outputDirectory,
                )
                selection = MlKitJapaneseShadowSelectionPolicy.select(
                    variants = variants,
                    evidenceGlyphCount = candidate.memberIndices.size,
                    cropWidth = normalized.width,
                    cropHeight = normalized.height,
                )
            }

            Timber.i(
                "ML Kit Japanese shadow selection index=%d attempts=%d selectedRotation=%s selectedText=%s evaluations=%s",
                candidateIndex,
                variants.size,
                selection.selected?.rotationDegrees?.toString() ?: "none",
                selection.selected?.retainedBlocks?.debugText().orEmpty(),
                selection.evaluations.joinToString(separator = " | ") { evaluation ->
                    "rotation=${evaluation.rotationDegrees},accepted=${evaluation.accepted},score=${"%.3f".format(evaluation.score)},completeness=${"%.3f".format(evaluation.completeness)},vertical=${"%.3f".format(evaluation.verticalCharacterRatio)},confidence=${"%.3f".format(evaluation.confidence)},kept=${evaluation.retainedBlocks.debugText()},nonVertical=${evaluation.nonVerticalBlocks.debugText()},annotation=${evaluation.annotationBlocks.debugText()},edge=${evaluation.edgeFragmentBlocks.debugText()}"
                },
            )
            val selected = selection.selected ?: return null
            val sourceLineIndices = candidate.sourceLineIndices
                .filter(firstPass.indices::contains)
                .distinct()
                .sorted()
            if (sourceLineIndices.isEmpty() || selected.retainedBlocks.isEmpty()) return null
            val selectedSourceBlocks = sourceLineIndices.map(firstPass::get)
            val normalizedWidth = normalized.width
            val normalizedHeight = normalized.height
            val mappedSourceBoxes = selected.retainedBlocks.map { block ->
                val mapped = MlKitJapaneseShadowRepairPolicy.mapNormalizedRectToSource(
                    rect = block.boundingBox.toGeometryRect(),
                    sourceBounds = source,
                    normalizedWidth = normalizedWidth,
                    normalizedHeight = normalizedHeight,
                )
                mapped.toAndroidRect()
            }
            val commonParentRegionId = selectedSourceBlocks.map(TextBlock::parentRegionId)
                .distinct()
                .singleOrNull()
            val commonBubbleGroupId = selectedSourceBlocks.map(TextBlock::bubbleGroupId)
                .distinct()
                .singleOrNull()
            return MlKitJapaneseShadowRepair(
                sourceLineIndices = sourceLineIndices,
                replacement = TextBlock(
                    text = selected.retainedBlocks.joinToString(separator = "") { block ->
                        block.text.replace("\r", "").replace("\n", "")
                    },
                    boundingBox = candidate.bounds.toAndroidRect(),
                    confidence = selected.confidence,
                    recognizedLanguage = "ja",
                    layoutOrientation = TextOrientation.VERTICAL_RTL,
                    sourceBoxes = mappedSourceBoxes,
                    bubbleGroupId = commonBubbleGroupId,
                    parentRegionId = commonParentRegionId,
                    regionGranularity = TextRegionGranularity.PARAGRAPH,
                ),
                score = selected.score,
            )
        } finally {
            if (normalized !== bitmap && !normalized.isRecycled) normalized.recycle()
        }
    }

    private suspend fun recognizeJapaneseShadowVariant(
        normalized: Bitmap,
        rotation: Int,
        candidate: MlKitJapaneseColumnCandidate,
        candidateIndex: Int,
        source: MlKitGeometryRect,
        outputDirectory: File,
    ): MlKitJapaneseShadowVariant {
        val variant = normalized.rotated(rotation)
        val suffix = when (rotation) {
            90 -> "cw"
            -90 -> "ccw"
            else -> "upright"
        }
        val outputFile = File(
            outputDirectory,
            "region_${(candidateIndex + 1).toString().padStart(2, '0')}_$suffix.png",
        )
        try {
            outputFile.outputStream().buffered().use { stream ->
                check(variant.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            val shadow = runRecognizer(
                recognizer = japanese,
                bitmap = variant,
                lang = "ja",
                origin = "shadow-region-${candidateIndex + 1}-$suffix",
            )
            val orderedShadow = MlKitJapaneseShadowReadingPolicy.orderVerticalRtl(
                blocks = shadow,
                uprightWidth = normalized.width,
                uprightHeight = normalized.height,
                rotationDegrees = rotation,
            )
            val result = MlKitJapaneseShadowVariant(
                rotationDegrees = rotation,
                blocks = orderedShadow,
            )
            Timber.i(
                "ML Kit Japanese shadow result index=%d rotation=%d members=%d sourceLines=%d source=[%d,%d,%d,%d] image=%dx%d file=%s confidenceMean=%.3f rawText=%s orderedText=%s orderedGeometry=%s",
                candidateIndex,
                rotation,
                candidate.memberIndices.size,
                candidate.sourceLineIndices.size,
                source.left,
                source.top,
                source.right,
                source.bottom,
                variant.width,
                variant.height,
                outputFile.absolutePath,
                shadow.map(TextBlock::confidence).average().takeIf { it.isFinite() } ?: 0.0,
                shadow.debugText(),
                orderedShadow.debugText(),
                orderedShadow.debugGeometry(),
            )
            return result
        } finally {
            if (variant !== normalized && !variant.isRecycled) variant.recycle()
        }
    }

    private fun MlKitGeometryRect.toAndroidRect(): Rect = Rect().apply {
        left = this@toAndroidRect.left
        top = this@toAndroidRect.top
        right = this@toAndroidRect.right
        bottom = this@toAndroidRect.bottom
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun List<TextBlock>.debugText(): String = joinToString(separator = " | ") { block ->
        block.text.replace('\n', ' ').replace('\r', ' ')
    }.take(1_200)

    private fun List<TextBlock>.debugGeometry(): String = joinToString(separator = " | ") { block ->
        val box = block.boundingBox
        "${block.text.replace('\n', ' ').replace('\r', ' ')}@[${box.left},${box.top},${box.right},${box.bottom}]"
    }.take(2_000)

    private fun Rect.centerPoint(): MlKitGeometryPoint = MlKitGeometryPoint(
        x = exactCenterX(),
        y = exactCenterY(),
    )

    private fun Rect.toGeometryRect(): MlKitGeometryRect = MlKitGeometryRect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )

    private fun TextBlock.mapRects(mapper: (Rect) -> Rect): TextBlock = copy(
        boundingBox = mapper(boundingBox),
        sourceBoxes = sourceBoxes.map(mapper),
    )

    private fun containsKana(text: String): Boolean = text.any { character ->
        character in '\u3040'..'\u309f' ||
            character in '\u30a0'..'\u30ff' ||
            character in '\uff65'..'\uff9f'
    }

    private fun containsHangul(text: String): Boolean = text.any { character ->
        character in '\uac00'..'\ud7af' ||
            character in '\u1100'..'\u11ff' ||
            character in '\u3130'..'\u318f'
    }

    override fun close() {
        runCatching { latin.close() }
        runCatching { japanese.close() }
        runCatching { chinese.close() }
        runCatching { korean.close() }
    }
}
