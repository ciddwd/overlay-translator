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
    fun repository_roundTripsACompleteNonDefaultSettingsObject() = runBlocking {
        val root = Files.createTempDirectory("settings-repository-test").toFile()
        val context = FileBackedContext(root)
        val repository = SettingsRepository(context, PlainTestCipher).apply {
            setDefaultPromptProvidersForTest(
                prompt = { "default prompt" },
                dictionaryPrompt = { "default dictionary prompt" },
            )
        }
        val fontName = "${"b".repeat(64)}.ttf"
        val preset = TranslationPreset(id = "custom_roundtrip", name = "Round trip")
        val expected = Settings(
            baseUrl = "https://roundtrip.example/v1/",
            apiKey = "api-key",
            model = "roundtrip-model",
            sourceLang = "ja",
            targetLang = "zh-TW",
            promptTemplate = "roundtrip prompt",
            ocrEngine = OcrEngineKind.PADDLE_ONNX,
            captureLoopIntervalMs = 4321L,
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
            renderMode = RenderMode.FLOATING_WINDOW,
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
            floatingButtonSkill = FloatingSkill.WORD_SELECT,
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
            localLlmMirror = LlmMirrorChoice.CUSTOM,
            localLlmMirrorUrl = "https://mirror.example/llm/",
            translationPresets = listOf(preset),
            activeTranslationPresetId = preset.id,
        )

        repository.update { expected }

        assertEquals(expected, repository.get())
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
