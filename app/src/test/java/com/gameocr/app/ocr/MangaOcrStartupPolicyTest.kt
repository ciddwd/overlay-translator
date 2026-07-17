package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaOcrStartupPolicyTest {

    @Test
    fun prewarmPolicy_coversEngineAndInstallationCases() {
        data class Case(
            val name: String,
            val engine: OcrEngineKind,
            val installed: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("manga installed", OcrEngineKind.MANGA_OCR_JA, true, true),
            Case("manga missing", OcrEngineKind.MANGA_OCR_JA, false, false),
            Case("paddle installed", OcrEngineKind.PADDLE_ONNX, true, false),
            Case("ml kit installed flag is irrelevant", OcrEngineKind.ML_KIT_JAPANESE, true, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MangaOcrStartupPolicy.shouldPrewarm(case.engine, case.installed),
            )
        }
    }

    @Test
    fun inferenceWarmupPlan_coversOrientationsSmallScreensAndFallbacks() {
        data class Case(
            val name: String,
            val screenWidth: Int,
            val screenHeight: Int,
            val expectedDetectorWidth: Int,
            val expectedDetectorHeight: Int,
        )

        listOf(
            Case(
                name = "portrait 1440x3200",
                screenWidth = 1440,
                screenHeight = 3200,
                expectedDetectorWidth = 448,
                expectedDetectorHeight = 960,
            ),
            Case(
                name = "landscape 3200x1440",
                screenWidth = 3200,
                screenHeight = 1440,
                expectedDetectorWidth = 960,
                expectedDetectorHeight = 448,
            ),
            Case(
                name = "small aligned screen is not upscaled",
                screenWidth = 640,
                screenHeight = 480,
                expectedDetectorWidth = 640,
                expectedDetectorHeight = 480,
            ),
            Case(
                name = "invalid display metrics use portrait fallback",
                screenWidth = 0,
                screenHeight = -1,
                expectedDetectorWidth = 448,
                expectedDetectorHeight = 960,
            ),
        ).forEach { case ->
            val plan = MangaOcrStartupPolicy.inferenceWarmupPlan(
                screenWidth = case.screenWidth,
                screenHeight = case.screenHeight,
            )
            assertEquals(case.name, case.expectedDetectorWidth, plan.detectorWidth)
            assertEquals(case.name, case.expectedDetectorHeight, plan.detectorHeight)
            assertEquals(case.name, 2, plan.encoderRuns)
            assertEquals(case.name, 1, plan.decoderSteps)
        }
    }

    @Test
    fun inferenceWarmupCompletionPolicy_allowsRetryButSkipsCompletedWork() {
        data class Case(
            val name: String,
            val completed: Boolean,
            val expectedShouldRun: Boolean,
        )

        listOf(
            Case("fresh engine", completed = false, expectedShouldRun = true),
            Case("failed or cancelled warmup remains retryable", completed = false, expectedShouldRun = true),
            Case("successful warmup is idempotent", completed = true, expectedShouldRun = false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedShouldRun,
                MangaOcrStartupPolicy.shouldRunInferenceWarmup(case.completed),
            )
        }
    }

    @Test
    fun optimizedCacheFileName_coversModelFingerprintCases() {
        data class Case(
            val name: String,
            val sourceName: String,
            val length: Long,
            val lastModified: Long,
            val runtimeSignature: String,
            val expected: String,
        )

        listOf(
            Case(
                name = "encoder standard",
                sourceName = "encoder_model.onnx",
                length = 22_000_000L,
                lastModified = 1234L,
                runtimeSignature = "cpu-all-t6-ort1.20.0",
                expected = "encoder_model.optimized-v1-cpu-all-t6-ort1.20.0-22000000-1234.onnx",
            ),
            Case(
                name = "decoder version is filename safe",
                sourceName = "decoder_model.onnx",
                length = 118_000_000L,
                lastModified = 5678L,
                runtimeSignature = "cpu all / 1.20.0 (android arm64)",
                expected = "decoder_model.optimized-v1-cpu_all___1.20.0__android_arm64_-118000000-5678.onnx",
            ),
            Case(
                name = "invalid metadata is clamped",
                sourceName = "model",
                length = -1L,
                lastModified = -2L,
                runtimeSignature = "",
                expected = "model.optimized-v1-unknown-0-0.onnx",
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MangaOcrStartupPolicy.optimizedCacheFileName(
                    sourceFileName = case.sourceName,
                    sourceLength = case.length,
                    sourceLastModified = case.lastModified,
                    runtimeSignature = case.runtimeSignature,
                ),
            )
        }
    }

    @Test
    fun cacheRecognition_coversStaleAndPartialCases() {
        data class Case(
            val name: String,
            val sourceName: String,
            val candidateName: String,
            val candidateLength: Long,
            val expectedOwnedBySource: Boolean,
            val expectedReusable: Boolean,
        )

        listOf(
            Case(
                name = "current encoder cache",
                sourceName = "encoder_model.onnx",
                candidateName = "encoder_model.optimized-v1-1.20.0-22000000-1234.onnx",
                candidateLength = 21_000_000L,
                expectedOwnedBySource = true,
                expectedReusable = true,
            ),
            Case(
                name = "stale encoder temp is owned but incomplete",
                sourceName = "encoder_model.onnx",
                candidateName = "encoder_model.optimized-v1-old.onnx.tmp",
                candidateLength = 0L,
                expectedOwnedBySource = true,
                expectedReusable = false,
            ),
            Case(
                name = "decoder cache does not belong to encoder",
                sourceName = "encoder_model.onnx",
                candidateName = "decoder_model.optimized-v1-1.20.0-118000000-5678.onnx",
                candidateLength = 110_000_000L,
                expectedOwnedBySource = false,
                expectedReusable = true,
            ),
        ).forEach { case ->
            assertEquals(
                "${case.name} ownership",
                case.expectedOwnedBySource,
                MangaOcrStartupPolicy.isOptimizedCacheFileFor(
                    case.sourceName,
                    case.candidateName,
                ),
            )
            assertEquals(
                "${case.name} reusable",
                case.expectedReusable,
                MangaOcrStartupPolicy.isReusableOptimizedCache(case.candidateLength),
            )
        }
    }
}
