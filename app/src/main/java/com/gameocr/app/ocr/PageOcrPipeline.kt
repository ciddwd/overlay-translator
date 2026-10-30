package com.gameocr.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.gameocr.app.capture.CaptureContentOrientationPolicy
import com.gameocr.app.data.CaptureContentOrientation
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.Settings
import com.gameocr.app.data.needsRawBitmap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns the final OCR frame, so a rejected rerun cannot replace its pixel masks. */
internal data class PageOcrFrame(
    val manager: MangaDelayedMaskDebugSessionManager,
    val imageWidth: Int,
    val imageHeight: Int,
    val coordinateScale: Float,
    val rotationDegrees: Int,
    val engine: OcrEngineKind,
    val readingOrientation: TextOrientation,
) {
    fun claim(blocks: List<TextBlock>): MangaDelayedMaskDebugSessionManager.Batch? = manager.claim(
        imageWidth = (CaptureContentOrientationPolicy.rotatedWidth(imageWidth, imageHeight, rotationDegrees) * coordinateScale).toInt(),
        imageHeight = (CaptureContentOrientationPolicy.rotatedHeight(imageWidth, imageHeight, rotationDegrees) * coordinateScale).toInt(),
        coordinateScale = coordinateScale,
        blocks = CaptureContentOrientationPolicy.mapBlocksToOcr(blocks, imageWidth, imageHeight, rotationDegrees),
    )

    fun mapPatchesToDisplay(patches: List<ShapeAwareBubblePatch>): List<ShapeAwareBubblePatch> =
        CaptureContentOrientationPolicy.mapPatchesToOriginal(patches,
            (imageWidth * coordinateScale).toInt(), (imageHeight * coordinateScale).toInt(), rotationDegrees)
}

internal data class PageOcrResult(
    val rawBlocks: List<TextBlock>,
    val orderedRawBlocks: List<TextBlock>,
    val blocks: List<TextBlock>,
    val orientationHint: OrientationResult?,
    val frame: PageOcrFrame,
)

