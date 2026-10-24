package com.gameocr.app.ocr

import android.graphics.Bitmap
import com.gameocr.app.data.AutoOcrRoute
import com.gameocr.app.data.AutoOcrRoutingPolicy
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.PreprocessOptions
import com.gameocr.app.data.Settings
import com.gameocr.app.data.needsRawBitmap
import com.gameocr.app.download.ModelReadinessChecker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import com.gameocr.app.util.VerticalDiagnosticLog

internal data class AutomaticOcrResult(val blocks: List<TextBlock>, val manga: Boolean = false)

/**
 * Auto picks its engine only after OCR has started, so callers pass the original image (see
 * [com.gameocr.app.data.needsRawBitmap]) and the user's invert/binarize are applied here per
 * candidate. Engines that need original detail (Japanese, Manga OCR) keep the untouched image.
 */
internal object AutoOcrPreprocessPolicy {
    /** Only tonal steps; upscaling already happened in the caller and changes box coordinates. */
    fun tonalOptions(preprocess: PreprocessOptions): PreprocessOptions? =
        PreprocessOptions(invert = preprocess.invert, binarize = preprocess.binarize).takeIf { it.anyEnabled() }

    fun usesTonal(kind: OcrEngineKind): Boolean = !kind.needsRawBitmap
}

