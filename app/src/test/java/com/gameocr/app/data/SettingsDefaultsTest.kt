package com.gameocr.app.data

import com.gameocr.app.capture.LoopFrameStabilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {

    @Test
    fun paddleModelVersion_defaultsToStableV5AcrossSettingsAndPresets() {
        data class Case(
            val source: String,
            val actual: PaddleModelVersion,
        )

        val cases = listOf(
            Case("settings", Settings().paddleModelVersion),
            Case(
                "translation preset",
                TranslationPreset(id = "test", name = "test").paddleModelVersion,
            ),
        )

        cases.forEach { case ->
            assertEquals(case.source, PaddleModelVersion.V5_MOBILE, case.actual)
        }
    }

    @Test
    fun dbnetUnclipRatio_defaultsKeepPaddleAndMangaOcrSeparate() {
        val settings = Settings()

        assertEquals(1.55f, settings.dbnetUnclipRatio, 0.0001f)
        assertEquals(1.65f, settings.mangaOcrDbnetUnclipRatio, 0.0001f)
    }

    @Test
    fun paddleDetectionAndMangaCropPadding_defaultsAreStable() {
        val settings = Settings()

        assertEquals(PaddleDetectionProfile.FAST, settings.paddleDetectionProfile)
        assertEquals(0, settings.mangaOcrCropPaddingPx)
    }

    @Test
    fun overlayFont_defaultsToSystemFont() {
        val settings = Settings()

        assertEquals("", settings.overlayFontFileName)
        assertEquals("", settings.overlayFontDisplayName)
        assertEquals(emptyList<OverlayFontEntry>(), settings.overlayFonts)
    }

    @Test
    fun textOrientationAutoDetect_defaultsToEnabled() {
        val settings = Settings()

        assertEquals(true, settings.textOrientationAutoDetect)
    }

    @Test
    fun translationContext_defaults_areConservativeAndFollowRecognition() {
        val settings = Settings()

        assertEquals(true, settings.translationOutputFollowRecognition)
        val output = resolveTranslationOutputSettings(
            settings.translationOutputFollowRecognition,
            settings.translationOutputLayout,
            settings.translationOutputDirection,
        )
        assertEquals(true, output.followRecognition)
        assertEquals(TranslationOutputLayout.HORIZONTAL, output.layout)
        assertEquals(TranslationOutputDirection.LEFT_TO_RIGHT, output.direction)
        assertEquals(true, settings.translationGlossaryEnabled)
        assertEquals(ForegroundAppDetectionMode.AUTO, settings.foregroundAppDetectionMode)
        assertEquals(false, settings.sendAppNameToTranslator)
    }

    @Test
    fun translationBlocks_defaultToVisibleCopyButtons() {
        assertEquals(
            TranslationBlockInteractionMode.COPY_BUTTON,
            Settings().translationBlockInteractionMode,
        )
    }

    @Test
    fun loopTextStableDuration_defaultsTo500MillisecondsAcrossSettingsAndRuntimePolicy() {
        assertEquals(500L, Settings().loopTextStableDurationMs)
        assertEquals(500L, LoopFrameStabilityPolicy.DEFAULT_STABLE_DURATION_MS)
    }

    @Test
    fun dbnetUnclipRatioFor_routesOnlyMangaOcrToMangaSpecificMargin() {
        data class Case(
            val engine: OcrEngineKind,
            val expected: Float,
        )

        val settings = Settings(
            dbnetUnclipRatio = 1.31f,
            mangaOcrDbnetUnclipRatio = 1.79f,
        )
        val cases = listOf(
            Case(OcrEngineKind.PADDLE_ONNX, 1.31f),
            Case(OcrEngineKind.MANGA_OCR_JA, 1.79f),
            Case(OcrEngineKind.ML_KIT_AUTO, 1.31f),
        )

        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, settings.dbnetUnclipRatioFor(case.engine), 0.0001f)
        }
    }
}
