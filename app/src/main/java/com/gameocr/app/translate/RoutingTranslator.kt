package com.gameocr.app.translate

import android.graphics.Bitmap
import android.os.SystemClock
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.glossary.TranslationContextResolver
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.util.RuntimePerformanceDiagnostics
import com.gameocr.app.util.RuntimePerformanceKeyPolicy
import com.gameocr.app.util.RuntimePerformanceStage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import timber.log.Timber

/** 按 [Settings.translatorEngine] 路由到用户选择的翻译引擎。 */
@Singleton
class RoutingTranslator @Inject constructor(
    private val openAi: OpenAiTranslator,
    private val anthropic: AnthropicTranslator,
    private val deepl: DeepLTranslator,
    private val niuTrans: NiuTransTranslator,
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
    private val translationMemory: TranslationMemoryService,
    private val performanceDiagnostics: RuntimePerformanceDiagnostics,
) : Translator {
    override suspend fun translate(source: String, settings: Settings): String? {
        performanceDiagnostics.beginTranslation()
        translationMemory.recall(source, settings)?.let { memory ->
            return normalizePlain(memory.correctedTranslation, settings)
        }
        if (shouldPassthroughNumericTranslation(source)) {
            logNumericPassthrough(stage = "translate", count = 1, total = 1)
            return source
        }
        val enriched = translationContextResolver.enrich(source, settings)
        val startedAt = SystemClock.elapsedRealtime()
        val translated = engineFor(enriched).translate(source, enriched)
        performanceDiagnostics.observe(
            stage = RuntimePerformanceStage.TRANSLATION,
            operationKey = RuntimePerformanceKeyPolicy.text(
                operation = "${enriched.translatorEngine.name}/${enriched.translationContextMode.name}/single",
                itemCount = 1,
                characterCount = source.length,
            ),
            elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
        )
        return normalizeText(
            text = translated,
            settings = enriched,
            stage = "translate"
        )
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> =
        flow {
            performanceDiagnostics.beginTranslation()
            translationMemory.recall(source, settings)?.let { memory ->
                emit(memory.correctedTranslation)
                return@flow
            }
            if (shouldPassthroughNumericTranslation(source)) {
                logNumericPassthrough(stage = "stream", count = 1, total = 1)
                emit(source)
                return@flow
            }
            val enriched = translationContextResolver.enrich(source, settings)
            val startedAt = SystemClock.elapsedRealtime()
            var completed = false
            try {
                emitAll(engineFor(enriched).translateStream(source, enriched))
                completed = true
            } finally {
                if (completed) {
                    performanceDiagnostics.observe(
                        stage = RuntimePerformanceStage.TRANSLATION,
                        operationKey = RuntimePerformanceKeyPolicy.text(
                            operation = "${enriched.translatorEngine.name}/${enriched.translationContextMode.name}/stream",
                            itemCount = 1,
                            characterCount = source.length,
                        ),
                        elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                    )
                }
            }
        }
            .map { ChineseScriptNormalizer.normalizeForTarget(it, settings.targetLang) }

    /** RoutingTranslator 把 prefersBatch 直接转发到当前 settings 选中的引擎。 */
    override val prefersBatch: Boolean
        get() = false // 不能静态判断；调用方应该用 [prefersBatchFor]

    fun prefersBatchFor(settings: Settings): Boolean {
        val engine = engineFor(settings)
        return if (engine === niuTrans) niuTrans.prefersBatch(settings) else engine.prefersBatch
    }

    fun supportsStructuredContextBatchFor(settings: Settings): Boolean =
        engineFor(settings).supportsStructuredContextBatch

    override fun handlesTranslationFailureRetry(settings: Settings): Boolean =
        engineFor(settings).handlesTranslationFailureRetry(settings)

    suspend fun downloadMlKitLanguagePair(sourceLang: String, targetLang: String, timeoutSeconds: Int) {
        googleMlKit.ensureLanguagePairModelsDownloaded(sourceLang, targetLang, timeoutSeconds)
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

    suspend fun deleteMlKitSourceLanguageModel(sourceLang: String, targetLang: String) =
        googleMlKit.deleteSourceLanguageModel(sourceLang, targetLang)

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
        if (sources.isEmpty()) return emptyList()
        performanceDiagnostics.beginTranslation()
        val selectedEngine = engineFor(settings)
        val promptScope = selectedEngine.batchPromptScope(settings)
        Timber.tag("TranslationBatch").i(
            "route engine=%s contextMode=%s promptScope=%s sources=%d",
            settings.translatorEngine.name,
            settings.translationContextMode.name,
            promptScope.name,
            sources.size,
        )
        if (promptScope == BatchPromptScope.SHARED_PAGE) {
            return translateFullContextBatch(sources, settings, onUpdate)
        }
        val memoryMatches = translationMemory.recallBatch(sources, settings)
        val mergedResults = MutableList<String?>(sources.size) { null }
        val pendingIndexes = mutableListOf<Int>()
        val pendingSources = mutableListOf<String>()
        sources.forEachIndexed { index, source ->
            val memory = memoryMatches.getOrNull(index)
            if (memory == null) {
                pendingIndexes += index
                pendingSources += source
            } else {
                val recalled = normalizePlain(memory.correctedTranslation, settings)
                mergedResults[index] = recalled
                onUpdate(BatchTranslationUpdate(index = index, text = recalled, elapsedMs = 0L))
            }
        }
        if (pendingSources.isEmpty()) return mergedResults

        val passthroughPlan = planNumericTranslationPassthrough(pendingSources)
        if (passthroughPlan.passthroughUpdates.isNotEmpty()) {
            logNumericPassthrough(
                stage = "batch",
                count = passthroughPlan.passthroughUpdates.size,
                total = sources.size,
            )
            passthroughPlan.passthroughUpdates.forEach { update ->
                val originalIndex = pendingIndexes.getOrNull(update.index) ?: return@forEach
                mergedResults[originalIndex] = update.text
                onUpdate(update.copy(index = originalIndex))
            }
        }
        if (passthroughPlan.translatableSources.isEmpty()) {
            return mergedResults
        }

        val enriched = translationContextResolver.enrich(
            passthroughPlan.translatableSources.joinToString("\n"),
            settings,
        )
        val rawResults = engineFor(enriched).translateBatchIncremental(
            passthroughPlan.translatableSources,
            enriched,
        ) { update ->
            passthroughPlan.originalIndexFor(update.index)?.let { pendingIndex ->
                pendingIndexes.getOrNull(pendingIndex)?.let { originalIndex ->
                onUpdate(
                    update.copy(
                        index = originalIndex,
                        text = update.text?.let { normalizePlain(it, enriched) },
                    )
                )
                }
            }
        }
        val normalizedResults = normalizeBatch(
            texts = rawResults,
            settings = enriched,
            stage = "batch"
        )
        passthroughPlan.merge(normalizedResults).forEachIndexed { pendingIndex, text ->
            pendingIndexes.getOrNull(pendingIndex)?.let { originalIndex ->
                mergedResults[originalIndex] = text
            }
        }
        return mergedResults
    }

    private suspend fun translateFullContextBatch(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> {
        val memoryMatches = translationMemory.recallBatch(sources, settings)
        val overrides = MutableList<String?>(sources.size) { null }
        memoryMatches.forEachIndexed { index, memory ->
            if (memory != null) {
                overrides[index] = normalizePlain(memory.correctedTranslation, settings)
            }
        }

        var passthroughCount = 0
        sources.forEachIndexed { index, source ->
            if (overrides[index] == null && shouldPassthroughNumericTranslation(source)) {
                overrides[index] = source
                passthroughCount += 1
            }
        }
        if (passthroughCount > 0) {
            logNumericPassthrough(
                stage = "full-context-batch",
                count = passthroughCount,
                total = sources.size,
            )
        }

        val results = MutableList<String?>(sources.size) { null }
        val progress = BatchTranslationProgressState(sources.size)
        overrides.forEachIndexed { index, text ->
            if (text != null && progress.accept(index)) {
                results[index] = text
                onUpdate(BatchTranslationUpdate(index = index, text = text, elapsedMs = 0L))
            }
        }
        if (overrides.all { it != null }) return results

        val enriched = translationContextResolver.enrich(sources.joinToString("\n"), settings)
        val rawResults = engineFor(enriched).translateBatchIncremental(
            sources = sources,
            settings = enriched,
        ) { update ->
            val index = update.index
            if (index !in sources.indices || !progress.accept(index)) return@translateBatchIncremental
            val text = overrides[index] ?: update.text?.let { normalizePlain(it, enriched) }
            results[index] = text
            onUpdate(update.copy(text = text))
        }
        val normalizedResults = normalizeBatch(
            texts = rawResults,
            settings = enriched,
            stage = "full-context-batch",
        )
        sources.indices.forEach { index ->
            val text = overrides[index] ?: normalizedResults.getOrNull(index)
            results[index] = text
            if (progress.accept(index)) {
                onUpdate(BatchTranslationUpdate(index = index, text = text))
            }
        }
        return results
    }

    override suspend fun testConnection(settings: Settings): TestResult =
        engineFor(settings).testConnection(settings)

    override val isEndToEnd: Boolean get() = false
    /** RoutingTranslator 的 isEndToEnd 不能静态判，调用方应用 [isEndToEndFor]。 */
    fun isEndToEndFor(settings: Settings): Boolean = engineFor(settings).isEndToEnd

    override suspend fun ocrAndTranslate(
        bitmap: Bitmap,
        settings: Settings
    ): List<Pair<TextBlock, String>> {
        performanceDiagnostics.beginTranslation()
        val normalized = normalizeOcrTranslations(
            results = engineFor(settings).ocrAndTranslate(bitmap, settings),
            settings = settings
        )
        val memoryMatches = translationMemory.recallBatch(
            sources = normalized.map { it.first.text },
            settings = settings,
        )
        return normalized.mapIndexed { index, pair ->
            val memory = memoryMatches.getOrNull(index)
            if (memory == null) pair
            else pair.first to normalizePlain(memory.correctedTranslation, settings)
        }
    }

    override suspend fun translateWord(source: String, settings: Settings): WordResult? {
        performanceDiagnostics.beginTranslation()
        val enriched = translationContextResolver.enrich(source, settings)
        return engineFor(enriched).translateWord(source, enriched)
            ?.let { normalizeWordResult(it, enriched) }
    }

    override suspend fun translateWordCompact(source: String, settings: Settings): WordResult? {
        performanceDiagnostics.beginTranslation()
        val enriched = translationContextResolver.enrich(source, settings)
        return engineFor(enriched).translateWordCompact(source, enriched)
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
            senses = result.senses.map { sense ->
                sense.copy(
                    definitions = sense.definitions.map { normalizePlain(it, settings) },
                    formNote = normalizePlain(sense.formNote, settings),
                )
            },
            difficultyNotes = result.difficultyNotes.map { normalizePlain(it, settings) },
            examples = result.examples.map { example ->
                example.copy(dst = normalizePlain(example.dst, settings))
            },
            fallbackTranslation = result.fallbackTranslation?.let { normalizePlain(it, settings) }
        )
        if (normalized != result) {
            val before = result.fallbackTranslation ?: result.effectiveDefinitions().firstOrNull().orEmpty()
            val after = normalized.fallbackTranslation ?: normalized.effectiveDefinitions().firstOrNull().orEmpty()
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
        TranslatorEngine.ANTHROPIC -> anthropic
        TranslatorEngine.DEEPL -> deepl
        TranslatorEngine.NIUTRANS -> niuTrans
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