@Singleton
class AutomaticOcrRecognizer @Inject constructor(
    private val mlKit: MlKitOcrEngine,
    private val identifier: OcrTextLanguageIdentifier,
    private val readiness: ModelReadinessChecker,
    private val optionalModule: MlKitOcrModuleManager,
) {
    internal suspend fun recognize(
        bitmap: Bitmap,
        settings: Settings,
        recognize: suspend (Bitmap, OcrEngineKind, Settings) -> List<TextBlock>,
    ): AutomaticOcrResult {
        // Per-invocation caches only: concurrent gallery/screen jobs and page turns cannot share a language guess.
        val ready = mutableMapOf<AutoOcrRoute, Boolean>()
        val devanagariReady = optionalModule.available()
        suspend fun route(language: String): AutoOcrRoute? = withContext(Dispatchers.IO) {
            AutoOcrRoutingPolicy.resolve(settings, language) { choice ->
                ready.getOrPut(choice) {
                    when (choice.engine) {
                        OcrEngineKind.PADDLE_ONNX -> readiness.paddle(choice.paddleVersion).ready
                        OcrEngineKind.MANGA_OCR_JA -> readiness.mangaOcr().ready
                        OcrEngineKind.ML_KIT_DEVANAGARI -> devanagariReady
                        else -> true
                    }
                }
            }
        }
        // Translation source language must not bypass automatic OCR language detection.
        val languageCache = mutableMapOf<String, String?>()
        suspend fun tag(blocks: List<TextBlock>): List<TextBlock> = blocks.map { block ->
            // Cache is bounded to this frame, with bounded samples. Never retain screenshot text globally.
            val sample = block.text.take(1000)
            val language = when {
                languageCache.containsKey(sample) -> languageCache[sample]
                languageCache.size >= 128 -> null
                else -> identifier.identify(sample).also { languageCache[sample] = it }
            }
            block.copy(recognizedLanguage = language)
        }
        fun evidence(blocks: List<TextBlock>) = blocks.map {
            AutoOcrEvidence(it.text, it.recognizedLanguage, it.confidence)
        }

        val tonal = AutoOcrPreprocessPolicy.tonalOptions(settings.preprocess)
        var toned: Bitmap? = null
        fun inputFor(kind: OcrEngineKind): Bitmap =
            if (tonal == null || !AutoOcrPreprocessPolicy.usesTonal(kind)) bitmap
            else toned ?: BitmapPreprocessor.apply(bitmap, tonal).also { toned = it }
        try {
            // Compare every script candidate: language ID cannot validate glyphs that OCR misread.
            val passes = linkedMapOf<OcrEngineKind, List<TextBlock>>()
            for (kind in listOf(OcrEngineKind.ML_KIT_JAPANESE, OcrEngineKind.ML_KIT_KOREAN,
                OcrEngineKind.ML_KIT_CHINESE, OcrEngineKind.ML_KIT_LATIN) +
                if (devanagariReady) listOf(OcrEngineKind.ML_KIT_DEVANAGARI) else emptyList()) {
                val blocks = try {
                    tag(mlKit.recognize(inputFor(kind), kind, settings))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (kind != OcrEngineKind.ML_KIT_DEVANAGARI) throw error
                    Timber.w(error, "Optional OCR module failed; keep bundled OCR results")
                    continue
                }
                passes[kind] = blocks
                Timber.tag("AutoOCR").d("candidate=%s blocks=%d score=%.3f languages=%s", kind,
                    blocks.size, AutoOcrLanguagePolicy.score(evidence(blocks)),
                    blocks.map { it.recognizedLanguage }.distinct())
            }
            val best = selectAutoOcrRegions(passes.values.flatten(),
                { AutoOcrEvidence(it.text, it.recognizedLanguage, it.confidence) }, ::overlaps)
            best.filter { winner -> passes.values.flatten().any { candidate ->
                overlaps(winner, candidate) && candidate.text.any { it in '\uac00'..'\ud7af' }
            } }.take(24).forEach { winner ->
                passes.forEach { (kind, candidates) ->
                    candidates.filter { overlaps(winner, it) }.take(8).forEach { candidate ->
                        VerticalDiagnosticLog.i("auto OCR candidate engine=$kind box=${candidate.boundingBox} " +
                            "confidence=${candidate.confidence} language=${candidate.recognizedLanguage} " +
                            "score=${AutoOcrLanguagePolicy.score(evidence(listOf(candidate)))} " +
                            "selected=${candidate === winner} text=${VerticalDiagnosticLog.text(candidate.text.take(80))}")
                    }
                }
            }
            if (best.isEmpty()) return AutomaticOcrResult(best)

            val groups = best.filter { it.recognizedLanguage != null }.groupBy { it.recognizedLanguage!! }
            var result = best
            var usedManga = false
            // One refinement per detected language, never recursive Auto calls or an unbounded retry queue.
            for ((language) in groups.entries.sortedByDescending { it.value.sumOf { b -> b.text.length } }.take(4)) {
                val selected = route(language) ?: continue
                val effective = AutoOcrRoutingPolicy.settingsFor(settings, language, selected)
                val refined = try {
                    passes[selected.engine] ?: tag(recognize(inputFor(selected.engine), selected.engine, effective))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.tag("AutoOCR").w(error, "Refinement failed language=%s engine=%s; keep bootstrap", language, selected.engine)
                    continue
                }
                val usable = selectAutoOcrRegions(refined.filter { candidate ->
                    AutoOcrLanguagePolicy.canRefine(
                        evidence(result.filter { overlaps(candidate, it) }),
                        AutoOcrEvidence(candidate.text, candidate.recognizedLanguage, candidate.confidence),
                        language,
                    )
                }, { AutoOcrEvidence(it.text, it.recognizedLanguage, it.confidence) }, ::overlaps)
                val replaced = replaceAutoOcrRegions(result, usable, language, { it.recognizedLanguage }, ::overlaps)
                if (replaced != result) {
                    result = replaced
                    usedManga = usedManga || selected.engine == OcrEngineKind.MANGA_OCR_JA
                }
            }
            Timber.tag("AutoOCR").i("bootstrap=regions passes=%d languages=%s blocks=%d", passes.size, groups.keys, result.size)
            // Parent IDs are local to each OCR pass and cannot identify a shared parent after mixing passes.
            return AutomaticOcrResult(result.map { it.copy(parentRegionId = null) }
                .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left })), usedManga)
        } finally {
            // OCR passes are complete here; the toned copy is owned by this call.
            toned?.takeIf { it !== bitmap }?.recycle()
        }
    }

    private fun overlaps(a: TextBlock, b: TextBlock): Boolean {
        val x = (minOf(a.boundingBox.right, b.boundingBox.right) - maxOf(a.boundingBox.left, b.boundingBox.left)).coerceAtLeast(0)
        val y = (minOf(a.boundingBox.bottom, b.boundingBox.bottom) - maxOf(a.boundingBox.top, b.boundingBox.top)).coerceAtLeast(0)
        val area = minOf(a.boundingBox.width().toLong() * a.boundingBox.height(), b.boundingBox.width().toLong() * b.boundingBox.height())
        return area > 0 && x.toLong() * y >= area * 0.5
    }
}
