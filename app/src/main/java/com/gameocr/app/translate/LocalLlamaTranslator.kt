package com.gameocr.app.translate

import android.os.SystemClock
import com.gameocr.app.data.Settings
import com.gameocr.app.llm.LlamaEngineHolder
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
 * binding 把 temperature / top_p / top_k 写死在 JNI 层。Settings 里的 localLlm* 字段当前不
 * 实际生效，是给未来 binding 升级预留。灰度期若发现 HY-MT 输出过于发散，需要去
 * `third_party/llama.cpp/examples/llama.android/lib/src/main/cpp/ai_chat.cpp` 把
 * common_sampler_params 暴露上来。
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

    override val prefersBatch: Boolean get() = false

    override suspend fun translate(source: String, settings: Settings): String? {
        if (source.isBlank()) return null
        val userPrompt = runtimePrompt(buildUserPrompt(source, settings), settings)
        val cacheKey = cacheKey(source, settings, userPrompt)
        cache.get(cacheKey)?.let {
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
            cache.get(cacheKey)?.let {
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
            sb.toString().trim().ifBlank { null }?.also { cache.put(cacheKey, it) }
        }
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        if (source.isBlank()) return@flow
        val userPrompt = runtimePrompt(buildUserPrompt(source, settings), settings)
        val cacheKey = cacheKey(source, settings, userPrompt)
        cache.get(cacheKey)?.let { cached ->
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
            cache.get(cacheKey)?.let { cached ->
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
            sb.toString().trim().takeIf { it.isNotBlank() }?.let { cache.put(cacheKey, it) }
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

    companion object {
        private const val PERF_TAG = "LocalLlmPerf"
    }
}
