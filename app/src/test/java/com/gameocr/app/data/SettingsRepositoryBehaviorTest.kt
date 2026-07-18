package com.gameocr.app.data

import android.content.Context
import android.content.ContextWrapper
import com.gameocr.app.capture.CaptureRegion
import com.gameocr.app.ocr.TextOrientation
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryBehaviorTest {

    @Test
    fun rescaleCaptureRegion_tableDriven_migratesWorkspaceAndOrientationCoordinates() = runBlocking {
        data class Case(
            val name: String,
            val region: CaptureRegion,
            val savedWidth: Int,
            val savedHeight: Int,
            val currentWidth: Int,
            val currentHeight: Int,
            val expectedRegion: CaptureRegion,
        )

        val cases = listOf(
            Case(
                name = "old HyperOS workspace width migrates to physical width",
                region = CaptureRegion(0, 0, 3053, 1440),
                savedWidth = 3053,
                savedHeight = 1440,
                currentWidth = 3200,
                currentHeight = 1440,
                expectedRegion = CaptureRegion(0, 0, 3200, 1440),
            ),
            Case(
                name = "same screen keeps coordinates",
                region = CaptureRegion(100, 200, 1000, 900),
                savedWidth = 3200,
                savedHeight = 1440,
                currentWidth = 3200,
                currentHeight = 1440,
                expectedRegion = CaptureRegion(100, 200, 1000, 900),
            ),
            Case(
                name = "orientation change scales both axes",
                region = CaptureRegion(144, 320, 720, 1600),
                savedWidth = 1440,
                savedHeight = 3200,
                currentWidth = 3200,
                currentHeight = 1440,
                expectedRegion = CaptureRegion(320, 144, 1600, 720),
            ),
            Case(
                name = "scaled out of range coordinates clamp to physical screen",
                region = CaptureRegion(-100, -100, 4000, 2000),
                savedWidth = 1600,
                savedHeight = 720,
                currentWidth = 3200,
                currentHeight = 1440,
                expectedRegion = CaptureRegion(0, 0, 3200, 1440),
            ),
            Case(
                name = "missing saved metadata preserves region",
                region = CaptureRegion(120, 240, 960, 1200),
                savedWidth = 0,
                savedHeight = 0,
                currentWidth = 1440,
                currentHeight = 3200,
                expectedRegion = CaptureRegion(120, 240, 960, 1200),
            ),
        )

        val root = Files.createTempDirectory("settings-region-rescale-test").toFile()
        val repository = fileBackedRepository(root)
        cases.forEach { case ->
            repository.update {
                Settings(
                    captureRegion = case.region,
                    captureRegionSavedScreenW = case.savedWidth,
                    captureRegionSavedScreenH = case.savedHeight,
                )
            }
            repository.rescaleCaptureRegionIfNeeded(case.currentWidth, case.currentHeight)
            val actual = repository.get()
            assertEquals("${case.name} region", case.expectedRegion, actual.captureRegion)
            assertEquals("${case.name} saved width", case.currentWidth, actual.captureRegionSavedScreenW)
            assertEquals("${case.name} saved height", case.currentHeight, actual.captureRegionSavedScreenH)
        }
    }

    @Test
    fun repository_roundTripsACompleteNonDefaultSettingsObject() = runBlocking {
        val root = Files.createTempDirectory("settings-repository-test").toFile()
        val repository = fileBackedRepository(root)
        val fontName = "${"b".repeat(64)}.ttf"
        val preset = TranslationPreset(id = "custom_roundtrip", name = "Round trip")
        val requested = Settings(
            baseUrl = "https://roundtrip.example/v1/",
            apiKey = "api-key",
            model = "roundtrip-model",
            sourceLang = "ja",
            targetLang = "zh-TW",
            promptTemplate = "roundtrip prompt",
            ocrEngine = OcrEngineKind.PADDLE_ONNX,
            captureLoopIntervalMs = 4321L,
            loopTriggerMode = LoopTriggerMode.FIXED_INTERVAL,
            loopTextStableDurationMs = 1300L,
            loopSkipSimilarFrames = false,
            loopFrameSimilarityThreshold = 0.83f,
            loopTextRegionMode = LoopTextRegionMode.ANYWHERE,
            loopTranslateRegionOnly = false,
            developerOptionsEnabled = true,
            ocrRedBoxModeEnabled = true,
            ocrRedBoxShowSourceText = false,
            ocrRedBoxShowTranslation = true,
            captureRegion = CaptureRegion(11, 22, 333, 444),
            captureRegionSavedScreenW = 1920,
            captureRegionSavedScreenH = 1080,
            overlayTextSizeSp = 22,
            overlayTextStyle = OverlayTextStyle(
                bold = true,
                italic = true,
                underline = true,
                letterSpacingEm = 0.12f,
                lineSpacingMultiplier = 1.6f,
                alignment = OverlayTextAlignment.END,
                strokeEnabled = true,
                shadowEnabled = true,
            ),
            overlayAlpha = 0.62f,
            overlayFontFileName = fontName,
            overlayFontDisplayName = "Roundtrip.ttf",
            overlayFonts = listOf(OverlayFontEntry(fontName, "Roundtrip.ttf")),
            streamingTranslate = false,
            retryEmptyTranslation = true,
            renderMode = RenderMode.FLOATING_WINDOW,
            translationBlockInteractionMode = TranslationBlockInteractionMode.OPEN_COPY_PANEL,
            overlayPlacement = OverlayPlacement.ABOVE,
            overlayTheme = OverlayTheme.CUSTOM,
            customBgColor = 0xAA102030.toInt(),
            customFgColor = 0xFF405060.toInt(),
            customBorderColor = 0xCC708090.toInt(),
            customBorderWidth = 4,
            overlayOffsetX = 31,
            overlayOffsetY = -17,
            preprocess = PreprocessOptions(upscale2x = true, invert = true, binarize = true),
            textOrientationAutoDetect = false,
            manualTextOrientation = TextOrientation.VERTICAL_RTL,
            translationOutputFollowRecognition = false,
            translationOutputLayout = TranslationOutputLayout.VERTICAL,
            translationOutputDirection = TranslationOutputDirection.RIGHT_TO_LEFT,
            baiduOcrApiKey = "baidu-key",
            baiduOcrSecretKey = "baidu-secret",
            baiduOcrEndpoint = BaiduOcrEndpoint.ACCURATE_BASIC,
            baiduOcrLanguage = BaiduOcrLanguage.JAP,
            umiOcrBaseUrl = "http://127.0.0.1:1224/api/ocr",
            lunaOcrBaseUrl = "http://127.0.0.1:2333/api/ocr",
            paddleAiStudioToken = "paddle-token",
            tencentSecretId = "tencent-id",
            tencentSecretKey = "tencent-secret",
            tencentRegion = "ap-singapore",
            tencentOcrEndpoint = TencentOcrEndpoint.GENERAL_ACCURATE,
            tencentOcrLanguage = TencentOcrLanguage.ZH_RARE,
            paddleModelVersion = PaddleModelVersion.V5_MOBILE,
            paddleModelMirrorUrl = "https://mirror.example/paddle/",
            mangaOcrModelMirrorUrl = "https://mirror.example/manga/",
            orientationModelMirrorUrl = "https://mirror.example/orientation/",
            preferShizukuCapture = true,
            a11yVolumeTrigger = true,
            translatorEngine = TranslatorEngine.DEEPL,
            translationGlossaryEnabled = false,
            foregroundAppDetectionMode = ForegroundAppDetectionMode.USAGE_ACCESS,
            sendAppNameToTranslator = true,
            deeplApiKey = "deepl-key",
            deeplPro = true,
            deeplProtocol = DeeplProtocol.DEEPLX,
            deeplBaseUrl = "https://deeplx.example/",
            deeplBearerAuth = true,
            deeplCustomToken = "deepl-token",
            youdaoAppKey = "youdao-key",
            youdaoAppSecret = "youdao-secret",
            volcAccessKeyId = "volc-id",
            volcSecretAccessKey = "volc-secret",
            volcRegion = "cn-south-1",
            baiduFanyiAppId = "baidu-app-id",
            baiduFanyiSecretKey = "baidu-fanyi-secret",
            floatingButtonSizeDp = 53,
            floatingButtonX = 101,
            floatingButtonY = 202,
            floatingButtonSnapToEdge = false,
            floatingButtonAutoDock = true,
            floatingButtonDockInsetDp = 17,
            floatingWindowX = 303,
            floatingWindowY = 404,
            floatingWindowWidthDp = 455,
            floatingWindowHeightDp = 233,
            floatingWindowContentMode = FloatingWindowContentMode.DST_ONLY,
            floatingWindowLocked = true,
            customBorderStyle = BorderStyle.DOTTED,
            overlayAllowWrap = false,
            overlayAvoidCollision = false,
            apiTimeoutSeconds = 47,
            mergeAdjacentBlocks = true,
            mergeStrength = MergeStrength.CONSERVATIVE,
            pinnedLanguages = listOf("ja", "zh-TW", "en"),
            cleartextAllowedHosts = listOf("192.168.0.2", "localhost"),
            floatingMenuItemOrder = FloatingMenu.DEFAULT_ORDER.reversed(),
            arcMenuPageSize = 5,
            floatingButtonSkill = FloatingSkill.LOOP,
            dictionaryPrompt = "roundtrip dictionary",
            localLlmTemperature = 0.31f,
            localLlmTopP = 0.42f,
            localLlmTopK = 17,
            localLlmRepetitionPenalty = 1.21f,
            localLlmContextSize = 3072,
            localLlmMaxNewTokens = 333,
            dbnetProbThresh = 0.19f,
            dbnetBoxScoreThresh = 0.44f,
            dbnetUnclipRatio = 1.37f,
            mangaOcrDbnetUnclipRatio = 1.83f,
            bubbleClusterGap = 47,
            mangaOcrCropPaddingPx = 29,
            localLlmMirror = LlmMirrorChoice.CUSTOM,
            localLlmMirrorUrl = "https://mirror.example/llm/",
            translationPresets = listOf(preset),
            activeTranslationPresetId = preset.id,
        )

        repository.update { requested }

        assertEquals(MangaOcrAdvancedSettingsPolicy.normalize(requested), repository.get())
    }

    private fun fileBackedRepository(root: File): SettingsRepository =
        SettingsRepository(FileBackedContext(root), PlainTestCipher).apply {
            setDefaultPromptProvidersForTest(
                prompt = { "default prompt" },
                dictionaryPrompt = { "default dictionary prompt" },
            )
        }

    private class FileBackedContext(private val root: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getPackageName(): String = "com.gameocr.app.repositorytest"
    }

    private object PlainTestCipher : SettingsSecretCipher {
        override fun encrypt(plainText: String): String = "test:$plainText"
        override fun decrypt(cipherText: String): String = cipherText.removePrefix("test:")
    }
}
