package com.gameocr.app.llm

import android.content.Context
import android.os.SystemClock
import android.system.Os
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.gameocr.app.BuildConfig
import com.gameocr.app.R
import com.gameocr.app.data.Settings
import com.gameocr.app.util.CpuThreadPolicy
import com.gameocr.app.util.InferenceTiming
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - **Idle unload**：按模型设置在任务结束后重新计时，0 表示不自动释放；
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
    private val deviceCapability: LocalLlmDeviceCapability,
    private val modelCatalog: LocalModelCatalog,
) {

    private val initLock = Mutex()
    /**
     * 推理串行锁。binding 的 [InferenceEngine.sendUserPrompt] 一次只能处理一段：
     * 进入时 state 必须是 ModelReady，否则丢弃并抛 `User prompt discarded due to: <state>`。
     * 屏译一帧 OCR 出多段，[Translator.translateBatch] 默认并发触发 N 个 translate，
     * 端侧 LLM 必须强制串行，否则第 2 段起全部丢弃 + 让 engine 进入 Error。
     * 同一把锁也覆盖 Token 统计、模型切换和卸载，保护 binding 的进程级 native 状态。
     */
    private val lifecycleGate = LlamaEngineLifecycleGate()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loadedKind: LlmModelKind? = null
    @Volatile private var loadedContextTokens: Int? = null
    @Volatile private var loadedConfigFingerprint: String? = null
    @Volatile private var loadedRuntimeFingerprint: String? = null
    @Volatile private var loadedSystemPrompt: String? = null
    @Volatile private var idleTimeoutMs: Long = IDLE_TIMEOUT_MS
    private val _currentModelId = MutableStateFlow<String?>(null)
    val currentModelId = _currentModelId.asStateFlow()
    @Volatile private var lastUseAt: Long = 0L
    private var idleJob: Job? = null

    /** 设备 API level 是否满足 binding 要求（Android 13+）。 */
    fun isDeviceCapable(): Boolean = deviceCapability.isSupported()

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
    private suspend fun ensureLoaded(kind: LlmModelKind, systemPrompt: String? = null,
        contextTokens: Int? = null, managed: LocalModelEntry? = null,
        config: LocalModelConfig? = null): InferenceEngine = initLock.withLock {
        if (!isDeviceCapable()) {
            throw LlmModelNotReadyException(
                context.getString(R.string.err_llm_device_unsupported)
            )
        }

        val current = engine
        val requestedId = managed?.id ?: LocalModelCatalog.builtInId(kind)
        // Imported GGUFs run through the generic preset kind; never show or tune them as that preset.
        val imported = managed != null && managed.kind == null
        val displayName = managed?.name ?: kind.displayName
        val value = config?.normalized()
        val fingerprint = value?.initializationFingerprint()
        val threadConfig = LocalLlmThreadPolicy.configured(
            CpuThreadPolicy.availableProcessors(), BuildConfig.LOCAL_LLM_GENERATION_THREADS, value,
        )
        val runtimeEnvironment = LocalLlmSamplingPolicy.nativeEnvironment(kind, value, imported) +
            LocalLlmThreadPolicy.nativeEnvironment(threadConfig)
        val runtimeFingerprint = runtimeEnvironment.entries.joinToString { "${it.key}=${it.value}" }
        // 同 kind fast path：仅当 state 健康（非 Error）时复用。否则走下方 cleanUp + reload 自愈，
        // 避免上次 sendUserPrompt 失败让 engine 卡在 Error，后续 translate 全报
        // "User prompt discarded due to: Error"。
        if (current != null && _currentModelId.value == requestedId && loadedContextTokens == contextTokens &&
            loadedConfigFingerprint == fingerprint && loadedSystemPrompt == systemPrompt &&
            current.state.value is InferenceEngine.State.ModelReady) {
            if (loadedRuntimeFingerprint != runtimeFingerprint) {
                configureEnvironment(runtimeEnvironment)
                Timber.tag(PERF_TAG).i("runtime policy updated without weight reload: %s",
                    LlamaTemplateGeneration.configureRuntime().joinToString(" "))
                loadedRuntimeFingerprint = runtimeFingerprint
            }
            idleTimeoutMs = (value?.idleUnloadMinutes ?: 5) * 60_000L
            touchInternal()
            Timber.tag(PERF_TAG).i("model reuse kind=%s state=%s", kind.name, current.state.value.javaClass.simpleName)
            return@withLock current
        }

        val modelFile = (if (managed != null) managed.file?.takeIf { it.isFile } else installer.checkInstalled(kind))
            ?: throw LlmModelNotReadyException(
                if (imported) context.getString(R.string.err_llm_imported_missing, displayName)
                else context.getString(R.string.err_llm_not_ready, displayName)
            )

        val availableProcessors = CpuThreadPolicy.availableProcessors()
        Timber.tag(PERF_TAG).i(
            "thread policy availableProcessors=%d TG=%d PP=%d requestedTG=%d max=%d",
            availableProcessors,
            threadConfig.generationThreads,
            threadConfig.promptThreads,
            BuildConfig.LOCAL_LLM_GENERATION_THREADS,
            availableProcessors.coerceIn(1, 64),
        )
        val totalStartedAt = SystemClock.elapsedRealtime()
        val engineStartedAt = SystemClock.elapsedRealtime()
        val requestedVulkan = value?.gpuLayers?.let { it != 0 } ?: LocalLlmAccelerationPolicy.requestsVulkan(kind)
        val samplingConfig = LocalLlmSamplingPolicy.configured(kind, config, imported)
        runCatching {
            LocalModelInitializationPolicy.nativeEnvironment(value ?: LocalModelConfig()).forEach { (name, setting) ->
                Os.setenv(name, setting, true)
            }
            Os.setenv("GAMEOCR_CONTEXT_TOKENS", (contextTokens ?: value?.contextTokens ?: 0).toString(), true)
            Os.setenv(
                LocalLlmAccelerationPolicy.VULKAN_OFFLOAD_ENV,
                if (requestedVulkan) "1" else "0",
                true,
            )
            runtimeEnvironment.forEach { (name, value) ->
                Os.setenv(name, value, true)
            }
        }.onFailure {
            Timber.tag(PERF_TAG).e(it, "failed to configure native LLM policy kind=%s", kind.name)
            throw it
        }
        Timber.tag(PERF_TAG).i(
            "native policy kind=%s requestedVulkan=%s temperature=%.2f topP=%.2f " +
                "topK=%d repeatPenalty=%.2f frequencyPenalty=%.2f",
            kind.name,
            requestedVulkan,
            samplingConfig.temperature,
            samplingConfig.topP,
            samplingConfig.topK,
            samplingConfig.repeatPenalty,
            samplingConfig.frequencyPenalty,
        )
        val e = current ?: AiChat.getInferenceEngine(context)
        val engineAcquireMs = InferenceTiming.elapsedMs(engineStartedAt, SystemClock.elapsedRealtime())

        // 状态机救援：上一次 loadModel 失败让 engine 卡在 Error；cleanUp() 把它拉回 Initialized。
        // 切换 kind 时也走 cleanUp 卸掉旧权重（同一 engine 不能并存两份）。
        val st = e.state.value
        // A failed switch must not leave the old file labelled as loaded.
        _currentModelId.value = null
        loadedKind = null
        loadedContextTokens = null
        loadedConfigFingerprint = null
        loadedRuntimeFingerprint = null
        loadedSystemPrompt = null
        if (llamaEngineLoadPreparation(st) == LlamaEngineLoadPreparation.RESET_THEN_LOAD) {
            Timber.w(
                "LlamaEngineHolder reset before load state=%s trackedKind=%s requestedKind=%s " +
                    "trackedEngine=%s",
                st.javaClass.simpleName,
                loadedKind?.name ?: "none",
                kind.name,
                current != null,
            )
            runCatching { e.cleanUp() }
                .onFailure { Timber.w(it, "cleanUp before model load failed") }
        }

        try {
            val modelStartedAt = SystemClock.elapsedRealtime()
            if (kind.usesGgufTemplate) {
                val metadata = LlamaTemplateGeneration.inspectFile(modelFile.absolutePath)
                if (metadata.size != 3 || metadata[0].isBlank() || metadata[2].isBlank()) {
                    throw LlmModelNotReadyException(context.getString(R.string.err_llm_load_failed,
                        displayName, modelFile.length() / 1024 / 1024))
                }
                Timber.tag(PERF_TAG).i("GGUF header architecture=%s quantization=%s templateChars=%d",
                    metadata[0], metadata[1], metadata[2].length)
            }
            e.loadModel(modelFile.absolutePath)
            Timber.tag(PERF_TAG).i("initialization effective policy: %s",
                LlamaTemplateGeneration.initializationInfo().joinToString(" "))
            val modelLoadMs = InferenceTiming.elapsedMs(modelStartedAt, SystemClock.elapsedRealtime())
            // System-prompt failure (including cancellation) must follow the same cleanup path as
            // loading failure. Otherwise an Error engine can be incorrectly labelled as loaded.
            var systemPromptMs = 0L
            if (!systemPrompt.isNullOrBlank()) {
                val systemPromptStartedAt = SystemClock.elapsedRealtime()
                e.setSystemPrompt(systemPrompt)
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
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { e.cleanUp() }
                    .onFailure { Timber.w(it, "cleanup after cancelled initialization") }
            }
            throw cancelled
        } catch (uae: UnsupportedArchitectureException) {
            runCatching { e.cleanUp() }.onFailure { Timber.w(it, "native cleanup after failed load") }
            // binding 把所有 "load 返回非 0" 统统包成 UnsupportedArchitectureException，message 永远是 null。
            // 实际可能原因：GGUF 损坏 / 量化格式 llama.cpp 不识别 / 架构不在白名单 / 内存不够。
            // 把诊断上下文打到 Timber（用户在「日志」页能看到），并抛更具体的 NotReady 异常。
            Timber.e(
                uae,
                "loadModel failed: model=$requestedId kind=$kind file=${modelFile.absolutePath} " +
                    "size=${modelFile.length() / 1024 / 1024}MB. " +
                    "binding 拒识 GGUF —— 可能是文件损坏、量化格式不支持、架构白名单不含此模型，或设备内存不足。"
            )
            throw LlmModelNotReadyException(
                context.getString(R.string.err_llm_load_failed, displayName, modelFile.length() / 1024 / 1024)
            )
        } catch (failure: Exception) {
            runCatching { e.cleanUp() }.onFailure { Timber.w(it, "native cleanup after failed initialization") }
            throw failure
        }
        engine = e
        loadedKind = kind
        loadedContextTokens = contextTokens
        loadedConfigFingerprint = fingerprint
        loadedRuntimeFingerprint = runtimeFingerprint
        loadedSystemPrompt = systemPrompt
        idleTimeoutMs = (value?.idleUnloadMinutes ?: 5) * 60_000L
        _currentModelId.value = requestedId
        touchInternal()
        Timber.i(
            "LlamaEngineHolder loaded kind=$kind file=${modelFile.name} size=${modelFile.length() / 1024 / 1024}MB"
        )
        e
    }

    /**
     * Runs one native-model session while unload and model switching are excluded.
     *
     * The lock order is always lifecycle gate, then [initLock]. Callers must keep every access to
     * [LlamaPromptMetrics] and the returned engine inside [action].
     */
    suspend fun <T> withEngineSession(
        kind: LlmModelKind,
        systemPrompt: String? = null,
        contextTokens: Int? = null,
        action: suspend (InferenceEngine) -> T,
    ): T = lifecycleGate.withSession {
        try {
            action(ensureLoaded(kind, systemPrompt, contextTokens))
        } finally {
            if (_currentModelId.value != null) touchInternal()
        }
    }

    /** All file selection and inference parameters belong to the request snapshot, not a later UI selection. */
    suspend fun <T> withManagedEngineSession(
        kind: LlmModelKind,
        settings: Settings,
        systemPrompt: String? = null,
        contextTokens: Int? = null,
        action: suspend (InferenceEngine) -> T,
    ): T = lifecycleGate.withSession {
        try {
            val id = settings.localModelId.takeIf { kind.managedViaGeneric }
            val managed = if (id != null) managedEntry(id) else null
            action(ensureLoaded(managed?.kind ?: kind, systemPrompt, contextTokens, managed, settings.runtimeLocalModelConfig))
        } finally {
            if (_currentModelId.value != null) touchInternal()
        }
    }

    suspend fun isManagedModelInstalled(kind: LlmModelKind, settings: Settings): Boolean =
        if (kind.managedViaGeneric && settings.localModelId != null)
            modelCatalog.entry(settings.localModelId)?.installed == true
        else isModelInstalled(kind)

    suspend fun managedEntry(id: String): LocalModelEntry = modelCatalog.entry(id)
        ?: throw LlmModelNotReadyException(context.getString(R.string.err_llm_imported_removed))

    /** Saving waits for native work; allocation changes are applied by the next request. */
    suspend fun applyManagedConfiguration(id: String) = lifecycleGate.withSession {
        initLock.withLock {
            if (_currentModelId.value != id) return@withLock
            val entry = modelCatalog.entry(id) ?: return@withLock
            val kind = entry.kind ?: LlmModelKind.QWEN3_0_6B_Q8_0
            val config = entry.config.normalized()
            idleTimeoutMs = config.idleUnloadMinutes * 60_000L
            if (engine?.state?.value is InferenceEngine.State.ModelReady &&
                loadedConfigFingerprint == config.initializationFingerprint()) {
                val threads = LocalLlmThreadPolicy.configured(CpuThreadPolicy.availableProcessors(),
                    BuildConfig.LOCAL_LLM_GENERATION_THREADS, config)
                val runtime = LocalLlmSamplingPolicy.nativeEnvironment(kind, config, imported = entry.kind == null) +
                    LocalLlmThreadPolicy.nativeEnvironment(threads)
                val runtimeFingerprint = runtime.entries.joinToString { "${it.key}=${it.value}" }
                if (loadedRuntimeFingerprint != runtimeFingerprint) {
                    configureEnvironment(runtime)
                    Timber.tag(PERF_TAG).i("saved runtime policy: %s", LlamaTemplateGeneration.configureRuntime().joinToString(" "))
                    loadedRuntimeFingerprint = runtimeFingerprint
                }
            }
            touchInternal()
        }
    }

    private fun configureEnvironment(values: Map<String, String>) {
        values.forEach { (name, value) -> Os.setenv(name, value, true) }
    }

    /** Waits for native work to finish before unloading or removing a weight file. */
    suspend fun deleteManagedModel(entry: LocalModelEntry): Boolean = withContext(Dispatchers.IO) { lifecycleGate.withSession {
        initLock.withLock {
            if (_currentModelId.value == entry.id) {
                engine?.cleanUp()
                loadedRuntimeFingerprint = null
                loadedSystemPrompt = null
                loadedKind = null
                loadedContextTokens = null
                loadedConfigFingerprint = null
                _currentModelId.value = null
                idleJob?.cancel()
                idleJob = null
            }
            if (entry.kind != null) installer.delete(entry.kind)
            else {
                modelCatalog.removeImported(entry.id)
                true
            }
        }
    } }

    /** 每次翻译触发后调一次：刷新 idle 计时器。 */
    fun touch() = touchInternal()

    private fun touchInternal() {
        lastUseAt = SystemClock.elapsedRealtime()
        scheduleIdleUnload()
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        val timeout = idleTimeoutMs
        if (timeout <= 0L || _currentModelId.value == null) {
            idleJob = null
            return
        }
        idleJob = ioScope.launch {
            delay(timeout)
            // 校验真实空闲时长，避免在 delay 期间又有 touch 误触发 unload。
            val idle = SystemClock.elapsedRealtime() - lastUseAt
            if (idle >= timeout) {
                unloadIfIdle(timeout)
            }
        }
    }

    private suspend fun unloadIfIdle(expectedTimeout: Long) = lifecycleGate.withSession {
        initLock.withLock {
            if (idleTimeoutMs != expectedTimeout || idleTimeoutMs <= 0L ||
                SystemClock.elapsedRealtime() - lastUseAt < idleTimeoutMs) return@withLock
            engine?.cleanUp()
            loadedKind = null
            loadedContextTokens = null
            loadedConfigFingerprint = null
            loadedRuntimeFingerprint = null
            loadedSystemPrompt = null
            _currentModelId.value = null
            idleJob?.cancel()
            idleJob = null
        }
    }

    /** 主动卸载模型权重。engine 实例保留以便下次 load。 */
    suspend fun unload() = lifecycleGate.withSession {
        initLock.withLock {
            val e = engine ?: return@withLock
            val previousKind = loadedKind
            e.cleanUp()
            loadedRuntimeFingerprint = null
            loadedSystemPrompt = null
            loadedKind = null
            loadedContextTokens = null
            loadedConfigFingerprint = null
            _currentModelId.value = null
            idleJob?.cancel()
            idleJob = null
            Timber.i("LlamaEngineHolder unloaded kind=$previousKind")
        }
    }

    companion object {
        private const val PERF_TAG = "LocalLlmPerf"
        const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
    }
}

internal enum class LlamaEngineLoadPreparation {
    LOAD,
    RESET_THEN_LOAD,
}

internal fun llamaEngineLoadPreparation(
    state: InferenceEngine.State,
): LlamaEngineLoadPreparation = when (state) {
    is InferenceEngine.State.Error,
    InferenceEngine.State.ModelReady,
    -> LlamaEngineLoadPreparation.RESET_THEN_LOAD

    else -> LlamaEngineLoadPreparation.LOAD
}
