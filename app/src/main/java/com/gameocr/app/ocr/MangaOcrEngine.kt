package com.gameocr.app.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import com.gameocr.app.R
import com.gameocr.app.data.MangaOcrAdvancedSettingsPolicy
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.dbnetUnclipRatioFor
import com.gameocr.app.util.CpuThreadPolicy
import com.gameocr.app.util.DecoderStepTimingSample
import com.gameocr.app.util.DecoderStepTimingSummary
import com.gameocr.app.util.InferenceDiagnostics
import com.gameocr.app.util.InferenceRuntimeDelta
import com.gameocr.app.util.InferenceRuntimeSnapshot
import com.gameocr.app.util.InferenceTiming
import com.gameocr.app.util.ReusableDirectBufferPool
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.Closeable
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * manga-ocr 端侧 OCR 引擎（[OcrEngineKind.MANGA_OCR_JA]）。
 *
 * **流水线**：
 * 1. 复用 [PaddleOcrEngine.detectQuads] 跑 DBNet 出文本行级 quads
 * 2. 转轴对齐 rect → [BubbleClusterer] 聚成「漫画气泡」级矩形
 * 3. 每个气泡 [Bitmap.createBitmap] 裁出 crop → ViT encoder (224×224) → 自回归 greedy decoder
 * 4. 输出 [TextBlock]（每气泡一段，[recognizedLanguage] = "ja"）
 *
 * **不变量**：
 * - 传入 bitmap 是 raw 的（CaptureService 已按 [OcrEngineKind.needsRawBitmap] 跳过 invert/binarize），
 *   只可能 upscale 过。坐标除 2 的缩回逻辑在 CaptureService 端统一处理。
 * - 不走 `warpCropQuad` 透视矫正——manga-ocr 训练时见的是矩形气泡 crop，不是窄条
 * - encoder hidden state 一次跑，复用给所有 decoder 步骤；input_ids 每步重建（长度+1）
 * - 每生成一个 token 检查 [coroutineContext.ensureActive]，用户取消时立刻退出 generate loop
 *
 * **模型来源**：l0wgear/manga-ocr-2025-onnx（基于 kha-white 训练，jzhang533 2025 改进版，
 * Optimum 导出 ONNX）。encoder hidden dim **192**（ViT-Small），decoder vocab **6144**。
 *
 * Phase 0 PoC 验证：8 张日漫页 5 分制均分 4.19 vs PPOCRv5 mobile 1.25，Δ=+2.94。
 * 单气泡 CPU 推理 30~300ms（远低于原估 1.5s），FP32 直接搬已可用，无需量化 / KV cache。
 */
