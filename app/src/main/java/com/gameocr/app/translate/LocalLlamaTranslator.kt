package com.gameocr.app.translate

import android.os.SystemClock
import com.arm.aichat.InferenceEngine
import com.gameocr.app.BuildConfig
import com.gameocr.app.data.Settings
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.llm.LlamaMultiSequence
import com.gameocr.app.llm.LlamaPromptMetrics
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.util.InferenceTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * 端侧 llama.cpp Translator 通用基类。把 [LlamaEngineHolder] 的 token-by-token Flow 拼成
 * 累积译文（符合 [Translator.translateStream] 的"每次发射全量当前译文"约定）。
 *
 * 具体引擎（[HyMt2Translator] / [SakuraGalTranslator]）只负责：
 * - 声明绑定哪个 [LlmModelKind]；
 * - 给出 system prompt（HY-MT 无 / Sakura 有翻译角色约束）；
 * - 给出 user prompt（HY-MT 用 "Please translate to {target}: {src}" 极简模板；Sakura 用 ACGN 化模板）。
 *
 * 注意：当前 [com.arm.aichat.InferenceEngine.sendUserPrompt] 不接受采样温度等参数——
 * binding 把 temperature / top_p / top_k 写死在 JNI 层。[Settings.localLlmMaxNewTokens]
 * 会传给生成接口，[Settings.localLlmContextSize] 用作批译的逻辑 token 预算；后者不会改变
 * native context 的物理容量。灰度期若发现输出过于发散，需要继续把 sampler 参数暴露上来。
 */
