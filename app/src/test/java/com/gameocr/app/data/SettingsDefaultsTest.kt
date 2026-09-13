package com.gameocr.app.data

import com.gameocr.app.capture.LoopFrameStabilityPolicy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDefaultsTest {

    @Test
    fun loopTriggerDefaultsToSettledButKeepsExplicitChoices_tableDriven() {
        assertEquals(LoopTriggerMode.SETTLED_PAGE, Settings().loopTriggerMode)
        val cases = listOf(
            "{}" to LoopTriggerMode.SETTLED_PAGE,
            "{\"loopTriggerMode\":\"FIXED_INTERVAL\"}" to LoopTriggerMode.FIXED_INTERVAL,
            "{\"loopTriggerMode\":\"WAIT_FOR_TEXT_COMPLETE\"}" to LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE,
            "{\"loopTriggerMode\":\"SETTLED_PAGE\"}" to LoopTriggerMode.SETTLED_PAGE,
        )
        cases.forEach { (encoded, expected) ->
            assertEquals(encoded, expected, Json.decodeFromString(Settings.serializer(), encoded).loopTriggerMode)
        }
    }

    @Test
    fun dictionaryLookupMode_defaultsOnlineAndHonorsExplicitSerializedChoices_tableDriven() {
        assertEquals(DictionaryLookupMode.ONLINE, Settings().dictionaryLookupMode)
        val cases = listOf(
            "{}" to DictionaryLookupMode.ONLINE,
            "{\"dictionaryLookupMode\":\"OFFLINE\"}" to DictionaryLookupMode.OFFLINE,
            "{\"dictionaryLookupMode\":\"ONLINE\"}" to DictionaryLookupMode.ONLINE,
        )
        cases.forEach { (serialized, expected) ->
            assertEquals(serialized, expected, Json.decodeFromString(Settings.serializer(), serialized).dictionaryLookupMode)
        }
    }

    @Test
    fun wordSelectDefaults_tableDriven_keepSelectionMemoryOff() {
        data class Case(
            val name: String,
            val actual: Boolean,
            val expected: Boolean,
        )

        val settings = Settings()
        listOf(
            Case("precise adjustment", settings.wordSelectPreciseAdjust, true),
            Case("translation card", settings.wordSelectCardMode, true),
            Case("remember selection", settings.wordSelectRememberRegion, false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.actual)
        }
    }

    @Test
    fun anthropicDefaults_areStableAcrossSettingsAndPresets() {
        data class Case(val name: String, val baseUrl: String, val model: String)

        val cases = listOf(
            Case("settings", Settings().anthropicBaseUrl, Settings().anthropicModel),
            Case(
                "translation preset",
                TranslationPreset(id = "test", name = "test").anthropicBaseUrl,
                TranslationPreset(id = "test", name = "test").anthropicModel,
            ),
        )

        cases.forEach { case ->
            assertEquals(case.name, DEFAULT_ANTHROPIC_BASE_URL, case.baseUrl)
            assertEquals(case.name, DEFAULT_ANTHROPIC_MODEL, case.model)
        }
    }

    @Test
    fun developerDiagnostics_defaultToOff() {
        data class Case(val name: String, val actual: Boolean)

        val settings = Settings()
        listOf(
            Case("developer options", settings.developerOptionsEnabled),
            Case("performance overlay", settings.performanceOverlayEnabled),
            Case("OCR screenshot saving", settings.ocrScreenshotSavingEnabled),
            Case("disable translation cache", settings.disableTranslationCache),
        ).forEach { case ->
            assertEquals(case.name, false, case.actual)
        }
    }

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
    fun paddleDetectionAndRetiredMangaAdvancedSettings_defaultsAreStable() {
        assertEquals(PaddleDetectionProfile.FAST, Settings().paddleDetectionProfile)

        data class Case(val name: String, val gap: Int, val cropPadding: Int)
        val cases = listOf(
            Case(
                "settings",
                Settings().bubbleClusterGap,
                Settings().mangaOcrCropPaddingPx,
            ),
            Case(
                "translation preset",
                TranslationPreset(id = "test", name = "test").bubbleClusterGap,
                TranslationPreset(id = "test", name = "test").mangaOcrCropPaddingPx,
            ),
        )

        cases.forEach { case ->
            assertEquals("${case.name} bubble gap", 0, case.gap)
            assertEquals("${case.name} crop padding", 0, case.cropPadding)
        }
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
        assertEquals(CaptureContentOrientation.AUTO, settings.captureContentOrientation)
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
