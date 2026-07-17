package com.gameocr.app.llm

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.Os
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.gameocr.app.BuildConfig
import com.gameocr.app.R
import com.gameocr.app.util.CpuThreadPolicy
import com.gameocr.app.util.InferenceTiming
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * 端侧 llama.cpp 推理引擎共享层。**整个 App 进程内最多持有一个 InferenceEngine 实例**，
 * 在 [HunyuanMtTranslator] 与 [SakuraGalTranslator] 之间复用，切换模型时先 cleanUp 再 loadModel。
 *
 * 内存策略（与 Plan 一致）：
 * - **Background prewarm**：选择已安装的本地翻译模型时，悬浮翻译 Service 启动后调用
 *   [ensureLoaded]；若预热尚未完成，首次翻译复用同一把初始化锁等待，不会重复加载；
 * - **Idle unload**：每次 [touch] 重置 5 分钟倒计时，到点自动 [unload]，释放 ~440-1024 MB；
 * - 引擎实例本身不 [InferenceEngine.destroy]（destroy 后无法再次 loadModel），只 cleanUp 卸权重。
 *
 * 设备能力门槛（[isDeviceCapable]）：上游 examples/llama.android/lib 的 minSdk=33，
 * Android 13 以下设备一律不允许进入 LOCAL_* 翻译引擎。屏译 :app minSdk=26 由
 * AndroidManifest 的 `<uses-sdk tools:overrideLibrary="com.arm.aichat" />` 抑制 merge 报错。
 *
 * 设计参考 [com.gameocr.app.ocr.PaddleOcrEngine] 的 Mutex + ensureReady 模式。
 */
