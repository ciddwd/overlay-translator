package com.gameocr.app.translate

import android.graphics.Bitmap
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.glossary.TranslationContextResolver
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.ocr.TextBlock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/** 按 [Settings.translatorEngine] 路由到 OpenAI 兼容 / DeepL / 有道图片翻译 / Google /
 *  火山 / 百度翻译 / 腾讯云翻译。新增引擎只需在此加构造参数 + [engineFor] 的 when 分支。 */
@Singleton
class RoutingTranslator @Inject constructor(
    private val openAi: OpenAiTranslator,
    private val deepl: DeepLTranslator,
    private val youdaoPicTrans: YoudaoPicTransTranslator,
    private val google: GoogleTranslator,
    private val googleMlKit: MlKitOnDeviceTranslator,
    private val volc: VolcTranslator,
    private val baiduFanyi: BaiduFanyiTranslator,
    private val tencent: TencentTranslator,
    private val sakura: SakuraGalTranslator,
    private val hyMt2: HyMt2Translator,
    private val llamaEngineHolder: LlamaEngineHolder,
    private val translationContextResolver: TranslationContextResolver,
) : Translator {
    override suspend fun translate(source: String, settings: Settings): String? {
        if (shouldPassthroughNumericTranslation(source)) {
            logNumericPassthrough(stage = "translate", count = 1, total = 1)
            return source
        }
        val enriched = translationContextResolver.enrich(source, settings)
        return normalizeText(
            text = engineFor(enriched).translate(source, enriched),
            settings = enriched,
            stage = "translate"
        )
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> =
        flow {
            if (shouldPassthroughNumericTranslation(source)) {
                logNumericPassthrough(stage = "stream", count = 1, total = 1)
                emit(source)
                return@flow
            }
            val enriched = translationContextResolver.enrich(source, settings)
            emitAll(engineFor(enriched).translateStream(source, enriched))
        }
            .map { ChineseScriptNormalizer.normalizeForTarget(it, settings.targetLang) }

    /** RoutingTranslator 把 prefersBatch 直接转发到当前 settings 选中的引擎。 */
    override val prefersBatch: Boolean
        get() = false // 不能静态判断；调用方应该用 [prefersBatchFor]

    fun prefersBatchFor(settings: Settings): Boolean = engineFor(settings).prefersBatch

    suspend fun downloadMlKitLanguagePair(sourceLang: String, targetLang: String) {
        googleMlKit.ensureLanguagePairModelsDownloaded(sourceLang, targetLang)
    }

    suspend fun areMlKitLanguagePairModelsDownloaded(
        sourceLang: String,
        targetLang: String,
    ): Boolean = googleMlKit.areLanguagePairModelsDownloaded(sourceLang, targetLang)

    suspend fun getMissingMlKitLanguageModels(
        sourceLang: String,
        targetLang: String,
    ): Set<String> = googleMlKit.getMissingLanguageModels(sourceLang, targetLang)

    suspend fun getDownloadedMlKitLanguageModels(): Set<String> =
        googleMlKit.getDownloadedLanguageModels()

    internal suspend fun prewarmLocalModel(settings: Settings): LocalLlmPrewarmResult {
        val local = engineFor(settings) as? LocalLlamaTranslator
        val installed = local?.isPrewarmModelInstalled() == true
        val decision = LocalLlmPrewarmPolicy.decide(
            routedToLocalModel = local != null,
            modelInstalled = installed,
        )
        if (decision == LocalLlmPrewarmDecision.PREWARM) {
            checkNotNull(local).prewarm(settings)
        }
        return LocalLlmPrewarmResult(
            decision = decision,
            modelKind = local?.prewarmModelKind?.name,
        )
    }

    override suspend fun translateBatch(
        sources: List<String>,
        settings: Settings,
    ): List<String?> = translateBatchIncremental(sources, settings) { }

    override suspend fun translateBatchIncremental(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> {
        val passthroughPlan = planNumericTranslationPassthrough(sources)
        if (passthroughPlan.passthroughUpdates.isNotEmpty()) {
            logNumericPassthrough(
                stage = "batch",
                count = passthroughPlan.passthroughUpdates.size,
                total = sources.size,
            )
            passthroughPlan.passthroughUpdates.forEach(onUpdate)
        }
        if (passthroughPlan.translatableSources.isEmpty()) {
            return passthroughPlan.merge(emptyList())
        }

        val enriched = translationContextResolver.enrich(
            passthroughPlan.translatableSources.joinToString("\n"),
            settings,
        )
        val rawResults = engineFor(enriched).translateBatchIncremental(
            passthroughPlan.translatableSources,
            enriched,
        ) { update ->
            passthroughPlan.originalIndexFor(update.index)?.let { originalIndex ->
                onUpdate(
                    update.copy(
                        index = originalIndex,
                        text = update.text?.let { normalizePlain(it, enriched) },
                    )
                )
            }
        }
        val normalizedResults = normalizeBatch(
            texts = rawResults,
            settings = enriched,
            stage = "batch"
        )
        return passthroughPlan.merge(normalizedResults)
    }

    override suspend fun testConnection(settings: Settings): TestResult =
        engineFor(settings).testConnection(settings)

    override val isEndToEnd: Boolean get() = false
    /** RoutingTranslator 的 isEndToEnd 不能静态判，调用方应用 [isEndToEndFor]。 */
    fun isEndToEndFor(settings: Settings): Boolean = engineFor(settings).isEndToEnd

    override suspend fun ocrAndTranslate(
        bitmap: Bitmap,
        settings: Settings
    ): List<Pair<TextBlock, String>> =
        normalizeOcrTranslations(
            results = engineFor(settings).ocrAndTranslate(bitmap, settings),
            settings = settings
        )

    override suspend fun translateWord(source: String, settings: Settings): WordResult? {
        val enriched = translationContextResolver.enrich(source, settings)
        return engineFor(enriched).translateWord(source, enriched)
            ?.let { normalizeWordResult(it, enriched) }
    }

    private fun normalizeText(text: String?, settings: Settings, stage: String): String? {
        val raw = text ?: return null
        val normalized = normalizePlain(raw, settings)
        if (normalized != raw) {
            logNormalization(stage, settings.targetLang, 1, 1, raw, normalized)
        }
        return normalized
    }

    private fun normalizeBatch(
        texts: List<String?>,
        settings: Settings,
        stage: String,
    ): List<String?> {
        var changed = 0
        var firstBefore: String? = null
        var firstAfter: String? = null
        val normalized = texts.map { raw ->
            if (raw == null) {
                null
            } else {
                val next = normalizePlain(raw, settings)
                if (next != raw) {
                    changed += 1
                    if (firstBefore == null) {
                        firstBefore = raw
                        firstAfter = next
                    }
                }
                next
            }
        }
        if (changed > 0) {
            logNormalization(stage, settings.targetLang, changed, texts.size, firstBefore.orEmpty(), firstAfter.orEmpty())
        }
        return normalized
    }

    private fun normalizeOcrTranslations(
        results: List<Pair<TextBlock, String>>,
        settings: Settings,
    ): List<Pair<TextBlock, String>> {
        var changed = 0
        var firstBefore: String? = null
        var firstAfter: String? = null
        val normalized = results.map { (block, raw) ->
            val next = normalizePlain(raw, settings)
            if (next != raw) {
                changed += 1
                if (firstBefore == null) {
                    firstBefore = raw
                    firstAfter = next
                }
            }
            block to next
        }
        if (changed > 0) {
            logNormalization("ocrAndTranslate", settings.targetLang, changed, results.size, firstBefore.orEmpty(), firstAfter.orEmpty())
        }
        return normalized
    }

    private fun normalizeWordResult(result: WordResult, settings: Settings): WordResult {
        val normalized = result.copy(
            pos = result.pos.map { normalizePlain(it, settings) },
            definitions = result.definitions.map { normalizePlain(it, settings) },
            difficultyNotes = result.difficultyNotes.map { normalizePlain(it, settings) },
            examples = result.examples.map { example ->
                example.copy(dst = normalizePlain(example.dst, settings))
            },
            fallbackTranslation = result.fallbackTranslation?.let { normalizePlain(it, settings) }
        )
        if (normalized != result) {
            val before = result.fallbackTranslation ?: result.definitions.firstOrNull().orEmpty()
            val after = normalized.fallbackTranslation ?: normalized.definitions.firstOrNull().orEmpty()
            logNormalization("word", settings.targetLang, 1, 1, before, after)
        }
        return normalized
    }

    private fun normalizePlain(text: String, settings: Settings): String =
        ChineseScriptNormalizer.normalizeForTarget(text, settings.targetLang)

    private fun logNormalization(
        stage: String,
        targetLang: String,
        changed: Int,
        total: Int,
        before: String,
        after: String,
    ) {
        Timber.tag("ZhScript").i(
            "[%s] target=%s script=%s changed=%d/%d before=%s after=%s",
            stage,
            targetLang,
            ChineseScriptNormalizer.targetScriptFor(targetLang),
            changed,
            total,
            preview(before),
            preview(after)
        )
    }

    private fun logNumericPassthrough(stage: String, count: Int, total: Int) {
        Timber.tag("TranslationPolicy").i(
            "numeric passthrough stage=%s count=%d total=%d",
            stage,
            count,
            total,
        )
    }

    private fun preview(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').take(80)

    private fun engineFor(settings: Settings): Translator = when (settings.translatorEngine) {
        TranslatorEngine.OPENAI -> openAi
        TranslatorEngine.DEEPL -> deepl
        TranslatorEngine.YOUDAO_PICTRANS -> youdaoPicTrans
        TranslatorEngine.GOOGLE -> google
        TranslatorEngine.GOOGLE_ML_KIT -> googleMlKit
        TranslatorEngine.VOLC -> volc
        TranslatorEngine.BAIDU_FANYI -> baiduFanyi
        TranslatorEngine.TENCENT -> tencent
        // Sakura is fixed to Japanese -> Simplified Chinese; unsupported pairs fall back to OpenAI-compatible LLM.
        // HY-MT 已从枚举移除（PR 未合主线），软回退目标改为 openAi。
        TranslatorEngine.LOCAL_SAKURA ->
            if (shouldUseLocalSakura(settings.sourceLang, settings.targetLang, llamaEngineHolder.isDeviceCapable())) {
                sakura
            } else {
                openAi
            }
        TranslatorEngine.LOCAL_HY_MT2 ->
            if (shouldUseLocalHyMt2(llamaEngineHolder.isDeviceCapable())) {
                hyMt2
            } else {
                openAi
            }
    }

    internal companion object {
        fun shouldUseLocalSakura(
            sourceLang: String,
            targetLang: String,
            deviceCapable: Boolean,
        ): Boolean {
            return deviceCapable && supportsSakuraSource(sourceLang) && supportsSakuraTarget(targetLang)
        }

        fun supportsSakuraSource(sourceLang: String): Boolean {
            val normalized = sourceLang.trim().lowercase()
            return normalized == "ja" || normalized.startsWith("ja-")
        }

        fun supportsSakuraTarget(targetLang: String): Boolean {
            return targetLang.trim().equals("zh-CN", ignoreCase = true)
        }

        fun shouldUseLocalHyMt2(deviceCapable: Boolean): Boolean = deviceCapable
    }
}