/** Shared recognition entry for captured frames, file batches and saved-result reconstruction. */
@Singleton
class PageOcrPipeline @Inject constructor(
    private val ocr: RoutingOcrEngine,
    private val paddle: PaddleOcrEngine,
    private val orientation: OrientationCoordinator,
    private val mangaInstaller: MangaOcrModelInstaller,
    private val sessions: ShapeAwareBubbleSessionStore,
) {
    private data class Pass(
        val normalized: Bitmap,
        val processed: Bitmap,
        val blocks: List<TextBlock>,
        val manager: MangaDelayedMaskDebugSessionManager,
        val engine: OcrEngineKind,
        val rotation: Int,
    ) {
        fun close(source: Bitmap) {
            if (processed !== normalized && !processed.isRecycled) processed.recycle()
            if (normalized !== source && !normalized.isRecycled) normalized.recycle()
        }
    }

    internal suspend fun recognize(
        source: Bitmap,
        settings: Settings,
        diagnostic: (String) -> Unit = {},
    ): PageOcrResult = withContext(Dispatchers.Default) {
        val contentHint = if (settings.captureContentOrientation != CaptureContentOrientation.AUTO &&
            ((settings.captureContentOrientation == CaptureContentOrientation.LANDSCAPE) !=
                (source.width >= source.height))) orientation.classifyPreOcr(source) else null
        val rotation = CaptureContentOrientationPolicy.resolveRotationDegrees(settings.captureContentOrientation,
            source.width, source.height, contentHint?.rawAngle ?: 0)
        val normalized = CaptureContentOrientationPolicy.rotateForOcr(source, rotation)
        var current: Pass? = null
        try {
            var effectiveEngine = settings.ocrEngine
            var orientationHint: OrientationResult? = null
            val hasMangaOcr = mangaInstaller.checkInstalled() != null
            val baiduConfigured = settings.baiduOcrApiKey.isNotBlank() && settings.baiduOcrSecretKey.isNotBlank()
            if (settings.textOrientationAutoDetect) {
                val preHint = settings.manualTextOrientation?.let { OrientationResult(it, 1f, 0, "manual") }
                    ?: if (rotation == 0 && contentHint != null) contentHint
                    else if (rotation != 0) OrientationResult(TextOrientation.UNKNOWN, 1f, 0, "content-normalized")
                    else orientation.classifyPreOcr(normalized)
                if (preHint.orientation != TextOrientation.UNKNOWN || preHint.source != "heuristic-bitmap-na") {
                    orientationHint = preHint
                }
                val preEngine = if (effectiveEngine == OcrEngineKind.ML_KIT_AUTO) null else OrientationRouting.resolveEngine(
                    preHint.orientation, settings.sourceLang, effectiveEngine, hasMangaOcr, baiduConfigured)
                if (preEngine != null) effectiveEngine = preEngine
            }
            val first = recognizePass(source, normalized, rotation, effectiveEngine, settings)
            current = first
            diagnostic("first OCR engine=$effectiveEngine image=${first.processed.width}x${first.processed.height} rotation=$rotation blocks=${first.blocks.size}")
            val weakChinese = shouldRerunLowQualityChinesePaddleOcr(settings.sourceLang, effectiveEngine,
                settings.textOrientationAutoDetect, settings.manualTextOrientation != null,
                first.processed.width, first.processed.height, first.blocks.size,
                first.blocks.count { it.boundingBox.height().toFloat() / it.boundingBox.width().coerceAtLeast(1) >= 1.25f },
                first.blocks.sumOf { it.text.count { ch -> !ch.isWhitespace() } })
            val halfTurn = if (rotation != 0 && effectiveEngine == OcrEngineKind.PADDLE_ONNX && first.blocks.isNotEmpty()) {
                recover(diagnostic) { paddle.probeHalfTurn(first.processed, first.blocks, settings.paddleModelVersion) }
            } else null
            if ((settings.textOrientationAutoDetect || halfTurn?.rawAngle == 180) &&
                (first.blocks.isNotEmpty() || weakChinese)) {
                val hint = if (halfTurn != null) {
                    halfTurn.copy(orientation = settings.manualTextOrientation ?: halfTurn.orientation)
                } else if (settings.manualTextOrientation != null) {
                    orientationHint ?: OrientationResult(settings.manualTextOrientation, 1f, 0, "manual")
                } else {
                    val layout = orientationHintFromLayout(first.blocks)
                    val refined = if (first.blocks.isEmpty()) OrientationResult(TextOrientation.UNKNOWN, 0f, 0, "post-ocr-empty")
                        else orientation.classifyPostOcr(first.processed, first.blocks,
                            orientationHint ?: OrientationResult(TextOrientation.UNKNOWN, 0f, 0, "post-ocr"))
                    val prior = orientationHint
                    when {
                        refined.orientation == TextOrientation.UNKNOWN && layout != null -> layout
                        refined.orientation == TextOrientation.UNKNOWN && prior != null &&
                            prior.orientation != TextOrientation.UNKNOWN -> prior
                        else -> refined
                    }
                }
                orientationHint = hint
                var corrected = false
                val upsideDown = hint.rawAngle == 180 && (hint.source == PaddleTextLineOrientationClassifier.SOURCE ||
                    hint.source == OcrHalfTurnPolicy.SOURCE)
                val reliable = effectiveEngine == OcrEngineKind.PADDLE_ONNX && OcrHalfTurnRerunPolicy.firstPassIsReliable(first.blocks)
                if (upsideDown && !reliable) {
                    val turn = OcrHalfTurnPolicy.correctedRotation(rotation)
                    val rerun = recover(diagnostic) { recognizePass(source,
                        CaptureContentOrientationPolicy.rotateForOcr(source, turn), turn, effectiveEngine, settings) }
                    if (rerun != null) {
                        corrected = OcrHalfTurnRerunPolicy.prefersRerun(first.blocks, rerun.blocks)
                        if (corrected) { first.close(source); current = rerun } else rerun.close(source)
                    }
                    diagnostic("180-degree OCR rerun accepted=$corrected")
                }
                if (!corrected) {
                    val newEngine = if (!settings.textOrientationAutoDetect || effectiveEngine == OcrEngineKind.ML_KIT_AUTO) null
                        else OrientationRouting.resolveEngine(hint.orientation, settings.sourceLang,
                            effectiveEngine, hasMangaOcr, baiduConfigured)
                    val fallback = if (newEngine == null && weakChinese) OrientationResult(TextOrientation.VERTICAL_RTL,
                        0.51f, 0, "low-quality-zh-paddle") else null
                    val rerunEngine = newEngine ?: fallback?.let {
                        OrientationRouting.resolveEngine(it.orientation, settings.sourceLang, effectiveEngine, hasMangaOcr, baiduConfigured)
                    }
                    if (rerunEngine != null && rerunEngine != effectiveEngine) {
                        val rerun = recover(diagnostic) { recognizePass(source,
                            CaptureContentOrientationPolicy.rotateForOcr(source, rotation), rotation, rerunEngine, settings) }
                        if (rerun != null) {
                            first.close(source)
                            current = rerun
                            if (fallback != null) orientationHint = orientationHintFromLayout(rerun.blocks) ?: fallback
                        }
                    }
                }
            }
            val accepted = requireNotNull(current)
            val reading = resolveTextBlockReadingOrientation(accepted.blocks, orientationHint?.orientation)
            val ordered = sortTextBlocksForReading(accepted.blocks, reading)
            val scale = if (settings.preprocess.upscale2x) 2f else 1f
            val scaled = if (scale == 2f) ordered.map { block ->
                fun Rect.half() = Rect(left / 2, top / 2, right / 2, bottom / 2)
                block.copy(boundingBox = block.boundingBox.half(), sourceBoxes = block.sourceBoxes.map { it.half() })
            } else ordered
            val blocks = CaptureContentOrientationPolicy.mapBlocksToOriginal(scaled, source.width, source.height, accepted.rotation)
            diagnostic("ocr final engine=${accepted.engine} orientation=$reading rotation=${accepted.rotation} blocks=${blocks.size}")
            PageOcrResult(accepted.blocks, ordered, blocks, orientationHint,
                PageOcrFrame(accepted.manager, source.width, source.height, scale, accepted.rotation, accepted.engine, reading))
        } finally {
            current?.close(source)
            if (current == null && normalized !== source && !normalized.isRecycled) normalized.recycle()
        }
    }

    private suspend fun recognizePass(source: Bitmap, normalized: Bitmap, rotation: Int,
        engine: OcrEngineKind, settings: Settings): Pass {
        var processed: Bitmap? = null
        try {
            val options = if (engine.needsRawBitmap) settings.preprocess.copy(invert = false, binarize = false)
                else settings.preprocess
            val input = BitmapPreprocessor.apply(normalized, options)
            processed = input
            return sessions.withFrame { manager ->
                Pass(normalized, input, ocr.recognizeWithSettings(input, engine, settings), manager, engine, rotation)
            }
        } catch (error: Throwable) {
            if (processed !== normalized) processed?.takeIf { !it.isRecycled }?.recycle()
            if (normalized !== source && !normalized.isRecycled) normalized.recycle()
            throw error
        }
    }

    private suspend fun <T> recover(diagnostic: (String) -> Unit, block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        diagnostic("OCR refinement failed; keep first pass: ${error.javaClass.simpleName}: ${error.message}")
        null
    }
}
