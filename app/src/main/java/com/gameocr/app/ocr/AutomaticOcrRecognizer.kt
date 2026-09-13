package com.gameocr.app.ocr

import android.graphics.Bitmap
import com.gameocr.app.data.AutoOcrRoute
import com.gameocr.app.data.AutoOcrRoutingPolicy
import com.gameocr.app.data.AutoOcrSettings
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.Settings
import com.gameocr.app.download.ModelReadinessChecker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

internal data class AutomaticOcrResult(val blocks: List<TextBlock>, val manga: Boolean = false)

@Singleton
class AutomaticOcrRecognizer @Inject constructor(
    private val mlKit: MlKitOcrEngine,
    private val identifier: OcrTextLanguageIdentifier,
    private val readiness: ModelReadinessChecker,
) {
    internal suspend fun recognize(
        bitmap: Bitmap,
        settings: Settings,
        recognize: suspend (Bitmap, OcrEngineKind, Settings) -> List<TextBlock>,
    ): AutomaticOcrResult {
        // Per-invocation caches only: concurrent gallery/screen jobs and page turns cannot share a language guess.
        val ready = mutableMapOf<AutoOcrRoute, Boolean>()
        suspend fun route(language: String): AutoOcrRoute? = withContext(Dispatchers.IO) {
            AutoOcrRoutingPolicy.resolve(settings, language) { choice ->
                ready.getOrPut(choice) {
                    when (choice.engine) {
                        OcrEngineKind.PADDLE_ONNX -> readiness.paddle(choice.paddleVersion).ready
                        OcrEngineKind.MANGA_OCR_JA -> readiness.mangaOcr().ready
                        else -> true
                    }
                }
            }
        }
        val source = AutoOcrSettings.languageKey(settings.sourceLang)
        if (source.isNotBlank() && source != "auto") {
            val selected = route(source) ?: return AutomaticOcrResult(emptyList())
            val effective = AutoOcrRoutingPolicy.settingsFor(settings, source, selected)
            Timber.tag("AutoOCR").i("source=%s engine=%s model=%s", source, selected.engine, selected.paddleVersion)
            return AutomaticOcrResult(recognize(bitmap, selected.engine, effective), selected.engine == OcrEngineKind.MANGA_OCR_JA)
        }

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

        // A bounded, confidence-driven bootstrap. A stray "OK" cannot short-circuit CJK recognition.
        val passes = linkedMapOf<OcrEngineKind, List<TextBlock>>()
        var bestKind = OcrEngineKind.ML_KIT_JAPANESE
        var best = emptyList<TextBlock>()
        for (kind in listOf(OcrEngineKind.ML_KIT_JAPANESE, OcrEngineKind.ML_KIT_KOREAN,
            OcrEngineKind.ML_KIT_CHINESE, OcrEngineKind.ML_KIT_LATIN)) {
            val blocks = tag(mlKit.recognize(bitmap, kind, settings))
            passes[kind] = blocks
            if (AutoOcrLanguagePolicy.score(evidence(blocks)) > AutoOcrLanguagePolicy.score(evidence(best))) {
                best = blocks
                bestKind = kind
            }
            if (AutoOcrLanguagePolicy.dominant(evidence(blocks)) != null) break
        }
        if (best.isEmpty()) return AutomaticOcrResult(best)

        val groups = best.filter { it.recognizedLanguage != null }.groupBy { it.recognizedLanguage!! }
        var result = best
        var usedManga = false
        // One refinement per detected language, never recursive Auto calls or an unbounded retry queue.
        for ((language) in groups.entries.sortedByDescending { it.value.sumOf { b -> b.text.length } }.take(4)) {
            val selected = route(language) ?: continue
            if (selected.engine == bestKind) continue
            val effective = AutoOcrRoutingPolicy.settingsFor(settings, language, selected)
            val refined = try {
                passes[selected.engine] ?: tag(recognize(bitmap, selected.engine, effective))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.tag("AutoOCR").w(error, "Refinement failed language=%s engine=%s; keep bootstrap", language, selected.engine)
                continue
            }
            val replaced = replaceAutoOcrRegions(result, refined, language, { it.recognizedLanguage }, ::overlaps)
            if (replaced != result) {
                result = replaced
                usedManga = usedManga || selected.engine == OcrEngineKind.MANGA_OCR_JA
            }
        }
        Timber.tag("AutoOCR").i("bootstrap=%s passes=%d languages=%s blocks=%d", bestKind, passes.size, groups.keys, result.size)
        return AutomaticOcrResult(result.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left })), usedManga)
    }

    private fun overlaps(a: TextBlock, b: TextBlock): Boolean {
        val x = (minOf(a.boundingBox.right, b.boundingBox.right) - maxOf(a.boundingBox.left, b.boundingBox.left)).coerceAtLeast(0)
        val y = (minOf(a.boundingBox.bottom, b.boundingBox.bottom) - maxOf(a.boundingBox.top, b.boundingBox.top)).coerceAtLeast(0)
        val area = minOf(a.boundingBox.width().toLong() * a.boundingBox.height(), b.boundingBox.width().toLong() * b.boundingBox.height())
        return area > 0 && x.toLong() * y >= area * 0.5
    }
}