abstract class LocalLlamaTranslator(
    protected val holder: LlamaEngineHolder,
    private val cache: TranslationCache,
) : Translator {

    protected abstract val modelKind: LlmModelKind

    /**
     * 该引擎要不要 system prompt。返回 null 表示不设（Hy-MT2 走纯 user prompt）。
     *
     * **必须返回静态字符串**——binding 的 [com.arm.aichat.InferenceEngine.setSystemPrompt] 是
     * 一次性 API：loadModel 后只能调用唯一一次（_readyForSystemPrompt 标志位用过即弃）。
     * 所以我们让 system prompt 跟 [modelKind] 绑定，loadModel 时由 [LlamaEngineHolder]
     * 立即调一次后续就不再调；不能依赖运行时 settings 改变 system prompt（变了也无法重设）。
     */
    protected abstract val systemPrompt: String?

    protected abstract fun buildUserPrompt(source: String, settings: Settings): String

    internal val prewarmModelKind: LlmModelKind get() = modelKind

    internal fun isPrewarmModelInstalled(): Boolean = holder.isModelInstalled(modelKind)

    internal suspend fun prewarm(settings: Settings) {
        val startedAt = SystemClock.elapsedRealtime()
        val engine = holder.ensureLoaded(modelKind, systemPrompt)
        val modelReadyAt = SystemClock.elapsedRealtime()
        var outputPieces = 0
        holder.inferenceMutex.withLock {
            engine.sendUserPrompt(
                buildUserPrompt(PREWARM_SOURCE, settings),
                PREWARM_PREDICT_LENGTH,
            ).collect { outputPieces++ }
        }
        val finishedAt = SystemClock.elapsedRealtime()
        holder.touch()
        Timber.tag(PERF_TAG).i(
            "prewarm completed kind=%s modelReadyMs=%d inferenceMs=%d totalMs=%d pieces=%d",
            modelKind.name,
            InferenceTiming.elapsedMs(startedAt, modelReadyAt),
            InferenceTiming.elapsedMs(modelReadyAt, finishedAt),
            InferenceTiming.elapsedMs(startedAt, finishedAt),
            outputPieces,
        )
    }

    override val prefersBatch: Boolean get() = BuildConfig.LOCAL_LLM_BATCH_SIZE > 1

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

        val results = MutableList<String?>(sources.size) { null }
        val emitted = BooleanArray(sources.size)
        fun publish(index: Int, text: String?) {
            if (index !in results.indices || emitted[index]) return
            emitted[index] = true
            onUpdate(BatchTranslationUpdate(index = index, text = text))
        }
        val pendingByKey = linkedMapOf<String, BatchPending>()
        var cacheHits = 0
        sources.forEachIndexed { index, source ->
            if (source.isBlank()) {
                publish(index, null)
                return@forEachIndexed
            }
            val individualPrompt = runtimePrompt(buildUserPrompt(source, settings), settings)
            val key = cacheKey(source, settings, individualPrompt)
            val cached = cache.get(key, settings)
            if (cached != null) {
                results[index] = cached
                cacheHits += 1
                publish(index, cached)
            } else {
                pendingByKey.getOrPut(key) {
                    BatchPending(
                        source = source,
                        individualPrompt = individualPrompt,
                        cacheKey = key,
                    )
                }.resultIndexes += index
            }
        }
        if (pendingByKey.isEmpty()) return results

        val modelReadyStartedAt = SystemClock.elapsedRealtime()
        val engine = holder.ensureLoaded(modelKind, systemPrompt)
        val modelReadyMs = InferenceTiming.elapsedMs(modelReadyStartedAt, SystemClock.elapsedRealtime())
        val queuedAt = SystemClock.elapsedRealtime()
        holder.inferenceMutex.withLock {
            val pending = pendingByKey.values.filter { item ->
                val cached = cache.get(item.cacheKey, settings)
                if (cached == null) {
                    true
                } else {
                    item.resultIndexes.forEach {
                        results[it] = cached
                        publish(it, cached)
                    }
                    cacheHits += item.resultIndexes.size
                    false
                }
            }
            if (pending.isEmpty()) return@withLock

            val engineContextTokens = LlamaPromptMetrics.contextSizeTokens()
            val nativePromptBatchTokens = LlamaPromptMetrics.batchSizeTokens()
            val nativeSequenceCapacity = LlamaPromptMetrics.sequenceCapacity()
            val systemPromptTokens = LlamaPromptMetrics.systemPromptTokens()
            val selectedBatchSize = LocalLlmNativeBatchPolicy.selectedBatchSize(
                requested = BuildConfig.LOCAL_LLM_BATCH_SIZE,
                nativeSequenceCapacity = nativeSequenceCapacity,
            )
            val plans = LocalLlmNativeBatchPolicy.plan(
                items = pending,
                requestedBatchSize = BuildConfig.LOCAL_LLM_BATCH_SIZE,
                configuredContextTokens = settings.localLlmContextSize,
                engineContextTokens = engineContextTokens,
                systemPromptTokens = systemPromptTokens,
                nativePromptBatchTokens = nativePromptBatchTokens,
                nativeSequenceCapacity = nativeSequenceCapacity,
                maxNewTokensPerItem = settings.localLlmMaxNewTokens,
                promptTokenCount = { item ->
                    LlamaPromptMetrics.countUserPromptTokens(item.individualPrompt)
                },
            )
            Timber.tag(PERF_TAG).i(
                "native batch plan kind=%s segments=%d configured=B%d selected=B%d unique=%d cacheHits=%d " +
                    "groups=%d nativeGroups=%d configuredContext=%d engineContext=%d " +
                    "systemTokens=%d promptBatchTokens=%d sequenceCapacity=%d",
                modelKind.name,
                sources.size,
                BuildConfig.LOCAL_LLM_BATCH_SIZE,
                selectedBatchSize,
                pending.size,
                cacheHits,
                plans.size,
                plans.count { it.nativeBatch },
                settings.localLlmContextSize,
                engineContextTokens,
                systemPromptTokens,
                nativePromptBatchTokens,
                nativeSequenceCapacity,
            )

            var firstRequest = true
            plans.forEachIndexed { groupIndex, plan ->
                val requestQueuedAt = if (firstRequest) queuedAt else SystemClock.elapsedRealtime()
                val requestModelReadyMs = if (firstRequest) modelReadyMs else 0L
                firstRequest = false
                if (!plan.nativeBatch) {
                    val item = plan.items.single()
                    val translated = generateLocked(
                        engine = engine,
                        userPrompt = item.individualPrompt,
                        predictLength = settings.localLlmMaxNewTokens,
                        mode = "native-single",
                        sourceForLog = item.source,
                        modelReadyMs = requestModelReadyMs,
                        queuedAt = requestQueuedAt,
                    )
                    applyBatchResult(item, translated, results, settings, ::publish)
                    return@forEachIndexed
                }

                val startedAt = SystemClock.elapsedRealtime()
                val outputs = LlamaMultiSequence.generate(
                    prompts = plan.items.map { it.individualPrompt },
                    predictLength = settings.localLlmMaxNewTokens,
                )?.map { output -> output.trim().ifBlank { null } }
                val finishedAt = SystemClock.elapsedRealtime()
                Timber.tag(PERF_TAG).i(
                    "native batch result kind=%s group=%d/%d B=%d promptTokens=%d requiredKv=%d " +
                        "modelReadyMs=%d queueMs=%d totalMs=%d success=%s",
                    modelKind.name,
                    groupIndex + 1,
                    plans.size,
                    plan.items.size,
                    plan.promptTokens,
                    plan.requiredKvTokens,
                    requestModelReadyMs,
                    InferenceTiming.elapsedMs(requestQueuedAt, startedAt),
                    InferenceTiming.elapsedMs(startedAt, finishedAt),
                    outputs != null,
                )
                if (outputs != null) {
                    plan.items.zip(outputs).forEach { (item, translated) ->
                        applyBatchResult(item, translated, results, settings, ::publish)
                    }
                } else {
                    Timber.tag(PERF_TAG).w(
                        "native batch fallback kind=%s group=%d items=%d reason=native_failure",
                        modelKind.name,
                        groupIndex + 1,
                        plan.items.size,
                    )
                    plan.items.forEach { item ->
                        val translated = generateLocked(
                            engine = engine,
                            userPrompt = item.individualPrompt,
                            predictLength = settings.localLlmMaxNewTokens,
                            mode = "native-fallback",
                            sourceForLog = item.source,
                            modelReadyMs = 0L,
                            queuedAt = SystemClock.elapsedRealtime(),
                        )
                        applyBatchResult(item, translated, results, settings, ::publish)
                    }
                }
            }
            holder.touch()
        }
        return results
    }

    override suspend fun translate(source: String, settings: Settings): String? {
        if (source.isBlank()) return null
        val userPrompt = runtimePrompt(buildUserPrompt(source, settings), settings)
        val cacheKey = cacheKey(source, settings, userPrompt)
        cache.get(cacheKey, settings)?.let {
            Timber.tag(PERF_TAG).i("cache hit kind=%s mode=full inputChars=%d", modelKind.name, source.length)
            return it
        }
        val modelReadyStartedAt = SystemClock.elapsedRealtime()
        val engine = holder.ensureLoaded(modelKind, systemPrompt)
        val modelReadyMs = InferenceTiming.elapsedMs(modelReadyStartedAt, SystemClock.elapsedRealtime())
        // 推理串行：translateBatch 默认并发触发多个 translate，端侧 LLM engine 一次只能跑一段，
        // 用 holder 的全局 inferenceMutex 排队，避免后段被 binding 丢弃。
        val queuedAt = SystemClock.elapsedRealtime()
        return holder.inferenceMutex.withLock {
            val startedAt = SystemClock.elapsedRealtime()
            cache.get(cacheKey, settings)?.let {
                Timber.tag(PERF_TAG).i(
                    "cache hit kind=%s mode=full afterQueueMs=%d inputChars=%d",
                    modelKind.name,
                    InferenceTiming.elapsedMs(queuedAt, startedAt),
                    source.length,
                )
                return@withLock it
            }
            val sb = StringBuilder()
            var firstOutputAt: Long? = null
            var outputPieces = 0
            engine.sendUserPrompt(userPrompt, settings.localLlmMaxNewTokens)
                .collect { token ->
                    if (firstOutputAt == null) firstOutputAt = SystemClock.elapsedRealtime()
                    outputPieces++
                    sb.append(token)
                }
            val finishedAt = SystemClock.elapsedRealtime()
            logGeneration(
                mode = "full",
                source = source,
                outputChars = sb.length,
                modelReadyMs = modelReadyMs,
                queuedAt = queuedAt,
                startedAt = startedAt,
                firstOutputAt = firstOutputAt,
                finishedAt = finishedAt,
                outputPieces = outputPieces,
                maxNewTokens = settings.localLlmMaxNewTokens,
            )
            holder.touch()
            sb.toString().trim().ifBlank { null }?.also { cache.put(cacheKey, it, settings) }
        }
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        if (source.isBlank()) return@flow
        val userPrompt = runtimePrompt(buildUserPrompt(source, settings), settings)
        val cacheKey = cacheKey(source, settings, userPrompt)
        cache.get(cacheKey, settings)?.let { cached ->
            Timber.tag(PERF_TAG).i("cache hit kind=%s mode=stream inputChars=%d", modelKind.name, source.length)
            emit(cached)
            return@flow
        }
        val modelReadyStartedAt = SystemClock.elapsedRealtime()
        val engine = holder.ensureLoaded(modelKind, systemPrompt)
        val modelReadyMs = InferenceTiming.elapsedMs(modelReadyStartedAt, SystemClock.elapsedRealtime())
        val queuedAt = SystemClock.elapsedRealtime()
        holder.inferenceMutex.withLock {
            val startedAt = SystemClock.elapsedRealtime()
            cache.get(cacheKey, settings)?.let { cached ->
                Timber.tag(PERF_TAG).i(
                    "cache hit kind=%s mode=stream afterQueueMs=%d inputChars=%d",
                    modelKind.name,
                    InferenceTiming.elapsedMs(queuedAt, startedAt),
                    source.length,
                )
                emit(cached)
                return@withLock
            }
            val sb = StringBuilder()
            var firstOutputAt: Long? = null
            var outputPieces = 0
            engine.sendUserPrompt(userPrompt, settings.localLlmMaxNewTokens)
                .collect { token ->
                    if (firstOutputAt == null) firstOutputAt = SystemClock.elapsedRealtime()
                    outputPieces++
                    sb.append(token)
                    emit(sb.toString())
                }
            val finishedAt = SystemClock.elapsedRealtime()
            logGeneration(
                mode = "stream",
                source = source,
                outputChars = sb.length,
                modelReadyMs = modelReadyMs,
                queuedAt = queuedAt,
                startedAt = startedAt,
                firstOutputAt = firstOutputAt,
                finishedAt = finishedAt,
                outputPieces = outputPieces,
                maxNewTokens = settings.localLlmMaxNewTokens,
            )
            holder.touch()
            sb.toString().trim().takeIf { it.isNotBlank() }?.let { cache.put(cacheKey, it, settings) }
        }
    }

    private suspend fun generateLocked(
        engine: InferenceEngine,
        userPrompt: String,
        predictLength: Int,
        mode: String,
        sourceForLog: String,
        modelReadyMs: Long,
        queuedAt: Long,
    ): String? {
        val startedAt = SystemClock.elapsedRealtime()
        val output = StringBuilder()
        var firstOutputAt: Long? = null
        var outputPieces = 0
        engine.sendUserPrompt(userPrompt, predictLength.coerceAtLeast(1)).collect { token ->
            if (firstOutputAt == null) firstOutputAt = SystemClock.elapsedRealtime()
            outputPieces += 1
            output.append(token)
        }
        val finishedAt = SystemClock.elapsedRealtime()
        logGeneration(
            mode = mode,
            source = sourceForLog,
            outputChars = output.length,
            modelReadyMs = modelReadyMs,
            queuedAt = queuedAt,
            startedAt = startedAt,
            firstOutputAt = firstOutputAt,
            finishedAt = finishedAt,
            outputPieces = outputPieces,
            maxNewTokens = predictLength,
        )
        return output.toString().trim().ifBlank { null }
    }

    private fun applyBatchResult(
        item: BatchPending,
        translated: String?,
        results: MutableList<String?>,
        settings: Settings,
        publish: (Int, String?) -> Unit,
    ) {
        translated?.let { cache.put(item.cacheKey, it, settings) }
        localLlmBatchResultUpdates(item.resultIndexes, translated).forEach { update ->
            results[update.index] = update.text
            publish(update.index, update.text)
        }
    }

    private fun logGeneration(
        mode: String,
        source: String,
        outputChars: Int,
        modelReadyMs: Long,
        queuedAt: Long,
        startedAt: Long,
        firstOutputAt: Long?,
        finishedAt: Long,
        outputPieces: Int,
        maxNewTokens: Int,
    ) {
        val timing = InferenceTiming.generation(
            queuedAtMs = queuedAt,
            startedAtMs = startedAt,
            firstOutputAtMs = firstOutputAt,
            finishedAtMs = finishedAt,
            outputPieces = outputPieces,
        )
        Timber.tag(PERF_TAG).i(
            "generate kind=%s mode=%s modelReadyMs=%d queueMs=%d firstTokenMs=%d totalMs=%d " +
                "pieces=%d piecesPerSec=%s inputChars=%d outputChars=%d maxNewTokens=%d",
            modelKind.name,
            mode,
            modelReadyMs,
            timing.queueMs,
            timing.firstOutputMs ?: -1L,
            timing.totalMs,
            outputPieces,
            timing.outputPiecesPerSecond?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "n/a",
            source.length,
            outputChars,
            maxNewTokens,
        )
    }

    private fun cacheKey(source: String, settings: Settings, userPrompt: String): String =
        LocalLlamaTranslationCacheKey.build(
            cache = cache,
            source = source,
            modelKind = modelKind,
            sourceLang = settings.sourceLang,
            targetLang = settings.targetLang,
            maxNewTokens = settings.localLlmMaxNewTokens,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
        )

    private fun runtimePrompt(basePrompt: String, settings: Settings): String =
        if (settings.runtimeTranslationContext.isBlank()) {
            basePrompt
        } else {
            settings.runtimeTranslationContext + "\n\n" + basePrompt
        }

    override suspend fun testConnection(settings: Settings): TestResult = runCatching {
        if (!holder.isDeviceCapable()) {
            return@runCatching TestResult(success = false, message = "Android 13+ required")
        }
        holder.ensureLoaded(modelKind, systemPrompt)
        TestResult(success = true, message = "Model loaded: ${modelKind.displayName}")
    }.getOrElse { t ->
        TestResult(success = false, message = "${t.javaClass.simpleName}: ${t.message}")
    }

    private data class BatchPending(
        val source: String,
        val individualPrompt: String,
        val cacheKey: String,
        val resultIndexes: MutableList<Int> = mutableListOf(),
    )

    companion object {
        private const val PERF_TAG = "LocalLlmPerf"
        private const val PREWARM_SOURCE = "こんにちは"
        private const val PREWARM_PREDICT_LENGTH = 1
    }
}