@Singleton
class LlamaEngineHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: LlmModelInstaller,
) {

    private val initLock = Mutex()
    /**
     * 推理串行锁。binding 的 [InferenceEngine.sendUserPrompt] 一次只能处理一段：
     * 进入时 state 必须是 ModelReady，否则丢弃并抛 `User prompt discarded due to: <state>`。
     * 屏译一帧 OCR 出多段，[Translator.translateBatch] 默认并发触发 N 个 translate，
     * 端侧 LLM 必须强制串行，否则第 2 段起全部丢弃 + 让 engine 进入 Error。
     */
    val inferenceMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loadedKind: LlmModelKind? = null
    @Volatile private var lastUseAt: Long = 0L
    private var idleJob: Job? = null

    /** 设备 API level 是否满足 binding 要求（Android 13+）。 */
    fun isDeviceCapable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    val currentKind: LlmModelKind? get() = loadedKind

    fun isModelInstalled(kind: LlmModelKind): Boolean = installer.checkInstalled(kind) != null

    /**
     * 确保指定 [kind] 的模型已加载到 engine 中，必要时切换。
     *
     * 状态自愈：上一次 loadModel 失败会让 [InferenceEngine] 进入 [InferenceEngine.State.Error]，
     * 之后所有 loadModel 调用都会被 binding 内的 `check(state is Initialized)` 拒掉
     * （日志表现为大量 `IllegalStateException: Cannot load model in Error!`）。本方法在每次
     * 加载前先检查 state——若是 Error 就调 [InferenceEngine.cleanUp]（binding 实现里允许从 Error
     * 回到 Initialized），让用户切换 chip 或重试翻译时能脱困。
     *
     * **System prompt 一次性设置**：binding 的 `setSystemPrompt` 必须在 loadModel **之后立即**
     * 调用、且只能一次（`_readyForSystemPrompt` 用完即弃）；之后任何 setSystemPrompt 都抛
     * `IllegalStateException: System prompt must be set ** RIGHT AFTER ** model loaded!`。
     * 所以我们让上层（[com.gameocr.app.translate.LocalLlamaTranslator]）传入 [systemPrompt]，
     * 本方法在 loadModel 成功后立刻设一次；后续翻译只调 sendUserPrompt，不再碰 setSystemPrompt。
     *
     * @throws LlmModelNotReadyException 模型文件未下载/不完整，或 native 层判定文件不可加载
     *         （GGUF 损坏 / 量化格式不支持 / 架构不在 llama.cpp 白名单）。UI 层应跳转到模型下载入口。
     */
    suspend fun ensureLoaded(kind: LlmModelKind, systemPrompt: String? = null): InferenceEngine = initLock.withLock {
        if (!isDeviceCapable()) {
            throw LlmModelNotReadyException(
                context.getString(R.string.err_llm_device_unsupported)
            )
        }

        val current = engine
        // 同 kind fast path：仅当 state 健康（非 Error）时复用。否则走下方 cleanUp + reload 自愈，
        // 避免上次 sendUserPrompt 失败让 engine 卡在 Error，后续 translate 全报
        // "User prompt discarded due to: Error"。
        if (current != null && loadedKind == kind && current.state.value !is InferenceEngine.State.Error) {
            touchInternal()
            Timber.tag(PERF_TAG).i("model reuse kind=%s state=%s", kind.name, current.state.value.javaClass.simpleName)
            return@withLock current
        }

        val modelFile = installer.checkInstalled(kind)
            ?: throw LlmModelNotReadyException(
                context.getString(R.string.err_llm_not_ready, kind.displayName)
            )

        val availableProcessors = CpuThreadPolicy.availableProcessors()
        val threadConfig = LocalLlmThreadPolicy.select(
            availableProcessors = availableProcessors,
            requestedGenerationThreads = BuildConfig.LOCAL_LLM_GENERATION_THREADS,
        )
        Timber.tag(PERF_TAG).i(
            "thread policy availableProcessors=%d TG=%d PP=%d requestedTG=%d max=%d",
            availableProcessors,
            threadConfig.generationThreads,
            threadConfig.promptThreads,
            BuildConfig.LOCAL_LLM_GENERATION_THREADS,
            CpuThreadPolicy.MAX_INFERENCE_THREADS,
        )
        val totalStartedAt = SystemClock.elapsedRealtime()
        val engineStartedAt = SystemClock.elapsedRealtime()
        val requestedVulkan = LocalLlmAccelerationPolicy.requestsVulkan(kind)
        val samplingConfig = LocalLlmSamplingPolicy.forModel(kind)
        runCatching {
            Os.setenv(
                LocalLlmAccelerationPolicy.VULKAN_OFFLOAD_ENV,
                LocalLlmAccelerationPolicy.nativeFlag(kind),
                true,
            )
            LocalLlmSamplingPolicy.nativeEnvironment(kind).forEach { (name, value) ->
                Os.setenv(name, value, true)
            }
            LocalLlmThreadPolicy.nativeEnvironment(threadConfig).forEach { (name, value) ->
                Os.setenv(name, value, true)
            }
        }.onFailure {
            Timber.tag(PERF_TAG).e(it, "failed to configure native LLM policy kind=%s", kind.name)
        }
        Timber.tag(PERF_TAG).i(
            "native policy kind=%s requestedVulkan=%s temperature=%.2f topP=%.2f frequencyPenalty=%.2f",
            kind.name,
            requestedVulkan,
            samplingConfig.temperature,
            samplingConfig.topP,
            samplingConfig.frequencyPenalty,
        )
        val e = current ?: AiChat.getInferenceEngine(context)
        val engineAcquireMs = InferenceTiming.elapsedMs(engineStartedAt, SystemClock.elapsedRealtime())

        // 状态机救援：上一次 loadModel 失败让 engine 卡在 Error；cleanUp() 把它拉回 Initialized。
        // 切换 kind 时也走 cleanUp 卸掉旧权重（同一 engine 不能并存两份）。
        val st = e.state.value
        if (st is InferenceEngine.State.Error) {
            Timber.w(
                "LlamaEngineHolder previous engine in Error (${st.exception.javaClass.simpleName}: " +
                    "${st.exception.message}); cleanUp() to retry."
            )
            runCatching { e.cleanUp() }
                .onFailure { Timber.w(it, "cleanUp from Error failed") }
        } else if (current != null && loadedKind != null) {
            Timber.i("LlamaEngineHolder switch: $loadedKind -> $kind")
            runCatching { e.cleanUp() }
                .onFailure { Timber.w(it, "cleanUp before switch failed") }
        }

        try {
            val modelStartedAt = SystemClock.elapsedRealtime()
            e.loadModel(modelFile.absolutePath)
            val modelLoadMs = InferenceTiming.elapsedMs(modelStartedAt, SystemClock.elapsedRealtime())
            // 立即喂 system prompt——binding 的 setSystemPrompt 只接受 loadModel 之后唯一一次窗口。
            // 这里 catch 单独包装，避免 "system prompt set 失败 → 抛出后 engine 仍 ModelReady" 让用户
            // 误以为模型坏了。空 prompt 直接跳过。
            var systemPromptMs = 0L
            if (!systemPrompt.isNullOrBlank()) {
                val systemPromptStartedAt = SystemClock.elapsedRealtime()
                runCatching { e.setSystemPrompt(systemPrompt) }
                    .onFailure {
                        Timber.w(it, "setSystemPrompt failed for kind=$kind; continuing without system prompt")
                    }
                systemPromptMs = InferenceTiming.elapsedMs(systemPromptStartedAt, SystemClock.elapsedRealtime())
            }
            Timber.tag(PERF_TAG).i(
                "model load kind=%s totalMs=%d engineAcquireMs=%d modelLoadMs=%d systemPromptMs=%d " +
                    "fileMb=%d reusedEngine=%s TG=%d PP=%d requestedVulkan=%s",
                kind.name,
                InferenceTiming.elapsedMs(totalStartedAt, SystemClock.elapsedRealtime()),
                engineAcquireMs,
                modelLoadMs,
                systemPromptMs,
                modelFile.length() / 1024 / 1024,
                current != null,
                threadConfig.generationThreads,
                threadConfig.promptThreads,
                requestedVulkan,
            )
        } catch (uae: UnsupportedArchitectureException) {
            // binding 把所有 "load 返回非 0" 统统包成 UnsupportedArchitectureException，message 永远是 null。
            // 实际可能原因：GGUF 损坏 / 量化格式 llama.cpp 不识别 / 架构不在白名单 / 内存不够。
            // 把诊断上下文打到 Timber（用户在「日志」页能看到），并抛更具体的 NotReady 异常。
            Timber.e(
                uae,
                "loadModel failed: kind=$kind file=${modelFile.absolutePath} size=${modelFile.length() / 1024 / 1024}MB. " +
                    "binding 拒识 GGUF —— 可能是文件损坏、量化格式不支持、架构白名单不含此模型，或设备内存不足。"
            )
            throw LlmModelNotReadyException(
                "${kind.displayName} 加载失败：当前 llama.cpp 不识别该 GGUF（${modelFile.length() / 1024 / 1024} MB）。" +
                    "可能原因：量化格式 / 架构 / 文件损坏。详见日志页 native 错误。"
            )
        }
        engine = e
        loadedKind = kind
        touchInternal()
        Timber.i(
            "LlamaEngineHolder loaded kind=$kind file=${modelFile.name} size=${modelFile.length() / 1024 / 1024}MB"
        )
        e
    }

    /** 每次翻译触发后调一次：刷新 idle 计时器。 */
    fun touch() = touchInternal()

    private fun touchInternal() {
        lastUseAt = SystemClock.elapsedRealtime()
        scheduleIdleUnload()
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = ioScope.launch {
            delay(IDLE_TIMEOUT_MS)
            // 校验真实空闲时长，避免在 delay 期间又有 touch 误触发 unload。
            val idle = SystemClock.elapsedRealtime() - lastUseAt
            if (idle >= IDLE_TIMEOUT_MS) {
                Timber.i("LlamaEngineHolder idle ${idle}ms, unloading")
                unload()
            }
        }
    }

    /** 主动卸载模型权重，释放 ~440-1024 MB 物理内存。engine 实例保留以便下次 load。 */
    suspend fun unload() = initLock.withLock {
        val e = engine ?: return@withLock
        val previousKind = loadedKind
        e.cleanUp()
        loadedKind = null
        idleJob?.cancel()
        idleJob = null
        Timber.i("LlamaEngineHolder unloaded kind=$previousKind")
    }

    companion object {
        private const val PERF_TAG = "LocalLlmPerf"
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
