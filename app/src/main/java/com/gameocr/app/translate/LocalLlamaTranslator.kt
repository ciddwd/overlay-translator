package com.gameocr.app.translate

import android.os.SystemClock
import com.arm.aichat.InferenceEngine
import com.gameocr.app.BuildConfig
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.llm.LlamaEngineHolder
import com.gameocr.app.llm.LlamaMultiSequence
import com.gameocr.app.llm.LlamaPromptMetrics
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.util.InferenceTiming
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * 端侧 llama.cpp Translator 通用基类。把 [LlamaEngineHolder] 的 token-by-token Flow 拼成
 * 累积译文（符合 [Translator.translateStream] 的"每次发射全量当前译文"约定）。
 *
 * 具体引擎（[HyMt2Translator] / [SakuraGalTranslator]）只负责：
 * - 声明绑定哪个 [LlmModelKind]；
 * - 给出 system prompt（HY-MT 无 / Sakura 有翻译角色约束）；
 * - 给出 user prompt（Hy-MT2 使用官方翻译模板；Sakura 使用 ACGN 翻译模板）。
 *
 * 当前 binding 的生成接口只直接接收最大输出长度；模型专用采样参数由
 * [com.gameocr.app.llm.LocalLlmSamplingPolicy] 在加载模型前传给 native 层。
 * [Settings.localLlmContextSize] 用作批译的逻辑 token 预算，不改变 native context 的物理容量。
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

    /** Trims only the oldest continuous-history turns and measures with the loaded model tokenizer. */
    protected suspend fun trimContinuousHistoryForPrompts(
        promptSources: List<String>,
        settings: Settings,
    ): Settings {
        val context = settings.runtimeTranslationPromptContext
        if (
            settings.translationContextMode != TranslationContextMode.CONTINUOUS_CONTEXT ||
            context.previousFrame.isEmpty() ||
            promptSources.isEmpty()
        ) return settings
        return holder.withEngineSession(modelKind, systemPrompt) {
            val capacity = minOf(
                settings.localLlmContextSize,
                LlamaPromptMetrics.contextSizeTokens(),
            )
            val maxPromptTokens = (
                capacity - settings.localLlmMaxNewTokens - CONTINUOUS_CONTEXT_HEADROOM_TOKENS
                ).coerceAtLeast(0)
            val withoutHistory = settings.copy(
                runtimeTranslationPromptContext = context.copy(previousFrame = emptyList()),
            )
            val basePromptTokens = promptSources.maxOf { source ->
                LlamaPromptMetrics.countUserPromptTokens(
                    runtimePrompt(buildUserPrompt(source, withoutHistory), withoutHistory)
                )
            }
            val selected = DialogueHistoryTokenBudgetPolicy.selectNewest(
                turns = context.previousFrame,
                maxTokens = (maxPromptTokens - basePromptTokens).coerceAtLeast(0),
                tokenCount = { turn ->
                    LlamaPromptMetrics.countTextTokens(
                        buildString {
                            append("Source: ").append(turn.source)
                            turn.translation?.let { append("\nTranslation: ").append(it) }
                        }
                    )
                },
            ).toMutableList()
            var candidate = settings.copy(
                runtimeTranslationPromptContext = context.copy(previousFrame = selected),
            )
            while (selected.isNotEmpty()) {
                val fits = promptSources.all { source ->
                    LlamaPromptMetrics.countUserPromptTokens(
                        runtimePrompt(buildUserPrompt(source, candidate), candidate)
                    ) <= maxPromptTokens
                }
                if (fits) break
                selected.removeAt(0)
                candidate = settings.copy(
                    runtimeTranslationPromptContext = context.copy(previousFrame = selected),
                )
            }
            Timber.tag(PERF_TAG).i(
                "continuous history budget kind=%s capacity=%d maxPrompt=%d basePrompt=%d turns=%d/%d trimmed=%d",
                modelKind.name,
                capacity,
                maxPromptTokens,
                basePromptTokens,
                selected.size,
                context.previousFrame.size,
                context.previousFrame.size - selected.size,
            )
            candidate
        }
    }

    protected sealed interface GeneratedOutputValidation {
        data object Accepted : GeneratedOutputValidation

        sealed interface Invalid : GeneratedOutputValidation {
            val reason: String
        }

        data class Retryable(
            override val reason: String,
            val fallbackText: String,
        ) : Invalid

        data class Rejected(override val reason: String) : Invalid
    }

    private data class ResolvedGeneratedOutput(
        val text: String?,
        val cacheable: Boolean,
    ) {
        companion object {
            val FAILED = ResolvedGeneratedOutput(text = null, cacheable = false)
        }
    }

    /** Engines with a final-output policy must not expose unvalidated partial generations. */
    protected open val bufferGeneratedOutputUntilValidated: Boolean = false

    protected open fun outputValidation(
        source: String,
        output: String,
        userPrompt: String,
        settings: Settings,
    ): GeneratedOutputValidation = GeneratedOutputValidation.Accepted

    /** Engine-specific, conservative output cleanup applied before validation and caching. */
    protected open fun normalizeGeneratedOutput(
        source: String,
        output: String,
        userPrompt: String,
        settings: Settings,
    ): String = output

    /** Returns a narrower one-shot recovery prompt, or null when this output must fail closed. */
    protected open fun outputRecoveryPrompt(
        source: String,
        rejectedUserPrompt: String,
        settings: Settings,
        rejection: GeneratedOutputValidation.Invalid,
    ): String? = null

    /** Per-sequence native line cap; zero keeps the engine's normal unlimited-line behavior. */
    protected open fun nativeBatchMaxOutputLines(
        source: String,
        userPrompt: String,
        settings: Settings,
    ): Int = 0

    /** Strict native limit markers are only safe for engines that reject them before publishing. */
    protected open val markNativeBatchLineLimitAsInvalid: Boolean = false

    internal val prewarmModelKind: LlmModelKind get() = modelKind

    internal fun isPrewarmModelInstalled(): Boolean = holder.isModelInstalled(modelKind)

    internal suspend fun prewarm(settings: Settings) {
        val startedAt = SystemClock.elapsedRealtime()
        var modelReadyAt = startedAt
        var outputPieces = 0
        holder.withEngineSession(modelKind, systemPrompt) { engine ->
            modelReadyAt = SystemClock.elapsedRealtime()
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

    override fun handlesTranslationFailureRetry(settings: Settings): Boolean = true

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
        fun publish(index: Int, text: String?, elapsedMs: Long) {
            if (index !in results.indices || emitted[index]) return
            emitted[index] = true
            onUpdate(
                BatchTranslationUpdate(
                    index = index,
                    text = text,
                    elapsedMs = elapsedMs.coerceAtLeast(0L),
                )
            )
        }
        val pendingByKey = linkedMapOf<String, BatchPending>()
        var cacheHits = 0
        sources.forEachIndexed { index, source ->
            if (source.isBlank()) {
                publish(index, null, 0L)
                return@forEachIndexed
            }
            val individualPrompt = runtimePrompt(buildUserPrompt(source, settings), settings)
            val key = cacheKey(source, settings, individualPrompt)
            val cached = cache.get(key, settings)?.let { value ->
                validatedCachedOutput(
                    source = source,
                    userPrompt = individualPrompt,
                    cached = value,
                    cacheKey = key,
                    settings = settings,
                )
            }
            if (cached != null) {
                results[index] = cached
                cacheHits += 1
                publish(index, cached, 0L)
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
        holder.withEngineSession(modelKind, systemPrompt) { engine ->
            val sessionReadyAt = SystemClock.elapsedRealtime()
            val modelReadyMs = InferenceTiming.elapsedMs(modelReadyStartedAt, sessionReadyAt)
            val queuedAt = sessionReadyAt
            val pending = pendingByKey.values.filter { item ->
                val cached = cache.get(item.cacheKey, settings)?.let { value ->
                    validatedCachedOutput(
                        source = item.source,
                        userPrompt = item.individualPrompt,
                        cached = value,
                        cacheKey = item.cacheKey,
                        settings = settings,
                    )
                }
                if (cached == null) {
                    true
                } else {
                    item.resultIndexes.forEach {
                        results[it] = cached
                        publish(it, cached, 0L)
                    }
                    cacheHits += item.resultIndexes.size
                    false
                }
            }
            if (pending.isEmpty()) return@withEngineSession

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
                effectivePromptTokenCount = { group ->
                    LlamaPromptMetrics.effectiveUserPromptBatchTokens(
                        group.map { item -> item.individualPrompt }.toTypedArray(),
                    )
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

            val outputRecoveries = mutableListOf<BatchOutputRecovery>()
            var firstRequest = true
            plans.forEachIndexed { groupIndex, plan ->
                val requestQueuedAt = if (firstRequest) queuedAt else SystemClock.elapsedRealtime()
                val requestModelReadyMs = if (firstRequest) modelReadyMs else 0L
                firstRequest = false
                if (!plan.nativeBatch) {
                    val item = plan.items.single()
                    val itemStartedAt = SystemClock.elapsedRealtime()
                    val translated = generateLocked(
                        engine = engine,
                        userPrompt = item.individualPrompt,
                        predictLength = settings.localLlmMaxNewTokens,
                        mode = "native-single",
                        sourceForLog = item.source,
                        modelReadyMs = requestModelReadyMs,
                        queuedAt = requestQueuedAt,
                    )
                    acceptBatchResultOrQueueRecovery(
                        item = item,
                        translated = translated,
                        results = results,
                        settings = settings,
                        elapsedMs = InferenceTiming.elapsedMs(
                            itemStartedAt,
                            SystemClock.elapsedRealtime(),
                        ),
                        publish = ::publish,
                        recoveries = outputRecoveries,
                    )
                    return@forEachIndexed
                }

                val startedAt = SystemClock.elapsedRealtime()
                val lineCaps = plan.items.map { item ->
                    nativeBatchMaxOutputLines(
                        source = item.source,
                        userPrompt = item.individualPrompt,
                        settings = settings,
                    ).coerceAtLeast(0)
                }
                val outputs = LlamaMultiSequence.generate(
                    prompts = plan.items.map { it.individualPrompt },
                    predictLengths = List(plan.items.size) { settings.localLlmMaxNewTokens },
                    maxOutputLines = lineCaps,
                    markLimitAsInvalid = markNativeBatchLineLimitAsInvalid && lineCaps.any { it > 0 },
                )?.map { output -> output.trim().ifBlank { null } }
                val finishedAt = SystemClock.elapsedRealtime()
                Timber.tag(PERF_TAG).i(
                    "native batch result kind=%s group=%d/%d B=%d promptTokens=%d " +
                        "decodedPromptTokens=%d requiredKv=%d lineCaps=%s " +
                        "modelReadyMs=%d queueMs=%d totalMs=%d success=%s",
                    modelKind.name,
                    groupIndex + 1,
                    plans.size,
                    plan.items.size,
                    plan.promptTokens,
                    plan.decodedPromptTokens,
                    plan.requiredKvTokens,
                    lineCaps,
                    requestModelReadyMs,
                    InferenceTiming.elapsedMs(requestQueuedAt, startedAt),
                    InferenceTiming.elapsedMs(startedAt, finishedAt),
                    outputs != null,
                )
                if (outputs != null) {
                    val groupElapsedMs = InferenceTiming.elapsedMs(startedAt, finishedAt)
                    plan.items.zip(outputs).forEach { (item, translated) ->
                        acceptBatchResultOrQueueRecovery(
                            item = item,
                            translated = translated,
                            results = results,
                            settings = settings,
                            elapsedMs = groupElapsedMs,
                            publish = ::publish,
                            recoveries = outputRecoveries,
                        )
                    }
                } else {
                    Timber.tag(PERF_TAG).w(
                        "native batch fallback kind=%s group=%d items=%d reason=native_failure",
                        modelKind.name,
                        groupIndex + 1,
                        plan.items.size,
                    )
                    plan.items.forEach { item ->
                        val itemStartedAt = SystemClock.elapsedRealtime()
                        val translated = generateLocked(
                            engine = engine,
                            userPrompt = item.individualPrompt,
                            predictLength = settings.localLlmMaxNewTokens,
                            mode = "native-fallback",
                            sourceForLog = item.source,
                            modelReadyMs = 0L,
                            queuedAt = SystemClock.elapsedRealtime(),
                        )
                        acceptBatchResultOrQueueRecovery(
                            item = item,
                            translated = translated,
                            results = results,
                            settings = settings,
                            elapsedMs = InferenceTiming.elapsedMs(
                                itemStartedAt,
                                SystemClock.elapsedRealtime(),
                            ),
                            publish = ::publish,
                            recoveries = outputRecoveries,
                        )
                    }
                }
            }
            outputRecoveries.forEach { recovery ->
                val recoveryStartedAt = SystemClock.elapsedRealtime()
                val translated = recoverRejectedOutputLocked(
                    engine = engine,
                    source = recovery.item.source,
                    rejectedUserPrompt = recovery.item.individualPrompt,
                    rejection = recovery.rejection,
                    settings = settings,
                )
                applyBatchResult(
                    item = recovery.item,
                    translated = translated.text,
                    results = results,
                    settings = settings,
                    elapsedMs = recovery.initialElapsedMs + InferenceTiming.elapsedMs(
                        recoveryStartedAt,
                        SystemClock.elapsedRealtime(),
                    ),
                    publish = ::publish,
                    cacheable = translated.cacheable,
                )
            }
            holder.touch()
        }
        return results
    }

    override suspend fun translate(source: String, settings: Settings): String? {
        if (source.isBlank()) return null
        val userPrompt = runtimePrompt(buildUserPrompt(source, settings), settings)
        val cacheKey = cacheKey(source, settings, userPrompt)
        cache.get(cacheKey, settings)?.let { cached ->
            validatedCachedOutput(source, userPrompt, cached, cacheKey, settings)
        }?.let {
            Timber.tag(PERF_TAG).i("cache hit kind=%s mode=full inputChars=%d", modelKind.name, source.length)
            return it
        }
        val modelReadyStartedAt = SystemClock.elapsedRealtime()
        return holder.withEngineSession(modelKind, systemPrompt) { engine ->
            val sessionReadyAt = SystemClock.elapsedRealtime()
            val modelReadyMs = InferenceTiming.elapsedMs(modelReadyStartedAt, sessionReadyAt)
            val queuedAt = sessionReadyAt
            val startedAt = SystemClock.elapsedRealtime()
            cache.get(cacheKey, settings)?.let { cached ->
                validatedCachedOutput(source, userPrompt, cached, cacheKey, settings)
            }?.let {
                Timber.tag(PERF_TAG).i(
                    "cache hit kind=%s mode=full afterQueueMs=%d inputChars=%d",
                    modelKind.name,
                    InferenceTiming.elapsedMs(queuedAt, startedAt),
                    source.length,
                )
                return@withEngineSession it
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
            val generated = sb.toString().trim().ifBlank { null }
            val resolved = validateAndRecoverOutputLocked(
                engine = engine,
                source = source,
                userPrompt = userPrompt,
                generated = generated,
                settings = settings,
            )
            resolved.text?.also { text ->
                if (resolved.cacheable) cache.put(cacheKey, text, settings)
            }
        }
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        if (source.isBlank()) return@flow
        val userPrompt = runtimePrompt(buildUserPrompt(source, settings), settings)
        val cacheKey = cacheKey(source, settings, userPrompt)
        cache.get(cacheKey, settings)?.let { cached ->
            validatedCachedOutput(source, userPrompt, cached, cacheKey, settings)
        }?.let { cached ->
            Timber.tag(PERF_TAG).i("cache hit kind=%s mode=stream inputChars=%d", modelKind.name, source.length)
            emit(cached)
            return@flow
        }
        val modelReadyStartedAt = SystemClock.elapsedRealtime()
        holder.withEngineSession(modelKind, systemPrompt) { engine ->
            val sessionReadyAt = SystemClock.elapsedRealtime()
            val modelReadyMs = InferenceTiming.elapsedMs(modelReadyStartedAt, sessionReadyAt)
            val queuedAt = sessionReadyAt
            val startedAt = SystemClock.elapsedRealtime()
            cache.get(cacheKey, settings)?.let { cached ->
                validatedCachedOutput(source, userPrompt, cached, cacheKey, settings)
            }?.let { cached ->
                Timber.tag(PERF_TAG).i(
                    "cache hit kind=%s mode=stream afterQueueMs=%d inputChars=%d",
                    modelKind.name,
                    InferenceTiming.elapsedMs(queuedAt, startedAt),
                    source.length,
                )
                emit(cached)
                return@withEngineSession
            }
            val sb = StringBuilder()
            var firstOutputAt: Long? = null
            var outputPieces = 0
            engine.sendUserPrompt(userPrompt, settings.localLlmMaxNewTokens)
                .collect { token ->
                    if (firstOutputAt == null) firstOutputAt = SystemClock.elapsedRealtime()
                    outputPieces++
                    sb.append(token)
                    if (!bufferGeneratedOutputUntilValidated) emit(sb.toString())
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
            val generated = sb.toString().trim().ifBlank { null }
            val resolved = validateAndRecoverOutputLocked(
                engine = engine,
                source = source,
                userPrompt = userPrompt,
                generated = generated,
                settings = settings,
            )
            if (bufferGeneratedOutputUntilValidated) resolved.text?.let { emit(it) }
            resolved.text?.let { text ->
                if (resolved.cacheable) cache.put(cacheKey, text, settings)
            }
        }
    }

    protected data class GenerationResult(
        val text: String?,
        val outputPieces: Int,
        val hitTokenLimit: Boolean,
    )

    protected data class UncachedGenerationRequest(
        val userPrompt: String,
        val sourceForLog: String,
        val mode: String,
        val predictLength: Int,
        val maxOutputLines: Int = 0,
    )

    protected suspend fun generateUncached(
        userPrompt: String,
        sourceForLog: String,
        settings: Settings,
        mode: String,
    ): String? = generateUncachedResult(userPrompt, sourceForLog, settings, mode).text

    protected suspend fun generateUncachedResult(
        userPrompt: String,
        sourceForLog: String,
        settings: Settings,
        mode: String,
        predictLength: Int = settings.localLlmMaxNewTokens,
    ): GenerationResult {
        val modelReadyStartedAt = SystemClock.elapsedRealtime()
        return holder.withEngineSession(modelKind, systemPrompt) { engine ->
            val sessionReadyAt = SystemClock.elapsedRealtime()
            val modelReadyMs = InferenceTiming.elapsedMs(modelReadyStartedAt, sessionReadyAt)
            val queuedAt = sessionReadyAt
            generateLockedResult(
                engine = engine,
                userPrompt = runtimePrompt(userPrompt, settings),
                predictLength = predictLength,
                mode = mode,
                sourceForLog = sourceForLog,
                modelReadyMs = modelReadyMs,
                queuedAt = queuedAt,
            ).also { holder.touch() }
        }
    }

    /**
     * Generates one or more independent requests in the same native decode loop. Strict limit
     * markers are only used by callers that already reject structurally truncated output.
     */
    protected suspend fun generateUncachedBatchResults(
        requests: List<UncachedGenerationRequest>,
        settings: Settings,
        markLimitAsInvalid: Boolean,
    ): List<GenerationResult>? {
        if (requests.isEmpty()) return emptyList()
        val modelReadyStartedAt = SystemClock.elapsedRealtime()
        return holder.withEngineSession(modelKind, systemPrompt) {
            val sessionReadyAt = SystemClock.elapsedRealtime()
            val startedAt = SystemClock.elapsedRealtime()
            val outputs = LlamaMultiSequence.generate(
                prompts = requests.map { runtimePrompt(it.userPrompt, settings) },
                predictLengths = requests.map { it.predictLength.coerceAtLeast(1) },
                maxOutputLines = requests.map { it.maxOutputLines.coerceAtLeast(0) },
                markLimitAsInvalid = markLimitAsInvalid,
            ) ?: return@withEngineSession null
            val finishedAt = SystemClock.elapsedRealtime()
            Timber.tag(PERF_TAG).i(
                "native strict batch kind=%s B=%d modelReadyMs=%d totalMs=%d lineCaps=%s",
                modelKind.name,
                requests.size,
                InferenceTiming.elapsedMs(modelReadyStartedAt, sessionReadyAt),
                InferenceTiming.elapsedMs(startedAt, finishedAt),
                requests.map(UncachedGenerationRequest::maxOutputLines),
            )
            holder.touch()
            outputs.mapIndexed { index, raw ->
                val request = requests[index]
                val hitTokenLimit = raw.contains(LlamaMultiSequence.TOKEN_LIMIT_SENTINEL)
                GenerationResult(
                    text = raw.trim().ifBlank { null },
                    outputPieces = if (hitTokenLimit) request.predictLength else 0,
                    hitTokenLimit = hitTokenLimit,
                )
            }
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
    ): String? = generateLockedResult(
        engine = engine,
        userPrompt = userPrompt,
        predictLength = predictLength,
        mode = mode,
        sourceForLog = sourceForLog,
        modelReadyMs = modelReadyMs,
        queuedAt = queuedAt,
    ).text

    private suspend fun generateLockedResult(
        engine: InferenceEngine,
        userPrompt: String,
        predictLength: Int,
        mode: String,
        sourceForLog: String,
        modelReadyMs: Long,
        queuedAt: Long,
    ): GenerationResult {
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
        return GenerationResult(
            text = output.toString().trim().ifBlank { null },
            outputPieces = outputPieces,
            hitTokenLimit = outputPieces >= predictLength,
        )
    }

    private fun validatedCachedOutput(
        source: String,
        userPrompt: String,
        cached: String,
        cacheKey: String,
        settings: Settings,
    ): String? {
        val normalizedCached = normalizeGeneratedOutputIfNeeded(source, cached, userPrompt, settings)
        return when (val validation = outputValidation(source, normalizedCached, userPrompt, settings)) {
            GeneratedOutputValidation.Accepted -> normalizedCached
            is GeneratedOutputValidation.Invalid -> {
                cache.remove(cacheKey)
                logOutputPolicyRejection(
                    phase = "cache",
                    source = source,
                    output = normalizedCached,
                    rejection = validation,
                )
                null
            }
        }
    }

    private suspend fun validateAndRecoverOutputLocked(
        engine: InferenceEngine,
        source: String,
        userPrompt: String,
        generated: String?,
        settings: Settings,
    ): ResolvedGeneratedOutput {
        val normalizedGenerated = generated?.let {
            normalizeGeneratedOutputIfNeeded(source, it, userPrompt, settings)
        }
        if (normalizedGenerated == null) {
            if (!settings.retryFailedTranslation) return ResolvedGeneratedOutput.FAILED
            return recoverRejectedOutputLocked(
                engine = engine,
                source = source,
                rejectedUserPrompt = userPrompt,
                rejection = GeneratedOutputValidation.Rejected(reason = "EMPTY"),
                settings = settings,
            )
        }
        return when (
            val validation = outputValidation(source, normalizedGenerated, userPrompt, settings)
        ) {
            GeneratedOutputValidation.Accepted -> ResolvedGeneratedOutput(normalizedGenerated, cacheable = true)
            is GeneratedOutputValidation.Retryable -> {
                logOutputPolicyRejection(
                    phase = "initial-retryable",
                    source = source,
                    output = normalizedGenerated,
                    rejection = validation,
                )
                if (settings.retryFailedTranslation) {
                    recoverRejectedOutputLocked(
                        engine = engine,
                        source = source,
                        rejectedUserPrompt = userPrompt,
                        rejection = validation,
                        settings = settings,
                    )
                } else {
                    logPreservedSource(validation, source, phase = "initial")
                    ResolvedGeneratedOutput(validation.fallbackText, cacheable = false)
                }
            }
            is GeneratedOutputValidation.Rejected -> {
                logOutputPolicyRejection(
                    phase = "initial",
                    source = source,
                    output = normalizedGenerated,
                    rejection = validation,
                )
                if (settings.retryFailedTranslation) {
                    recoverRejectedOutputLocked(
                        engine = engine,
                        source = source,
                        rejectedUserPrompt = userPrompt,
                        rejection = validation,
                        settings = settings,
                    )
                } else {
                    Timber.tag(PERF_TAG).i(
                        "output policy retry skipped kind=%s reason=%s inputChars=%d setting=disabled",
                        modelKind.name,
                        validation.reason,
                        source.length,
                    )
                    ResolvedGeneratedOutput.FAILED
                }
            }
        }
    }

    private fun acceptBatchResultOrQueueRecovery(
        item: BatchPending,
        translated: String?,
        results: MutableList<String?>,
        settings: Settings,
        elapsedMs: Long,
        publish: (Int, String?, Long) -> Unit,
        recoveries: MutableList<BatchOutputRecovery>,
    ) {
        val normalizedTranslated = translated?.let {
            normalizeGeneratedOutputIfNeeded(item.source, it, item.individualPrompt, settings)
        }
        if (normalizedTranslated == null) {
            if (settings.retryFailedTranslation) {
                recoveries += BatchOutputRecovery(
                    item = item,
                    rejection = GeneratedOutputValidation.Rejected(reason = "EMPTY"),
                    initialElapsedMs = elapsedMs,
                )
            } else {
                applyBatchResult(item, null, results, settings, elapsedMs, publish)
            }
            return
        }
        when (val validation = outputValidation(
            item.source,
            normalizedTranslated,
            item.individualPrompt,
            settings,
        )) {
            GeneratedOutputValidation.Accepted ->
                applyBatchResult(item, normalizedTranslated, results, settings, elapsedMs, publish)
            is GeneratedOutputValidation.Retryable -> {
                logOutputPolicyRejection(
                    phase = "batch-initial-retryable",
                    source = item.source,
                    output = normalizedTranslated,
                    rejection = validation,
                )
                if (settings.retryFailedTranslation) {
                    recoveries += BatchOutputRecovery(
                        item = item,
                        rejection = validation,
                        initialElapsedMs = elapsedMs,
                    )
                } else {
                    logPreservedSource(validation, item.source, phase = "batch-initial")
                    applyBatchResult(
                        item = item,
                        translated = validation.fallbackText,
                        results = results,
                        settings = settings,
                        elapsedMs = elapsedMs,
                        publish = publish,
                        cacheable = false,
                    )
                }
            }
            is GeneratedOutputValidation.Rejected -> {
                logOutputPolicyRejection(
                    phase = "batch-initial",
                    source = item.source,
                    output = normalizedTranslated,
                    rejection = validation,
                )
                if (settings.retryFailedTranslation) {
                    recoveries += BatchOutputRecovery(
                        item = item,
                        rejection = validation,
                        initialElapsedMs = elapsedMs,
                    )
                } else {
                    applyBatchResult(item, null, results, settings, elapsedMs, publish)
                }
            }
        }
    }

    private suspend fun recoverRejectedOutputLocked(
        engine: InferenceEngine,
        source: String,
        rejectedUserPrompt: String,
        rejection: GeneratedOutputValidation.Invalid,
        settings: Settings,
    ): ResolvedGeneratedOutput {
        val recoveryPrompt = outputRecoveryPrompt(
            source = source,
            rejectedUserPrompt = rejectedUserPrompt,
            settings = settings,
            rejection = rejection,
        )?.let { runtimePrompt(it, settings) }
        if (recoveryPrompt == null) {
            Timber.tag(PERF_TAG).w(
                "output policy fail-closed kind=%s reason=%s inputChars=%d recovery=unavailable",
                modelKind.name,
                rejection.reason,
                source.length,
            )
            return terminalFallback(rejection, source, phase = "recovery-unavailable")
        }

        val rawRecovered = generateLocked(
            engine = engine,
            userPrompt = recoveryPrompt,
            predictLength = settings.localLlmMaxNewTokens,
            mode = "output-policy-retry",
            sourceForLog = source,
            modelReadyMs = 0L,
            queuedAt = SystemClock.elapsedRealtime(),
        ) ?: return terminalFallback(rejection, source, phase = "recovery-empty")
        val recovered = normalizeGeneratedOutputIfNeeded(
            source = source,
            output = rawRecovered,
            userPrompt = recoveryPrompt,
            settings = settings,
        )
        return when (val retryValidation = outputValidation(source, recovered, recoveryPrompt, settings)) {
            GeneratedOutputValidation.Accepted -> {
                Timber.tag(PERF_TAG).i(
                    "output policy recovered kind=%s reason=%s inputChars=%d outputChars=%d",
                    modelKind.name,
                    rejection.reason,
                    source.length,
                    recovered.length,
                )
                ResolvedGeneratedOutput(recovered, cacheable = true)
            }
            is GeneratedOutputValidation.Retryable -> {
                logOutputPolicyRejection(
                    phase = "retry-retryable",
                    source = source,
                    output = recovered,
                    rejection = retryValidation,
                )
                terminalFallback(retryValidation, source, phase = "retry")
            }
            is GeneratedOutputValidation.Rejected -> {
                logOutputPolicyRejection(
                    phase = "retry",
                    source = source,
                    output = recovered,
                    rejection = retryValidation,
                )
                ResolvedGeneratedOutput.FAILED
            }
        }
    }

    private fun terminalFallback(
        validation: GeneratedOutputValidation.Invalid,
        source: String,
        phase: String,
    ): ResolvedGeneratedOutput = when (validation) {
        is GeneratedOutputValidation.Retryable -> {
            logPreservedSource(validation, source, phase)
            ResolvedGeneratedOutput(validation.fallbackText, cacheable = false)
        }
        is GeneratedOutputValidation.Rejected -> ResolvedGeneratedOutput.FAILED
    }

    private fun logPreservedSource(
        validation: GeneratedOutputValidation.Retryable,
        source: String,
        phase: String,
    ) {
        Timber.tag(PERF_TAG).i(
            "output policy preserve source kind=%s phase=%s reason=%s inputChars=%d cacheable=false",
            modelKind.name,
            phase,
            validation.reason,
            source.length,
        )
    }

    private fun logOutputPolicyRejection(
        phase: String,
        source: String,
        output: String,
        rejection: GeneratedOutputValidation.Invalid,
    ) {
        Timber.tag(PERF_TAG).w(
            "output policy rejected kind=%s phase=%s reason=%s inputChars=%d outputChars=%d",
            modelKind.name,
            phase,
            rejection.reason,
            source.length,
            output.length,
        )
    }

    private fun normalizeGeneratedOutputIfNeeded(
        source: String,
        output: String,
        userPrompt: String,
        settings: Settings,
    ): String {
        val normalized = normalizeGeneratedOutput(source, output, userPrompt, settings)
        if (normalized != output) {
            Timber.tag(PERF_TAG).i(
                "output policy normalized kind=%s inputChars=%d rawOutputChars=%d outputChars=%d",
                modelKind.name,
                source.length,
                output.length,
                normalized.length,
            )
        }
        return normalized
    }

    private fun applyBatchResult(
        item: BatchPending,
        translated: String?,
        results: MutableList<String?>,
        settings: Settings,
        elapsedMs: Long,
        publish: (Int, String?, Long) -> Unit,
        cacheable: Boolean = true,
    ) {
        translated?.let { if (cacheable) cache.put(item.cacheKey, it, settings) }
        localLlmBatchResultUpdates(item.resultIndexes, translated, elapsedMs).forEach { update ->
            results[update.index] = update.text
            publish(update.index, update.text, update.elapsedMs ?: elapsedMs)
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

    protected open fun runtimePrompt(basePrompt: String, settings: Settings): String =
        if (settings.runtimeTranslationContext.isBlank()) {
            basePrompt
        } else {
            settings.runtimeTranslationContext + "\n\n" + basePrompt
        }

    override suspend fun testConnection(settings: Settings): TestResult = runCatching {
        if (!holder.isDeviceCapable()) {
            return@runCatching TestResult(success = false, message = "Android 13+ required")
        }
        holder.withEngineSession(modelKind, systemPrompt) { }
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

    private data class BatchOutputRecovery(
        val item: BatchPending,
        val rejection: GeneratedOutputValidation.Invalid,
        val initialElapsedMs: Long,
    )

    companion object {
        private const val PERF_TAG = "LocalLlmPerf"
        private const val PREWARM_SOURCE = "こんにちは"
        private const val PREWARM_PREDICT_LENGTH = 1
        private const val CONTINUOUS_CONTEXT_HEADROOM_TOKENS = 64
    }
}