@Singleton
class MangaOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val paddle: PaddleOcrEngine,
    private val installer: MangaOcrModelInstaller,
    private val settingsRepository: com.gameocr.app.data.SettingsRepository
) : OcrEngine {

    private val initLock = Mutex()
    private val warmupLock = Mutex()
    private var env: OrtEnvironment? = null
    private var encSession: OrtSession? = null
    private var decSession: OrtSession? = null
    private var encSessionOptions: OrtSession.SessionOptions? = null
    private var decSessionOptions: OrtSession.SessionOptions? = null
    @Volatile private var inferenceWarmupCompleted = false
    private var id2tok: Array<String> = emptyArray()
    private val directBufferPool = ReusableDirectBufferPool(
        maxRetainedFloatBuffers = 2,
        maxRetainedLongBuffers = 2,
    )
    private val powerManager by lazy {
        context.getSystemService(PowerManager::class.java)
    }

    // 启动时自适应解析（不同 Optimum 版本输入/输出名可能不一致）
    private var encOutputName: String = "last_hidden_state"
    private var decInputIdsName: String = "input_ids"
    private var decHiddenName: String = "encoder_hidden_states"
    private val availableProcessors by lazy { CpuThreadPolicy.availableProcessors() }
    private val ortThreads by lazy { CpuThreadPolicy.select(availableProcessors) }

    override suspend fun recognize(bitmap: Bitmap, kind: OcrEngineKind): List<TextBlock> {
        val startedAt = SystemClock.elapsedRealtime()
        val mangaReadyStartedAt = SystemClock.elapsedRealtime()
        ensureReady()
        val mangaReadyMs = InferenceTiming.elapsedMs(mangaReadyStartedAt, SystemClock.elapsedRealtime())
        // DBNet 也得就绪。Paddle 和 manga-ocr 都未就绪时分别抛各自的 ModelNotReadyException
        val paddleReadyStartedAt = SystemClock.elapsedRealtime()
        paddle.ensureReady()
        val paddleReadyMs = InferenceTiming.elapsedMs(paddleReadyStartedAt, SystemClock.elapsedRealtime())
        val s = settingsRepository.get()
        val results = withContext(Dispatchers.Default) {
            traceSection(MangaOcrTracePolicy.sectionName(MangaOcrTraceStage.RUN)) {
                runFull(bitmap, s)
            }
        }
        Timber.tag(PERF_TAG).i(
            "recognize totalMs=%d mangaReadyMs=%d paddleReadyMs=%d bitmap=%dx%d blocks=%d ortThreads=%d",
            InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
            mangaReadyMs,
            paddleReadyMs,
            bitmap.width,
            bitmap.height,
            results.size,
            ortThreads,
        )
        return results
    }

    suspend fun ensureReady() = initLock.withLock {
        if (encSession != null && decSession != null) return@withLock
        val startedAt = SystemClock.elapsedRealtime()
        val files = installer.checkInstalled()
            ?: throw ModelNotReadyException(context.getString(R.string.err_manga_ocr_not_ready))

        // 解析 vocab —— 行号即 token id（6144 行 BertJapaneseTokenizer 风格）
        val tokens = files.vocab.readText(Charsets.UTF_8).split('\n')
            .map { it.trim('\r', '\n', ' ', '\t') }
        if (tokens.size < 100 ||
            tokens.getOrNull(PAD_ID) != "[PAD]" ||
            tokens.getOrNull(CLS_ID) != "[CLS]" ||
            tokens.getOrNull(SEP_ID) != "[SEP]"
        ) {
            throw ModelNotReadyException(
                context.getString(R.string.err_manga_ocr_not_ready) + " (vocab broken)"
            )
        }
        id2tok = tokens.toTypedArray()

        val e = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val encLoaded = createSessionWithOptimizedCache(e, files.encoder, "encoder")
        val decLoaded = try {
            createSessionWithOptimizedCache(e, files.decoder, "decoder")
        } catch (t: Throwable) {
            runCatching { encLoaded.session.close() }
            runCatching { encLoaded.options.close() }
            throw t
        }
        val enc = encLoaded.session
        val dec = decLoaded.session

        // 自适应输入/输出名
        encOutputName = pickName(enc.outputNames, "last_hidden_state", "hidden_states", "output_0")
        decInputIdsName = pickName(dec.inputNames, "input_ids", "decoder_input_ids")
        decHiddenName = pickName(dec.inputNames, "encoder_hidden_states", "encoder_outputs", "last_hidden_state")

        encSession = enc
        decSession = dec
        encSessionOptions = encLoaded.options
        decSessionOptions = decLoaded.options
        Timber.i(
            "MangaOcr ready: enc=%dKB dec=%dKB vocab=%d encCache=%s decCache=%s encOut=%s decIn=%s decHidden=%s",
            files.encoder.length() / 1024, files.decoder.length() / 1024, tokens.size,
            encLoaded.cacheHit, decLoaded.cacheHit,
            encOutputName, decInputIdsName, decHiddenName
        )
        Timber.tag(PERF_TAG).i(
            "init totalMs=%d encMs=%d decMs=%d encCache=%s decCache=%s encKb=%d decKb=%d vocab=%d availableProcessors=%d ortThreads=%d",
            InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
            encLoaded.createMs,
            decLoaded.createMs,
            encLoaded.cacheHit,
            decLoaded.cacheHit,
            files.encoder.length() / 1024,
            files.decoder.length() / 1024,
            tokens.size,
            availableProcessors,
            ortThreads,
        )
    }

    suspend fun prewarm() {
        val startedAt = SystemClock.elapsedRealtime()
        val mangaStartedAt = SystemClock.elapsedRealtime()
        ensureReady()
        val mangaMs = InferenceTiming.elapsedMs(mangaStartedAt, SystemClock.elapsedRealtime())
        val paddleStartedAt = SystemClock.elapsedRealtime()
        paddle.ensureReady()
        val paddleMs = InferenceTiming.elapsedMs(paddleStartedAt, SystemClock.elapsedRealtime())
        val settings = settingsRepository.get()
        val displayMetrics = context.resources.displayMetrics
        val plan = MangaOcrStartupPolicy.inferenceWarmupPlan(
            screenWidth = displayMetrics.widthPixels,
            screenHeight = displayMetrics.heightPixels,
        )
        var skipped = false
        var inference = InferenceWarmupTiming()
        warmupLock.withLock {
            if (!MangaOcrStartupPolicy.shouldRunInferenceWarmup(inferenceWarmupCompleted)) {
                skipped = true
                return@withLock
            }
            Timber.tag(PERF_TAG).i(
                "prewarm inference start detector=%dx%d profile=%s encoderRuns=%d decoderSteps=%d",
                plan.detectorWidth,
                plan.detectorHeight,
                settings.paddleDetectionProfile,
                plan.encoderRuns,
                plan.decoderSteps,
            )
            inference = withContext(Dispatchers.Default) {
                runInferenceWarmup(settings, plan)
            }
            inferenceWarmupCompleted = true
        }
        Timber.tag(PERF_TAG).i(
            "prewarm totalMs=%d mangaMs=%d paddleMs=%d inferenceMs=%d dbnetMs=%d encoderMs=%d decoderMs=%d encoderRuns=%d decoderSteps=%d skipped=%s completed=%s",
            InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
            mangaMs,
            paddleMs,
            inference.totalMs,
            inference.dbnetMs,
            inference.encoderMs,
            inference.decoderMs,
            inference.encoderRuns,
            inference.decoderSteps,
            skipped,
            inferenceWarmupCompleted,
        )
    }

    private data class InferenceWarmupTiming(
        val totalMs: Long = 0L,
        val dbnetMs: Long = 0L,
        val encoderMs: Long = 0L,
        val decoderMs: Long = 0L,
        val encoderRuns: Int = 0,
        val decoderSteps: Int = 0,
    )

    private suspend fun runInferenceWarmup(
        settings: com.gameocr.app.data.Settings,
        plan: MangaOcrInferenceWarmupPlan,
    ): InferenceWarmupTiming {
        val startedAt = SystemClock.elapsedRealtime()
        coroutineContext.ensureActive()

        val detectorStartedAt = SystemClock.elapsedRealtime()
        val detectorBitmap = Bitmap.createBitmap(
            plan.detectorWidth,
            plan.detectorHeight,
            Bitmap.Config.ARGB_8888,
        ).apply {
            eraseColor(Color.WHITE)
        }
        try {
            paddle.detectQuads(
                bitmap = detectorBitmap,
                binThresh = settings.dbnetProbThresh,
                scoreThresh = settings.dbnetBoxScoreThresh,
                unclipRatio = settings.dbnetUnclipRatioFor(OcrEngineKind.MANGA_OCR_JA),
                profile = settings.paddleDetectionProfile,
                passLabel = "manga-prewarm",
            )
        } finally {
            detectorBitmap.recycle()
        }
        val dbnetMs = InferenceTiming.elapsedMs(detectorStartedAt, SystemClock.elapsedRealtime())

        val e = checkNotNull(env) { "MangaOcr environment missing after ensureReady" }
        val enc = checkNotNull(encSession) { "MangaOcr encoder missing after ensureReady" }
        val dec = checkNotNull(decSession) { "MangaOcr decoder missing after ensureReady" }
        var encoderMs = 0L
        var decoderMs = 0L
        var decoderSteps = 0
        val encoderBitmap = Bitmap.createBitmap(
            IMG_SIZE,
            IMG_SIZE,
            Bitmap.Config.ARGB_8888,
        ).apply {
            eraseColor(Color.WHITE)
        }
        try {
            repeat(plan.encoderRuns) { runIndex ->
                coroutineContext.ensureActive()
                val encoderRun = runEncoderInference(encoderBitmap, e, enc)
                encoderMs += encoderRun.timing.totalMs
                logEncoderDetail(
                    scope = "prewarm[$runIndex]",
                    timing = encoderRun.timing,
                    cropWidth = encoderBitmap.width,
                    cropHeight = encoderBitmap.height,
                )

                var currentDecoderMs = 0L
                var warmupToken = -1
                encoderRun.use { encoderOutput ->
                    if (runIndex < plan.decoderSteps) {
                        coroutineContext.ensureActive()
                        val decoderRuntimeStart = captureInferenceRuntimeSnapshot(
                            includeSystemStats = false,
                            startingBoundary = true,
                        )
                        val ids = IntArray(1) { CLS_ID }
                        val step = runDecoderStep(
                            environment = e,
                            decoder = dec,
                            hidden = encoderOutput.hidden,
                            ids = ids,
                            length = 1,
                        )
                        warmupToken = step.nextId
                        val decoderRuntime = InferenceDiagnostics.runtimeDelta(
                            decoderRuntimeStart,
                            captureInferenceRuntimeSnapshot(
                                includeSystemStats = false,
                                startingBoundary = false,
                            ),
                        )
                        val decoderTotalUs = decoderRuntime.wallUs
                        val decoderSummary = InferenceTiming.stageSummary(
                            totalUs = decoderTotalUs,
                            stagesUs = listOf(
                                step.bufferAcquireUs,
                                step.inputFillUs,
                                step.tensorCreateUs,
                                step.runUs,
                                step.logitsReadUs,
                                step.argmaxUs,
                            ),
                        )
                        val decoderTiming = MangaDecoderTiming(
                            totalMs = decoderTotalUs / 1_000L,
                            totalUs = decoderTotalUs,
                            bufferAcquireUs = step.bufferAcquireUs,
                            inputFillUs = step.inputFillUs,
                            tensorCreateUs = step.tensorCreateUs,
                            runUs = step.runUs,
                            logitsReadUs = step.logitsReadUs,
                            argmaxUs = step.argmaxUs,
                            otherUs = decoderSummary.unaccountedUs,
                            steps = 1,
                            nonDirectInputs = if (step.inputDirect) 0 else 1,
                            reusedInputs = if (step.bufferReused) 1 else 0,
                            tensorOwnedCopies = if (step.tensorOwnsBuffer) 1 else 0,
                            runtime = decoderRuntime,
                            stepRun = InferenceDiagnostics.summarizeDecoderSteps(
                                listOf(
                                    DecoderStepTimingSample(
                                        stepIndex = 0,
                                        inputLength = 1,
                                        runUs = step.runUs,
                                    ),
                                ),
                            ),
                        )
                        currentDecoderMs = decoderTiming.totalMs
                        decoderMs += currentDecoderMs
                        decoderSteps++
                        logDecoderDetail("prewarm[$runIndex]", decoderTiming)
                    }
                }
                Timber.tag(PERF_TAG).i(
                    "prewarm manga run=%d encoderMs=%d decoderMs=%d decoderToken=%d",
                    runIndex,
                    encoderRun.timing.totalMs,
                    currentDecoderMs,
                    warmupToken,
                )
            }
        } finally {
            encoderBitmap.recycle()
        }

        return InferenceWarmupTiming(
            totalMs = InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
            dbnetMs = dbnetMs,
            encoderMs = encoderMs,
            decoderMs = decoderMs,
            encoderRuns = plan.encoderRuns,
            decoderSteps = decoderSteps,
        )
    }

    private data class LoadedOrtSession(
        val session: OrtSession,
        val options: OrtSession.SessionOptions,
        val cacheHit: Boolean,
        val createMs: Long,
    )

    private fun createSessionWithOptimizedCache(
        environment: OrtEnvironment,
        source: File,
        role: String,
    ): LoadedOrtSession {
        val cacheDir = checkNotNull(source.parentFile) {
            "MangaOcr model has no parent directory: ${source.absolutePath}"
        }
        val cache = File(
            cacheDir,
            MangaOcrStartupPolicy.optimizedCacheFileName(
                sourceFileName = source.name,
                sourceLength = source.length(),
                sourceLastModified = source.lastModified(),
                runtimeSignature = "cpu-all-t$ortThreads-ort${environment.version}",
            ),
        )
        cacheDir.listFiles()
            ?.filter {
                it.name != cache.name &&
                    MangaOcrStartupPolicy.isOptimizedCacheFileFor(source.name, it.name)
            }
            ?.forEach { stale ->
                if (!stale.delete()) {
                    Timber.w("MangaOcr failed to delete stale %s cache: %s", role, stale.absolutePath)
                }
            }

        if (cache.isFile && MangaOcrStartupPolicy.isReusableOptimizedCache(cache.length())) {
            val cachedOptions = newSessionOptions(OrtSession.SessionOptions.OptLevel.NO_OPT)
            val cacheStartedAt = SystemClock.elapsedRealtime()
            try {
                val session = environment.createSession(cache.absolutePath, cachedOptions)
                val createMs = InferenceTiming.elapsedMs(cacheStartedAt, SystemClock.elapsedRealtime())
                Timber.tag(PERF_TAG).i(
                    "session role=%s cacheHit=true createMs=%d sourceKb=%d cacheKb=%d",
                    role,
                    createMs,
                    source.length() / 1024,
                    cache.length() / 1024,
                )
                return LoadedOrtSession(session, cachedOptions, cacheHit = true, createMs = createMs)
            } catch (t: Throwable) {
                runCatching { cachedOptions.close() }
                if (!cache.delete()) {
                    Timber.w("MangaOcr failed to delete broken %s cache: %s", role, cache.absolutePath)
                }
                Timber.w(t, "MangaOcr %s optimized cache load failed; falling back to source model", role)
            }
        }

        val tempCache = File(cacheDir, cache.name + ".tmp")
        if (tempCache.exists() && !tempCache.delete()) {
            Timber.w("MangaOcr failed to delete stale %s temp cache: %s", role, tempCache.absolutePath)
        }
        val sourceOptions = newSessionOptions(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val cacheOutputConfigured = runCatching {
            sourceOptions.setOptimizedModelFilePath(tempCache.absolutePath)
        }.onFailure {
            Timber.w(it, "MangaOcr %s optimized cache output unavailable", role)
        }.isSuccess
        val sourceStartedAt = SystemClock.elapsedRealtime()
        try {
            val session = environment.createSession(source.absolutePath, sourceOptions)
            val createMs = InferenceTiming.elapsedMs(sourceStartedAt, SystemClock.elapsedRealtime())
            if (cacheOutputConfigured &&
                tempCache.isFile &&
                MangaOcrStartupPolicy.isReusableOptimizedCache(tempCache.length())
            ) {
                if (cache.exists() && !cache.delete()) {
                    Timber.w("MangaOcr failed to replace old %s cache: %s", role, cache.absolutePath)
                }
                if (!tempCache.renameTo(cache)) {
                    Timber.w("MangaOcr failed to publish %s optimized cache: %s", role, tempCache.absolutePath)
                }
            }
            Timber.tag(PERF_TAG).i(
                "session role=%s cacheHit=false createMs=%d sourceKb=%d cacheKb=%d",
                role,
                createMs,
                source.length() / 1024,
                cache.takeIf(File::isFile)?.length()?.div(1024) ?: 0L,
            )
            return LoadedOrtSession(session, sourceOptions, cacheHit = false, createMs = createMs)
        } catch (t: Throwable) {
            runCatching { sourceOptions.close() }
            runCatching { tempCache.delete() }
            throw t
        }
    }

    private fun newSessionOptions(
        optimizationLevel: OrtSession.SessionOptions.OptLevel,
    ): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(ortThreads)
        setOptimizationLevel(optimizationLevel)
    }

    private fun pickName(names: Set<String>, vararg candidates: String): String {
        for (c in candidates) if (c in names) return c
        // 兜底用第一个名字（不应该走到这里，走到说明 Optimum 改了导出格式）
        Timber.w("MangaOcr unknown name set %s; candidates=%s; falling back to first", names, candidates.toList())
        return names.firstOrNull() ?: candidates.first()
    }

    private suspend fun runFull(bitmap: Bitmap, settings: com.gameocr.app.data.Settings): List<TextBlock> {
        val startedAt = SystemClock.elapsedRealtime()
        val runtimeStart = captureInferenceRuntimeSnapshot(
            includeSystemStats = true,
            startingBoundary = true,
        )
        // 1) 复用 paddle DBNet 检测 → quads；大图额外走重叠分块，提升整屏小字召回。
        val mangaUnclipRatio = settings.dbnetUnclipRatioFor(OcrEngineKind.MANGA_OCR_JA)
        val detectStartedAt = SystemClock.elapsedRealtime()
        val quads = traceSection(MangaOcrTracePolicy.sectionName(MangaOcrTraceStage.DETECT)) {
            detectQuadsForManga(
                bitmap = bitmap,
                settings = settings,
                mangaUnclipRatio = mangaUnclipRatio
            )
        }
        val detectMs = InferenceTiming.elapsedMs(detectStartedAt, SystemClock.elapsedRealtime())
        if (quads.isEmpty()) {
            Timber.i("MangaOcr: DBNet returned 0 quads")
            Timber.tag(PERF_TAG).i(
                "run totalMs=%d detectMs=%d clusterMs=0 bubblesMs=0 bitmap=%dx%d quads=0 bubbles=0 blocks=0",
                InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
                detectMs,
                bitmap.width,
                bitmap.height,
            )
            logRuntimeDetail(
                scope = "run-empty",
                start = runtimeStart,
                end = captureInferenceRuntimeSnapshot(
                    includeSystemStats = true,
                    startingBoundary = false,
                ),
            )
            return emptyList()
        }

        // 2) quad → IntRect → 气泡聚类（用 BubbleClusterer.IntRect 而非 android.graphics.Rect，
        //    后者在 JVM 单测里是 Stub 不能直接构造）
        val clusterStartedAt = SystemClock.elapsedRealtime()
        val (rects, bubbles) = traceSection(
            MangaOcrTracePolicy.sectionName(MangaOcrTraceStage.CLUSTER)
        ) {
            val clusteredRects = quads.map { quad ->
                val b = quad.axisAlignedBounds()
                BubbleClusterer.IntRect(
                    left = b[0].coerceAtLeast(0),
                    top = b[1].coerceAtLeast(0),
                    right = b[2].coerceAtMost(bitmap.width),
                    bottom = b[3].coerceAtMost(bitmap.height)
                )
            }
            val bubbleClusterGap = MangaOcrAdvancedSettingsPolicy.effectiveBubbleClusterGap(
                settings.bubbleClusterGap
            )
            val cropPaddingPx = MangaOcrAdvancedSettingsPolicy.effectiveCropPaddingPx(
                settings.mangaOcrCropPaddingPx
            )
            val clusteredBubbles = BubbleClusterer.cluster(
                rects = clusteredRects,
                imgW = bitmap.width,
                imgH = bitmap.height,
                pad = cropPaddingPx,
                gap = bubbleClusterGap,
            )
            clusteredRects to clusteredBubbles
        }
        val bubbleClusterGap = MangaOcrAdvancedSettingsPolicy.effectiveBubbleClusterGap(
            settings.bubbleClusterGap
        )
        val cropPaddingPx = MangaOcrAdvancedSettingsPolicy.effectiveCropPaddingPx(
            settings.mangaOcrCropPaddingPx
        )
        val clusterMs = InferenceTiming.elapsedMs(clusterStartedAt, SystemClock.elapsedRealtime())
        Timber.i(
            "MangaOcr: %d quads -> %d bubbles (profile=%s maxSide=%d tiling=%s gap=%d cropPad=%d dbnet=%.2f/%.2f×%.2f)",
            quads.size,
            bubbles.size,
            settings.paddleDetectionProfile.name,
            settings.paddleDetectionProfile.maxSideLen,
            settings.paddleDetectionProfile.enableMangaTiling,
            bubbleClusterGap,
            cropPaddingPx,
            settings.dbnetProbThresh, settings.dbnetBoxScoreThresh, mangaUnclipRatio
        )

        // 3) 每气泡裁切 + 推理
        val bubblesStartedAt = SystemClock.elapsedRealtime()
        val results = mutableListOf<TextBlock>()
        for ((i, bubble) in bubbles.withIndex()) {
            coroutineContext.ensureActive()
            val crop = cropBubble(bitmap, bubble.rect) ?: continue
            val bubbleStartedAt = SystemClock.elapsedRealtime()
            val cropWidth = crop.width
            val cropHeight = crop.height
            val recognition = try {
                traceSection(
                    MangaOcrTracePolicy.sectionName(MangaOcrTraceStage.BUBBLE, i)
                ) {
                    recognizeBubble(crop, i)
                }
            } finally {
                crop.recycle()
            }
            val text = recognition.text.trim()
            val memberRects = bubble.memberIndices.mapNotNull(rects::getOrNull)
            Timber.tag(PERF_TAG).i(
                "bubble index=%d totalMs=%d encoderMs=%d decoderMs=%d decoderSteps=%d crop=%dx%d chars=%d",
                i,
                InferenceTiming.elapsedMs(bubbleStartedAt, SystemClock.elapsedRealtime()),
                recognition.encoder.totalMs,
                recognition.decoder.totalMs,
                recognition.decoder.steps,
                cropWidth,
                cropHeight,
                text.length,
            )
            logEncoderDetail("bubble[$i]", recognition.encoder, cropWidth, cropHeight)
            logDecoderDetail("bubble[$i]", recognition.decoder)
            Timber.i(
                "MangaOcr bub[%d] content=%s crop=%s cropPad=%d members=%d memberRects=%s -> '%s' (%d chars)",
                i,
                bubble.contentRect,
                bubble.rect,
                cropPaddingPx,
                bubble.memberIndices.size,
                memberRects,
                text,
                text.length,
            )
            if (text.isEmpty()) continue
            if (shouldDropMangaOcrEdgeNoise(text, bubble.rect, bitmap.width, bitmap.height)) {
                Timber.i("MangaOcr drop edge noise bub[%d] %s -> '%s'", i, bubble.rect, text)
                continue
            }
            val sourceBoxes = memberRects.map { rect ->
                Rect(rect.left, rect.top, rect.right, rect.bottom)
            }
            val bubbleBounds = Rect(
                bubble.contentRect.left,
                bubble.contentRect.top,
                bubble.contentRect.right,
                bubble.contentRect.bottom,
            )
            results += TextBlock(
                text = text,
                boundingBox = bubbleBounds,
                confidence = 1f,
                recognizedLanguage = "ja",
                layoutOrientation = inferSourceLayoutOrientation(
                    sourceBoxes = memberRects,
                    blockBounds = bubble.contentRect,
                ),
                sourceBoxes = sourceBoxes,
            )
        }
        val bubblesMs = InferenceTiming.elapsedMs(bubblesStartedAt, SystemClock.elapsedRealtime())
        Timber.tag(PERF_TAG).i(
            "run profile=%s maxSide=%d tiling=%s cropPad=%d totalMs=%d detectMs=%d clusterMs=%d bubblesMs=%d bitmap=%dx%d quads=%d bubbles=%d blocks=%d",
            settings.paddleDetectionProfile.name,
            settings.paddleDetectionProfile.maxSideLen,
            settings.paddleDetectionProfile.enableMangaTiling,
            cropPaddingPx,
            InferenceTiming.elapsedMs(startedAt, SystemClock.elapsedRealtime()),
            detectMs,
            clusterMs,
            bubblesMs,
            bitmap.width,
            bitmap.height,
            quads.size,
            bubbles.size,
            results.size,
        )
        logRuntimeDetail(
            scope = "run",
            start = runtimeStart,
            end = captureInferenceRuntimeSnapshot(
                includeSystemStats = true,
                startingBoundary = false,
            ),
        )
        return results
    }

    private suspend fun detectQuadsForManga(
        bitmap: Bitmap,
        settings: com.gameocr.app.data.Settings,
        mangaUnclipRatio: Float
    ): List<DBPostprocessor.Quad> {
        val base = paddle.detectQuads(
            bitmap,
            binThresh = settings.dbnetProbThresh,
            scoreThresh = settings.dbnetBoxScoreThresh,
            unclipRatio = mangaUnclipRatio,
            profile = settings.paddleDetectionProfile,
            passLabel = "manga-full",
        )
        if (!MangaOcrTiling.shouldUseTiles(
                bitmap.width,
                bitmap.height,
                settings.paddleDetectionProfile,
            )
        ) {
            Timber.i(
                "MangaOcr tiling skipped profile=%s enabled=%s bitmap=%dx%d tileSide=%d",
                settings.paddleDetectionProfile.name,
                settings.paddleDetectionProfile.enableMangaTiling,
                bitmap.width,
                bitmap.height,
                MangaOcrTiling.DEFAULT_TILE_SIDE,
            )
            return base
        }

        val tiles = MangaOcrTiling.tilesFor(bitmap.width, bitmap.height)
        val tiled = mutableListOf<DBPostprocessor.Quad>()
        for (tile in tiles) {
            coroutineContext.ensureActive()
            val crop = Bitmap.createBitmap(bitmap, tile.left, tile.top, tile.width, tile.height)
            try {
                val tileQuads = paddle.detectQuads(
                    crop,
                    binThresh = settings.dbnetProbThresh,
                    scoreThresh = settings.dbnetBoxScoreThresh,
                    unclipRatio = mangaUnclipRatio,
                    profile = settings.paddleDetectionProfile,
                    passLabel = "manga-tile[$tile]",
                )
                tiled += tileQuads.map { it.offsetBy(tile.left.toFloat(), tile.top.toFloat()) }
            } finally {
                crop.recycle()
            }
        }

        // Keep the full-page result as the structural anchor. Tiled detections may recover isolated
        // text, but an overlapping/nearby tiled box must not bridge or enlarge an existing bubble.
        val bubbleClusterGap = MangaOcrAdvancedSettingsPolicy.effectiveBubbleClusterGap(
            settings.bubbleClusterGap
        )
        val merged = fuseMangaOcrQuads(
            base = base,
            tiled = tiled,
            anchorGuardGap = bubbleClusterGap,
        )
        Timber.i(
            "MangaOcr tiled DBNet: base=%d tiled=%d merged=%d supplements=%d tiles=%d bitmap=%dx%d",
            base.size,
            tiled.size,
            merged.size,
            (merged.size - dedupePaddleQuads(base).size).coerceAtLeast(0),
            tiles.size,
            bitmap.width,
            bitmap.height,
        )
        return merged
    }

    private fun cropBubble(src: Bitmap, rect: BubbleClusterer.IntRect): Bitmap? {
        val w = rect.width
        val h = rect.height
        if (w <= 0 || h <= 0) return null
        // 不做 rotate90 预处理。错误假设过：以为 manga-ocr 的 ViT 训练时是横排矩形需要把竖排旋转过去。
        // 实际 manga-ocr 训练数据集是 Manga109 即日漫整页，**ViT 见过的就是竖排长条 squash 到 224×224**，
        // 旋转反而把输入打成 out-of-distribution，整张图识别质量崩盘（实测 12 bubbles 9 个乱码）。
        // 保留 cropBubble 简单形式；OOD 改进留给"上采样长边到 2400"以及未来的更换检测器方向。
        return runCatching {
            Bitmap.createBitmap(src, rect.left, rect.top, w, h)
        }.getOrNull()
    }

    private data class MangaPreprocessResult(
        val data: FloatArray,
        val totalUs: Long,
        val scaleUs: Long,
        val grayUs: Long,
        val pixelReadUs: Long,
        val nchwUs: Long,
        val otherUs: Long,
    )

    private data class MangaEncoderTiming(
        val totalMs: Long = 0L,
        val totalUs: Long = 0L,
        val preprocessUs: Long = 0L,
        val scaleUs: Long = 0L,
        val grayUs: Long = 0L,
        val pixelReadUs: Long = 0L,
        val nchwUs: Long = 0L,
        val inputBufferAcquireUs: Long = 0L,
        val inputBufferFillUs: Long = 0L,
        val inputTensorCreateUs: Long = 0L,
        val runUs: Long = 0L,
        val outputLookupUs: Long = 0L,
        val otherUs: Long = 0L,
        val inputFloats: Int = 0,
        val inputDirect: Boolean = false,
        val inputBufferReused: Boolean = false,
        val inputBufferCapacityFloats: Int = 0,
        val inputTensorOwnsBuffer: Boolean = false,
        val hiddenFloats: Int = 0,
    )

    private class MangaEncoderRun(
        private val result: OrtSession.Result,
        val hidden: OnnxTensor,
        val timing: MangaEncoderTiming,
    ) : Closeable {
        override fun close() {
            result.close()
        }
    }

    private data class DecoderStepResult(
        val nextId: Int,
        val bufferAcquireUs: Long,
        val inputFillUs: Long,
        val tensorCreateUs: Long,
        val runUs: Long,
        val logitsReadUs: Long,
        val argmaxUs: Long,
        val inputDirect: Boolean,
        val bufferReused: Boolean,
        val tensorOwnsBuffer: Boolean,
    )

    private data class MangaDecoderTiming(
        val totalMs: Long = 0L,
        val totalUs: Long = 0L,
        val bufferAcquireUs: Long = 0L,
        val inputFillUs: Long = 0L,
        val tensorCreateUs: Long = 0L,
        val runUs: Long = 0L,
        val logitsReadUs: Long = 0L,
        val argmaxUs: Long = 0L,
        val decodeUs: Long = 0L,
        val otherUs: Long = 0L,
        val steps: Int = 0,
        val nonDirectInputs: Int = 0,
        val reusedInputs: Int = 0,
        val tensorOwnedCopies: Int = 0,
        val runtime: InferenceRuntimeDelta? = null,
        val stepRun: DecoderStepTimingSummary = DecoderStepTimingSummary(),
    )

    private data class BubbleRecognition(
        val text: String,
        val encoder: MangaEncoderTiming = MangaEncoderTiming(),
        val decoder: MangaDecoderTiming = MangaDecoderTiming(),
    )

    private suspend fun recognizeBubble(
        crop: Bitmap,
        bubbleIndex: Int,
    ): BubbleRecognition {
        val e = env ?: return BubbleRecognition("")
        val enc = encSession ?: return BubbleRecognition("")
        val dec = decSession ?: return BubbleRecognition("")

        val encoderRun = traceSection(
            MangaOcrTracePolicy.sectionName(MangaOcrTraceStage.ENCODER, bubbleIndex)
        ) {
            runEncoderInference(crop, e, enc)
        }
        val decoderRuntimeStart = captureInferenceRuntimeSnapshot(
            includeSystemStats = false,
            startingBoundary = true,
        )
        var bufferAcquireUs = 0L
        var inputFillUs = 0L
        var tensorCreateUs = 0L
        var runUs = 0L
        var logitsReadUs = 0L
        var argmaxUs = 0L
        var decoderSteps = 0
        var nonDirectInputs = 0
        var reusedInputs = 0
        var tensorOwnedCopies = 0
        var decodeUs = 0L
        val stepRunSamples = ArrayList<DecoderStepTimingSample>(MAX_TOKENS)

        val text = traceSection(
            MangaOcrTracePolicy.sectionName(MangaOcrTraceStage.DECODER, bubbleIndex)
        ) {
            encoderRun.use { encoderOutput ->
                val ids = IntArray(MAX_TOKENS + 1)
                ids[0] = CLS_ID
                var len = 1
                for (step in 0 until MAX_TOKENS) {
                    coroutineContext.ensureActive()
                    val stepResult = runDecoderStep(e, dec, encoderOutput.hidden, ids, len)
                    decoderSteps++
                    bufferAcquireUs += stepResult.bufferAcquireUs
                    inputFillUs += stepResult.inputFillUs
                    tensorCreateUs += stepResult.tensorCreateUs
                    runUs += stepResult.runUs
                    logitsReadUs += stepResult.logitsReadUs
                    argmaxUs += stepResult.argmaxUs
                    stepRunSamples += DecoderStepTimingSample(
                        stepIndex = step,
                        inputLength = len,
                        runUs = stepResult.runUs,
                    )
                    if (!stepResult.inputDirect) nonDirectInputs++
                    if (stepResult.bufferReused) reusedInputs++
                    if (stepResult.tensorOwnsBuffer) tensorOwnedCopies++

                    if (stepResult.nextId == SEP_ID) break
                    ids[len] = stepResult.nextId
                    len++
                    if (len > MAX_TOKENS) break
                }
                val decodeStartedAtNs = SystemClock.elapsedRealtimeNanos()
                val decoded = decodeTokens(ids, fromIdx = 1, toIdx = len)
                decodeUs = InferenceTiming.elapsedUs(
                    decodeStartedAtNs,
                    SystemClock.elapsedRealtimeNanos(),
                )
                decoded
            }
        }
        val decoderRuntime = InferenceDiagnostics.runtimeDelta(
            decoderRuntimeStart,
            captureInferenceRuntimeSnapshot(
                includeSystemStats = false,
                startingBoundary = false,
            ),
        )
        val decoderTotalUs = decoderRuntime.wallUs
        val decoderSummary = InferenceTiming.stageSummary(
            totalUs = decoderTotalUs,
            stagesUs = listOf(
                bufferAcquireUs,
                inputFillUs,
                tensorCreateUs,
                runUs,
                logitsReadUs,
                argmaxUs,
                decodeUs,
            ),
        )
        return BubbleRecognition(
            text = text,
            encoder = encoderRun.timing,
            decoder = MangaDecoderTiming(
                totalMs = decoderTotalUs / 1_000L,
                totalUs = decoderTotalUs,
                bufferAcquireUs = bufferAcquireUs,
                inputFillUs = inputFillUs,
                tensorCreateUs = tensorCreateUs,
                runUs = runUs,
                logitsReadUs = logitsReadUs,
                argmaxUs = argmaxUs,
                decodeUs = decodeUs,
                otherUs = decoderSummary.unaccountedUs,
                steps = decoderSteps,
                nonDirectInputs = nonDirectInputs,
                reusedInputs = reusedInputs,
                tensorOwnedCopies = tensorOwnedCopies,
                runtime = decoderRuntime,
                stepRun = InferenceDiagnostics.summarizeDecoderSteps(stepRunSamples),
            ),
        )
    }

    private fun runEncoderInference(
        crop: Bitmap,
        environment: OrtEnvironment,
        encoder: OrtSession,
    ): MangaEncoderRun {
        val totalStartedAtMs = SystemClock.elapsedRealtime()
        val totalStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val preprocess = preprocess224Timed(crop)

        val bufferAcquireStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val inputLease = directBufferPool.acquireFloat(preprocess.data.size)
        val inputBufferAcquireUs = InferenceTiming.elapsedUs(
            bufferAcquireStartedAtNs,
            SystemClock.elapsedRealtimeNanos(),
        )

        var inputBufferFillUs = 0L
        var inputTensorCreateUs = 0L
        var inputDirect = false
        var inputTensorOwnsBuffer = false
        var runUs = 0L
        var outputLookupUs = 0L
        var encoderResult: OrtSession.Result? = null
        lateinit var hidden: OnnxTensor
        var hiddenFloats = 0
        inputLease.use { lease ->
            val inputBuffer = lease.buffer
            val bufferFillStartedAtNs = SystemClock.elapsedRealtimeNanos()
            inputBuffer.put(preprocess.data)
            inputBuffer.flip()
            inputBufferFillUs = InferenceTiming.elapsedUs(
                bufferFillStartedAtNs,
                SystemClock.elapsedRealtimeNanos(),
            )
            inputDirect = inputBuffer.isDirect

            val tensorStartedAtNs = SystemClock.elapsedRealtimeNanos()
            val pixelTensor = OnnxTensor.createTensor(
                environment,
                inputBuffer,
                longArrayOf(1, 3, IMG_SIZE.toLong(), IMG_SIZE.toLong()),
            )
            inputTensorCreateUs = InferenceTiming.elapsedUs(
                tensorStartedAtNs,
                SystemClock.elapsedRealtimeNanos(),
            )
            inputTensorOwnsBuffer = pixelTensor.ownsBuffer()

            pixelTensor.use { tensor ->
                val runStartedAtNs = SystemClock.elapsedRealtimeNanos()
                val result = encoder.run(mapOf("pixel_values" to tensor))
                runUs = InferenceTiming.elapsedUs(runStartedAtNs, SystemClock.elapsedRealtimeNanos())
                try {
                    val lookupStartedAtNs = SystemClock.elapsedRealtimeNanos()
                    hidden = result[encOutputName].get() as OnnxTensor
                    outputLookupUs = InferenceTiming.elapsedUs(
                        lookupStartedAtNs,
                        SystemClock.elapsedRealtimeNanos(),
                    )
                    hiddenFloats = tensorElementCount(hidden.info.shape)
                    encoderResult = result
                } catch (t: Throwable) {
                    result.close()
                    throw t
                }
            }
        }
        val totalUs = InferenceTiming.elapsedUs(totalStartedAtNs, SystemClock.elapsedRealtimeNanos())
        val summary = InferenceTiming.stageSummary(
            totalUs = totalUs,
            stagesUs = listOf(
                preprocess.totalUs,
                inputBufferAcquireUs,
                inputBufferFillUs,
                inputTensorCreateUs,
                runUs,
                outputLookupUs,
            ),
        )
        return MangaEncoderRun(
            result = checkNotNull(encoderResult),
            hidden = hidden,
            timing = MangaEncoderTiming(
                totalMs = InferenceTiming.elapsedMs(totalStartedAtMs, SystemClock.elapsedRealtime()),
                totalUs = totalUs,
                preprocessUs = preprocess.totalUs,
                scaleUs = preprocess.scaleUs,
                grayUs = preprocess.grayUs,
                pixelReadUs = preprocess.pixelReadUs,
                nchwUs = preprocess.nchwUs,
                inputBufferAcquireUs = inputBufferAcquireUs,
                inputBufferFillUs = inputBufferFillUs,
                inputTensorCreateUs = inputTensorCreateUs,
                runUs = runUs,
                outputLookupUs = outputLookupUs,
                otherUs = summary.unaccountedUs + preprocess.otherUs,
                inputFloats = preprocess.data.size,
                inputDirect = inputDirect,
                inputBufferReused = inputLease.reused,
                inputBufferCapacityFloats = inputLease.capacityElements,
                inputTensorOwnsBuffer = inputTensorOwnsBuffer,
                hiddenFloats = hiddenFloats,
            ),
        )
    }

    private fun runDecoderStep(
        environment: OrtEnvironment,
        decoder: OrtSession,
        hidden: OnnxTensor,
        ids: IntArray,
        length: Int,
    ): DecoderStepResult {
        val bufferAcquireStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val inputLease = directBufferPool.acquireLong(MAX_TOKENS + 1)
        val bufferAcquireUs = InferenceTiming.elapsedUs(
            bufferAcquireStartedAtNs,
            SystemClock.elapsedRealtimeNanos(),
        )
        return inputLease.use { lease ->
            val inputBuffer = lease.buffer
            val inputFillStartedAtNs = SystemClock.elapsedRealtimeNanos()
            for (index in 0 until length) {
                inputBuffer.put(ids[index].toLong())
            }
            inputBuffer.flip()
            val inputFillUs = InferenceTiming.elapsedUs(
                inputFillStartedAtNs,
                SystemClock.elapsedRealtimeNanos(),
            )
            val inputDirect = inputBuffer.isDirect

            val tensorStartedAtNs = SystemClock.elapsedRealtimeNanos()
            val idsTensor = OnnxTensor.createTensor(
                environment,
                inputBuffer,
                longArrayOf(1, length.toLong()),
            )
            val tensorCreateUs = InferenceTiming.elapsedUs(
                tensorStartedAtNs,
                SystemClock.elapsedRealtimeNanos(),
            )
            val tensorOwnsBuffer = idsTensor.ownsBuffer()

            var runUs = 0L
            var logitsReadUs = 0L
            var argmaxUs = 0L
            val nextId = idsTensor.use { inputIds ->
                val runStartedAtNs = SystemClock.elapsedRealtimeNanos()
                val result = decoder.run(
                    mapOf(
                        decInputIdsName to inputIds,
                        decHiddenName to hidden,
                    ),
                )
                runUs = InferenceTiming.elapsedUs(runStartedAtNs, SystemClock.elapsedRealtimeNanos())
                result.use {
                    val logitsStartedAtNs = SystemClock.elapsedRealtimeNanos()
                    @Suppress("UNCHECKED_CAST")
                    val logits = it[0].value as Array<Array<FloatArray>>
                    logitsReadUs = InferenceTiming.elapsedUs(
                        logitsStartedAtNs,
                        SystemClock.elapsedRealtimeNanos(),
                    )
                    val argmaxStartedAtNs = SystemClock.elapsedRealtimeNanos()
                    val id = argmaxLastStep(logits[0])
                    argmaxUs = InferenceTiming.elapsedUs(
                        argmaxStartedAtNs,
                        SystemClock.elapsedRealtimeNanos(),
                    )
                    id
                }
            }
            DecoderStepResult(
                nextId = nextId,
                bufferAcquireUs = bufferAcquireUs,
                inputFillUs = inputFillUs,
                tensorCreateUs = tensorCreateUs,
                runUs = runUs,
                logitsReadUs = logitsReadUs,
                argmaxUs = argmaxUs,
                inputDirect = inputDirect,
                bufferReused = lease.reused,
                tensorOwnsBuffer = tensorOwnsBuffer,
            )
        }
    }

    /** ONNX result 出 use 块后底层 buffer 会释放，必须 deep copy。 */
    private fun tensorElementCount(shape: LongArray): Int {
        val count = shape.fold(1L) { total, dimension ->
            require(dimension > 0L) { "Unexpected MangaOcr tensor shape: ${shape.contentToString()}" }
            require(total <= Int.MAX_VALUE / dimension) {
                "MangaOcr tensor is too large: ${shape.contentToString()}"
            }
            total * dimension
        }
        return count.toInt()
    }

    private fun captureInferenceRuntimeSnapshot(
        includeSystemStats: Boolean,
        startingBoundary: Boolean,
    ): InferenceRuntimeSnapshot {
        val boundaryNs = if (startingBoundary) null else SystemClock.elapsedRealtimeNanos()
        val thread = Thread.currentThread()
        val threadId = Process.myTid()
        val processCpuMs = Process.getElapsedCpuTime()
        val callerThreadCpuNs = Debug.threadCpuTimeNanos().takeIf { it >= 0L }
        val callerThreadPriority = runCatching {
            Process.getThreadPriority(threadId)
        }.getOrNull()
        val javaHeapUsedBytes = if (includeSystemStats) {
            Runtime.getRuntime().let { runtime -> runtime.totalMemory() - runtime.freeMemory() }
        } else {
            0L
        }
        val nativeHeapAllocatedBytes = if (includeSystemStats) {
            Debug.getNativeHeapAllocatedSize()
        } else {
            0L
        }
        val gcCount = runtimeStatLong("art.gc.gc-count", includeSystemStats)
        val gcTimeMs = runtimeStatLong("art.gc.gc-time", includeSystemStats)
        val blockingGcCount = runtimeStatLong("art.gc.blocking-gc-count", includeSystemStats)
        val blockingGcTimeMs = runtimeStatLong("art.gc.blocking-gc-time", includeSystemStats)
        val thermalStatus = if (includeSystemStats && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { powerManager?.currentThermalStatus }.getOrNull()
        } else {
            null
        }
        return InferenceRuntimeSnapshot(
            elapsedRealtimeNs = boundaryNs ?: SystemClock.elapsedRealtimeNanos(),
            processCpuMs = processCpuMs,
            callerThreadCpuNs = callerThreadCpuNs,
            callerThreadId = threadId,
            callerThreadName = thread.name,
            callerThreadPriority = callerThreadPriority,
            gcCount = gcCount,
            gcTimeMs = gcTimeMs,
            blockingGcCount = blockingGcCount,
            blockingGcTimeMs = blockingGcTimeMs,
            javaHeapUsedBytes = javaHeapUsedBytes,
            nativeHeapAllocatedBytes = nativeHeapAllocatedBytes,
            thermalStatus = thermalStatus,
        )
    }

    private fun runtimeStatLong(name: String, enabled: Boolean): Long? =
        if (enabled) Debug.getRuntimeStat(name)?.toLongOrNull() else null

    private fun logRuntimeDetail(
        scope: String,
        start: InferenceRuntimeSnapshot,
        end: InferenceRuntimeSnapshot,
    ) {
        val runtime = InferenceDiagnostics.runtimeDelta(start, end)
        Timber.tag(RUNTIME_DETAIL_TAG).i(
            "scope=%s wallUs=%d processCpuMs=%d processCpuCorePermille=%d callerThreadCpuUs=%d callerCpuWallPermille=%d thread=%s(tid=%d,priority=%d)->%s(tid=%d,priority=%d) gc(count=%d,timeMs=%d,blockingCount=%d,blockingTimeMs=%d) heap(javaDeltaBytes=%d,nativeDeltaBytes=%d,javaEndBytes=%d,nativeEndBytes=%d) thermal=%s->%s availableProcessors=%d ortThreads=%d",
            scope,
            runtime.wallUs,
            runtime.processCpuMs,
            runtime.processCpuCorePermille ?: -1,
            runtime.callerThreadCpuUs ?: -1L,
            runtime.callerThreadCpuWallPermille ?: -1,
            runtime.callerThreadStartName ?: "unknown",
            runtime.callerThreadStartId ?: -1,
            runtime.callerThreadStartPriority ?: -1,
            runtime.callerThreadEndName ?: "unknown",
            runtime.callerThreadEndId ?: -1,
            runtime.callerThreadEndPriority ?: -1,
            runtime.gcCount ?: -1L,
            runtime.gcTimeMs ?: -1L,
            runtime.blockingGcCount ?: -1L,
            runtime.blockingGcTimeMs ?: -1L,
            runtime.javaHeapDeltaBytes,
            runtime.nativeHeapDeltaBytes,
            runtime.javaHeapEndBytes,
            runtime.nativeHeapEndBytes,
            InferenceDiagnostics.thermalStatusName(runtime.thermalStart),
            InferenceDiagnostics.thermalStatusName(runtime.thermalEnd),
            availableProcessors,
            ortThreads,
        )
    }

    private fun logEncoderDetail(
        scope: String,
        timing: MangaEncoderTiming,
        cropWidth: Int,
        cropHeight: Int,
    ) {
        Timber.tag(ENCODER_DETAIL_TAG).i(
            "scope=%s crop=%dx%d timingUs total=%d preprocess=%d(scale=%d,gray=%d,pixels=%d,nchw=%d) inputBuffer(acquire=%d,fill=%d,reused=%s,capacityFloats=%d) inputTensor=%d run=%d outputLookup=%d hiddenRetained=true other=%d inputFloats=%d inputBytes=%d inputDirect=%s inputTensorOwnsBuffer=%s hiddenFloats=%d hiddenBytes=%d",
            scope,
            cropWidth,
            cropHeight,
            timing.totalUs,
            timing.preprocessUs,
            timing.scaleUs,
            timing.grayUs,
            timing.pixelReadUs,
            timing.nchwUs,
            timing.inputBufferAcquireUs,
            timing.inputBufferFillUs,
            timing.inputBufferReused,
            timing.inputBufferCapacityFloats,
            timing.inputTensorCreateUs,
            timing.runUs,
            timing.outputLookupUs,
            timing.otherUs,
            timing.inputFloats,
            timing.inputFloats.toLong() * Float.SIZE_BYTES,
            timing.inputDirect,
            timing.inputTensorOwnsBuffer,
            timing.hiddenFloats,
            timing.hiddenFloats.toLong() * Float.SIZE_BYTES,
        )
    }

    private fun logDecoderDetail(
        scope: String,
        timing: MangaDecoderTiming,
    ) {
        val runtime = timing.runtime
        val stepRun = timing.stepRun
        val slowest = stepRun.slowest.joinToString(separator = ",") {
            "${it.stepIndex}:${it.inputLength}:${it.runUs}"
        }
        Timber.tag(DECODER_DETAIL_TAG).i(
            "scope=%s timingUs total=%d bufferAcquire=%d inputFill=%d tensorCreate=%d run=%d logitsRead=%d argmax=%d decode=%d other=%d steps=%d nonDirectInputs=%d reusedInputs=%d tensorOwnedCopies=%d runtime(processCpuMs=%d,processCpuCorePermille=%d,callerThreadCpuUs=%d,callerCpuWallPermille=%d,thread=%s/%d/p%d->%s/%d/p%d) stepRunUs(count=%d,avg=%d,min=%d,p50=%d,p90=%d,p95=%d,max=%d,firstQuarterAvg=%d,lastQuarterAvg=%d,slowestStepInputRun=%s)",
            scope,
            timing.totalUs,
            timing.bufferAcquireUs,
            timing.inputFillUs,
            timing.tensorCreateUs,
            timing.runUs,
            timing.logitsReadUs,
            timing.argmaxUs,
            timing.decodeUs,
            timing.otherUs,
            timing.steps,
            timing.nonDirectInputs,
            timing.reusedInputs,
            timing.tensorOwnedCopies,
            runtime?.processCpuMs ?: -1L,
            runtime?.processCpuCorePermille ?: -1,
            runtime?.callerThreadCpuUs ?: -1L,
            runtime?.callerThreadCpuWallPermille ?: -1,
            runtime?.callerThreadStartName ?: "unknown",
            runtime?.callerThreadStartId ?: -1,
            runtime?.callerThreadStartPriority ?: -1,
            runtime?.callerThreadEndName ?: "unknown",
            runtime?.callerThreadEndId ?: -1,
            runtime?.callerThreadEndPriority ?: -1,
            stepRun.count,
            stepRun.averageUs,
            stepRun.minUs,
            stepRun.p50Us,
            stepRun.p90Us,
            stepRun.p95Us,
            stepRun.maxUs,
            stepRun.firstQuarterAverageUs,
            stepRun.lastQuarterAverageUs,
            slowest.ifEmpty { "none" },
        )
    }

    /** logits shape [seq, vocab]，取最后一步 argmax */
    private fun argmaxLastStep(logits: Array<FloatArray>): Int {
        val last = logits[logits.size - 1]
        var best = 0
        var bestVal = last[0]
        for (i in 1 until last.size) {
            if (last[i] > bestVal) { bestVal = last[i]; best = i }
        }
        return best
    }

    /** 拼接 ids[fromIdx until toIdx] 对应的字符串。跳过 special tokens (id ≤ MASK_ID)；strip `##` WordPiece 前缀。 */
    private fun decodeTokens(ids: IntArray, fromIdx: Int, toIdx: Int): String {
        val sb = StringBuilder()
        for (i in fromIdx until toIdx) {
            val tid = ids[i]
            if (tid <= MASK_ID) continue
            val tok = id2tok.getOrNull(tid) ?: continue
            sb.append(if (tok.startsWith("##")) tok.substring(2) else tok)
        }
        return sb.toString()
    }

    /**
     * 224×224 squash + L→RGB + (x/255 - 0.5)/0.5 + NCHW float[]。
     * 与 PoC `preprocess_224` 1:1 等价（manga-ocr 官方 ViTImageProcessor 行为）。
     */
    private fun preprocess224Timed(crop: Bitmap): MangaPreprocessResult {
        val totalStartedAtNs = SystemClock.elapsedRealtimeNanos()
        // L→RGB：强制把任何输入转灰度再扩到 3 通道，与 kha-white 训练流程一致
        val scaleStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val squashed = if (crop.width == IMG_SIZE && crop.height == IMG_SIZE) crop
        else Bitmap.createScaledBitmap(crop, IMG_SIZE, IMG_SIZE, true)
        val scaleUs = InferenceTiming.elapsedUs(scaleStartedAtNs, SystemClock.elapsedRealtimeNanos())

        val grayStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val grayRgb = toGrayThenRgb(squashed)
        if (squashed !== crop) squashed.recycle()
        val grayUs = InferenceTiming.elapsedUs(grayStartedAtNs, SystemClock.elapsedRealtimeNanos())

        val pixelReadStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val pixels = IntArray(IMG_SIZE * IMG_SIZE)
        grayRgb.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)
        grayRgb.recycle()
        val pixelReadUs = InferenceTiming.elapsedUs(
            pixelReadStartedAtNs,
            SystemClock.elapsedRealtimeNanos(),
        )

        // NCHW: channel-major
        val nchwStartedAtNs = SystemClock.elapsedRealtimeNanos()
        val arr = FloatArray(3 * IMG_SIZE * IMG_SIZE)
        val plane = IMG_SIZE * IMG_SIZE
        for (i in 0 until plane) {
            val p = pixels[i]
            // 灰度后 R=G=B；按 (x/255-0.5)/0.5 = (x-127.5)/127.5
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            arr[i] = (r - 0.5f) / 0.5f
            arr[plane + i] = (g - 0.5f) / 0.5f
            arr[2 * plane + i] = (b - 0.5f) / 0.5f
        }
        val nchwUs = InferenceTiming.elapsedUs(nchwStartedAtNs, SystemClock.elapsedRealtimeNanos())
        val totalUs = InferenceTiming.elapsedUs(totalStartedAtNs, SystemClock.elapsedRealtimeNanos())
        val summary = InferenceTiming.stageSummary(
            totalUs = totalUs,
            stagesUs = listOf(scaleUs, grayUs, pixelReadUs, nchwUs),
        )
        return MangaPreprocessResult(
            data = arr,
            totalUs = totalUs,
            scaleUs = scaleUs,
            grayUs = grayUs,
            pixelReadUs = pixelReadUs,
            nchwUs = nchwUs,
            otherUs = summary.unaccountedUs,
        )
    }

    /**
     * `PIL.Image.convert('L').convert('RGB')` 的 Kotlin 等价：先按 luminance 灰度化（ITU-R BT.601），
     * 再三通道复制。manga-ocr 训练时 PIL 这一步必须复刻，否则识别质量明显下降。
     */
    private fun toGrayThenRgb(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = true
            // BT.601 灰度变换矩阵：所有通道用 0.299 R + 0.587 G + 0.114 B
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    override fun close() {
        runCatching { encSession?.close() }
        runCatching { decSession?.close() }
        runCatching { encSessionOptions?.close() }
        runCatching { decSessionOptions?.close() }
        encSession = null
        decSession = null
        encSessionOptions = null
        decSessionOptions = null
        inferenceWarmupCompleted = false
        directBufferPool.clear()
        // env 是 ORT 全局单例，不在这里关；与 PaddleOcrEngine.close() 行为一致
    }

    companion object {
        private const val PERF_TAG = "MangaOcrPerf"
        private const val ENCODER_DETAIL_TAG = "MangaEncoderDetail"
        private const val DECODER_DETAIL_TAG = "MangaDecoderDetail"
        private const val RUNTIME_DETAIL_TAG = "MangaRuntimeDetail"
        const val PAD_ID = 0
        const val UNK_ID = 1
        const val CLS_ID = 2
        const val SEP_ID = 3
        const val MASK_ID = 4

        /** ViT 输入边长（manga-ocr 训练固定 224×224 squash） */
        const val IMG_SIZE = 224

        /**
         * decoder greedy 上限。比 PoC 用的 64 紧——端侧坏 case 兜底。日漫单气泡实测多数 < 30 token，
         * 极少超过 40。打满说明气泡 crop 过大或模型走偏，加 hit_limit 警告比硬等更安全。
         */
        const val MAX_TOKENS = 48

        const val CLUSTER_GAP_PX = 18
    }
}

private inline fun <T> traceSection(
    sectionName: String,
    block: () -> T,
): T {
    Trace.beginSection(sectionName)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}
