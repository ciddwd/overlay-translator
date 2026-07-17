package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.PaddleDetectionProfile

internal data class MangaOcrInferenceWarmupPlan(
    val detectorWidth: Int,
    val detectorHeight: Int,
    val encoderRuns: Int,
    val decoderSteps: Int,
)

internal object MangaOcrStartupPolicy {
    private const val CACHE_SCHEMA_VERSION = 1
    private const val CACHE_MARKER = ".optimized-v"
    private const val DEFAULT_SCREEN_WIDTH = 1440
    private const val DEFAULT_SCREEN_HEIGHT = 3200
    private const val ENCODER_WARMUP_RUNS = 2
    private const val DECODER_WARMUP_STEPS = 1

    fun shouldPrewarm(
        selectedEngine: OcrEngineKind,
        modelInstalled: Boolean,
    ): Boolean = selectedEngine == OcrEngineKind.MANGA_OCR_JA && modelInstalled

    fun shouldRunInferenceWarmup(completed: Boolean): Boolean = !completed

    /**
     * DBNet 真实预热按当前屏幕宽高比构造输入，但把最长边限制在 FAST 的 960。
     *
     * 即使用户选择 ACCURATE，也不在服务启动时分配 1920 边长的巨大临时张量；960 输入已经能让
     * DBNet 权重、CPU kernel 和 ORT arena 走过一遍，准确模式的大尺寸分配留给首次真实截图。
     */
    fun inferenceWarmupPlan(
        screenWidth: Int,
        screenHeight: Int,
    ): MangaOcrInferenceWarmupPlan {
        val safeWidth = screenWidth.takeIf { it > 0 } ?: DEFAULT_SCREEN_WIDTH
        val safeHeight = screenHeight.takeIf { it > 0 } ?: DEFAULT_SCREEN_HEIGHT
        val detectorPlan = PaddleDetectionSizing.plan(
            sourceWidth = safeWidth,
            sourceHeight = safeHeight,
            profile = PaddleDetectionProfile.FAST,
        )
        return MangaOcrInferenceWarmupPlan(
            detectorWidth = detectorPlan.targetWidth,
            detectorHeight = detectorPlan.targetHeight,
            encoderRuns = ENCODER_WARMUP_RUNS,
            decoderSteps = DECODER_WARMUP_STEPS,
        )
    }

    fun optimizedCacheFileName(
        sourceFileName: String,
        sourceLength: Long,
        sourceLastModified: Long,
        runtimeSignature: String,
    ): String {
        val stem = sourceFileName.substringBeforeLast('.', sourceFileName)
        val safeRuntimeSignature = runtimeSignature
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "unknown" }
        return "$stem$CACHE_MARKER$CACHE_SCHEMA_VERSION-$safeRuntimeSignature-" +
            "${sourceLength.coerceAtLeast(0L)}-${sourceLastModified.coerceAtLeast(0L)}.onnx"
    }

    fun isOptimizedCacheFileFor(
        sourceFileName: String,
        candidateFileName: String,
    ): Boolean {
        val stem = sourceFileName.substringBeforeLast('.', sourceFileName)
        return candidateFileName.startsWith(stem + CACHE_MARKER)
    }

    fun isReusableOptimizedCache(cacheLength: Long): Boolean = cacheLength > 0L
}
