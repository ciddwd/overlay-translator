package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPresetTest {

    @Test
    fun mangaBuiltInPresetAppliesOfflineJapaneseMangaModeAndKeepsSecrets() {
        val base = Settings(
            apiKey = "openai-key",
            baiduOcrApiKey = "baidu-key",
            baiduOcrSecretKey = "baidu-secret",
            paddleAiStudioToken = "paddle-ai-studio-token",
            tencentSecretId = "tencent-id",
            tencentSecretKey = "tencent-secret",
            deeplApiKey = "deepl-key",
            deeplCustomToken = "deepl-token",
            youdaoAppKey = "youdao-key",
            youdaoAppSecret = "youdao-secret",
            volcAccessKeyId = "volc-id",
            volcSecretAccessKey = "volc-secret",
            baiduFanyiAppId = "baidu-fanyi-id",
            baiduFanyiSecretKey = "baidu-fanyi-secret"
        )

        val preset = TranslationPresetCatalog.find(
            custom = emptyList(),
            id = TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH
        )!!
        val applied = preset.applyTo(base)

        assertEquals("ja", applied.sourceLang)
        assertEquals("zh-CN", applied.targetLang)
        assertEquals(OcrEngineKind.MANGA_OCR_JA, applied.ocrEngine)
        assertEquals(TranslatorEngine.LOCAL_SAKURA, applied.translatorEngine)
        assertTrue(applied.mergeAdjacentBlocks)
        assertEquals(MergeStrength.AGGRESSIVE, applied.mergeStrength)

        assertEquals(base.apiKey, applied.apiKey)
        assertEquals(base.baiduOcrApiKey, applied.baiduOcrApiKey)
        assertEquals(base.baiduOcrSecretKey, applied.baiduOcrSecretKey)
        assertEquals(base.paddleAiStudioToken, applied.paddleAiStudioToken)
        assertEquals(base.tencentSecretId, applied.tencentSecretId)
        assertEquals(base.tencentSecretKey, applied.tencentSecretKey)
        assertEquals(base.deeplApiKey, applied.deeplApiKey)
        assertEquals(base.deeplCustomToken, applied.deeplCustomToken)
        assertEquals(base.youdaoAppKey, applied.youdaoAppKey)
        assertEquals(base.youdaoAppSecret, applied.youdaoAppSecret)
        assertEquals(base.volcAccessKeyId, applied.volcAccessKeyId)
        assertEquals(base.volcSecretAccessKey, applied.volcSecretAccessKey)
        assertEquals(base.baiduFanyiAppId, applied.baiduFanyiAppId)
        assertEquals(base.baiduFanyiSecretKey, applied.baiduFanyiSecretKey)
    }

    @Test
    fun builtInPresetCatalogContainsOnlyCurrentSystemPresets() {
        data class Case(
            val name: String,
            val id: String,
            val translatorEngine: TranslatorEngine
        )

        val cases = listOf(
            Case(
                name = "manga",
                id = TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH,
                translatorEngine = TranslatorEngine.LOCAL_SAKURA
            )
        )

        val builtIns = TranslationPresetCatalog.builtIns()

        assertEquals(cases.map { it.id }, builtIns.map { it.id })
        cases.forEach { case ->
            val preset = requireNotNull(builtIns.firstOrNull { it.id == case.id }) { case.name }
            assertEquals(case.name, case.translatorEngine, preset.translatorEngine)
        }
        assertFalse(builtIns.any { it.id == "builtin_hymt2_auto_zh" })
    }

    @Test
    fun translationPresetShapeDoesNotContainSecretFields() {
        val fieldNames = TranslationPreset::class.java.declaredFields.map { it.name }.toSet()
        val forbidden = setOf(
            "apiKey",
            "baiduOcrApiKey",
            "baiduOcrSecretKey",
            "paddleAiStudioToken",
            "tencentSecretId",
            "tencentSecretKey",
            "deeplApiKey",
            "deeplCustomToken",
            "youdaoAppKey",
            "youdaoAppSecret",
            "volcAccessKeyId",
            "volcSecretAccessKey",
            "baiduFanyiAppId",
            "baiduFanyiSecretKey"
        )

        assertTrue(fieldNames.intersect(forbidden).isEmpty())
    }

    @Test
    fun customPresetListFiltersBuiltInIdCollisions() {
        data class Case(
            val name: String,
            val id: String
        )

        val cases = listOf(
            Case("manga collision", TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH)
        )

        cases.forEach { case ->
            val custom = TranslationPreset(
                id = case.id,
                name = "shadow",
                shortName = "shadow",
                translatorEngine = TranslatorEngine.OPENAI
            )
            val all = TranslationPresetCatalog.all(listOf(custom))

            assertFalse(case.name, all.any { it.name == "shadow" })
            assertEquals(case.name, 1, all.count { TranslationPresetCatalog.isBuiltIn(it.id) })
        }
    }

    @Test
    fun matchesSettingsIgnoresSecretsButRejectsPresetSettingChanges() {
        val base = Settings(
            apiKey = "openai-key",
            deeplCustomToken = "deepl-token",
            sourceLang = "ja",
            targetLang = "zh-CN",
            dictionaryPrompt = "dictionary prompt v1",
            ocrEngine = OcrEngineKind.MANGA_OCR_JA,
            translatorEngine = TranslatorEngine.LOCAL_SAKURA,
            mergeAdjacentBlocks = true,
            mergeStrength = MergeStrength.AGGRESSIVE,
            textOrientationAutoDetect = true,
            dbnetProbThresh = 0.30f
        )
        val preset = TranslationPresetCatalog.fromSettings(
            id = "custom_1",
            name = "Manga tuned",
            shortName = "Manga",
            settings = base
        )
        val legacyPreset = preset.copy(settingsHash = "")
        val staleHashPreset = preset.copy(settingsHash = "old-version-hash")

        assertTrue(preset.settingsHash.isNotBlank())
        assertTrue(
            TranslationPresetCatalog.matchesSettings(
                preset,
                base.copy(apiKey = "changed-key", deeplCustomToken = "changed-token")
            )
        )
        assertTrue(
            TranslationPresetCatalog.matchesSettings(
                legacyPreset,
                base.copy(apiKey = "changed-key", deeplCustomToken = "changed-token")
            )
        )
        assertTrue(
            TranslationPresetCatalog.matchesSettings(
                staleHashPreset,
                base.copy(apiKey = "changed-key", deeplCustomToken = "changed-token")
            )
        )

        data class Case(
            val name: String,
            val changed: Settings
        )
        val cases = listOf(
            Case("target language", base.copy(targetLang = "en")),
            Case("dictionary prompt", base.copy(dictionaryPrompt = "dictionary prompt v2")),
            Case(
                "overlay text style",
                base.copy(overlayTextStyle = OverlayTextStyle(bold = true, strokeEnabled = true))
            ),
            Case("OCR engine", base.copy(ocrEngine = OcrEngineKind.PADDLE_ONNX)),
            Case("translator engine", base.copy(translatorEngine = TranslatorEngine.LOCAL_HY_MT2)),
            Case("merge strength", base.copy(mergeStrength = MergeStrength.STANDARD)),
            Case("orientation auto detect", base.copy(textOrientationAutoDetect = false)),
            Case("DBNet threshold", base.copy(dbnetProbThresh = 0.25f))
        )

        cases.forEach { case ->
            assertFalse(case.name, TranslationPresetCatalog.matchesSettings(preset, case.changed))
        }
    }

    @Test
    fun translationPresetRoundTripsDictionaryPrompt() {
        val base = Settings(dictionaryPrompt = "custom dictionary prompt")
        val preset = TranslationPresetCatalog.fromSettings(
            id = "custom_dictionary",
            name = "Dictionary",
            shortName = "Dict",
            settings = base
        )
        val applied = preset.applyTo(Settings(dictionaryPrompt = "old prompt"))

        assertEquals("custom dictionary prompt", preset.dictionaryPrompt)
        assertEquals("custom dictionary prompt", applied.dictionaryPrompt)
        assertTrue(TranslationPresetCatalog.matchesSettings(preset, base))
        assertFalse(
            TranslationPresetCatalog.matchesSettings(
                preset,
                base.copy(dictionaryPrompt = "changed dictionary prompt")
            )
        )
    }

    @Test
    fun translationPresetRoundTripsGlobalOverlayTextStyle() {
        val style = OverlayTextStyle(
            bold = true,
            italic = true,
            underline = true,
            letterSpacingEm = 0.12f,
            lineSpacingMultiplier = 1.4f,
            alignment = OverlayTextAlignment.CENTER,
            strokeEnabled = true,
            strokeWidthDp = 2.5f,
            strokeColor = 0xFF102030.toInt(),
            shadowEnabled = true,
            shadowRadiusDp = 5f,
            shadowOffsetXDp = -2f,
            shadowOffsetYDp = 3f,
            shadowColor = 0xAA405060.toInt()
        )
        val base = Settings(
            overlayTheme = OverlayTheme.PAPER_LIGHT,
            overlayTextStyle = style
        )
        val preset = TranslationPresetCatalog.fromSettings(
            id = "custom_text_style",
            name = "Text style",
            shortName = "Style",
            settings = base
        )
        val applied = preset.applyTo(Settings(overlayTheme = OverlayTheme.CUSTOM))

        assertEquals(style, preset.overlayTextStyle)
        assertEquals(style, applied.overlayTextStyle)
        assertEquals(OverlayTheme.PAPER_LIGHT, applied.overlayTheme)
        assertTrue(TranslationPresetCatalog.matchesSettings(preset, base))
    }

    @Test
    fun translationPreset_allContentFieldsRoundTripByBehavior() {
        val preset = TranslationPreset(
            id = "custom_behavior",
            name = "Behavior",
            shortName = "Behave",
            baseUrl = "https://behavior.example/v1/",
            model = "behavior-model",
            sourceLang = "ko",
            targetLang = "en",
            promptTemplate = "behavior prompt",
            dictionaryPrompt = "behavior dictionary",
            ocrEngine = OcrEngineKind.PADDLE_ONNX,
            preprocess = PreprocessOptions(upscale2x = true, invert = true, binarize = true),
            renderMode = RenderMode.FLOATING_WINDOW,
            overlayPlacement = OverlayPlacement.BELOW,
            overlayTheme = OverlayTheme.CUSTOM,
            customBgColor = 0xAA102030.toInt(),
            customFgColor = 0xFF405060.toInt(),
            customBorderColor = 0xCC708090.toInt(),
            customBorderWidth = 5,
            customBorderStyle = BorderStyle.DASHED,
            overlayTextSizeSp = 23,
            overlayTextStyle = OverlayTextStyle(
                bold = true,
                italic = true,
                underline = true,
                letterSpacingEm = 0.13f,
                lineSpacingMultiplier = 1.7f,
                alignment = OverlayTextAlignment.END,
                strokeEnabled = true,
                strokeWidthDp = 2.4f,
                strokeColor = 0xFF112233.toInt(),
                shadowEnabled = true,
                shadowRadiusDp = 4.2f,
                shadowOffsetXDp = -2f,
                shadowOffsetYDp = 3f,
                shadowColor = 0xAA445566.toInt(),
            ),
            overlayAlpha = 0.63f,
            overlayFontFileName = "${"a".repeat(64)}.ttf",
            overlayFontDisplayName = "Behavior.ttf",
            overlayOffsetX = 37,
            overlayOffsetY = -19,
            overlayAllowWrap = false,
            overlayAvoidCollision = false,
            streamingTranslate = false,
            translatorEngine = TranslatorEngine.DEEPL,
            deeplPro = true,
            deeplProtocol = DeeplProtocol.DEEPLX,
            deeplBaseUrl = "https://deeplx.example/",
            deeplBearerAuth = true,
            baiduOcrEndpoint = BaiduOcrEndpoint.ACCURATE,
            baiduOcrLanguage = BaiduOcrLanguage.JAP,
            umiOcrBaseUrl = "http://127.0.0.1:1224/api/ocr",
            lunaOcrBaseUrl = "http://127.0.0.1:2333/api/ocr",
            tencentRegion = "ap-singapore",
            tencentOcrEndpoint = TencentOcrEndpoint.GENERAL_ACCURATE,
            tencentOcrLanguage = TencentOcrLanguage.ZH_RARE,
            paddleModelVersion = PaddleModelVersion.V5_MOBILE,
            apiTimeoutSeconds = 47,
            mergeAdjacentBlocks = true,
            mergeStrength = MergeStrength.CONSERVATIVE,
            textOrientationAutoDetect = false,
            manualTextOrientation = com.gameocr.app.ocr.TextOrientation.VERTICAL_RTL,
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
        )
        val applied = preset.applyTo(Settings())
        val rebuilt = TranslationPresetCatalog.fromSettings(
            id = preset.id,
            name = preset.name,
            shortName = preset.shortName,
            settings = applied,
        )
        val metadata = setOf("id", "name", "shortName", "settingsHash")
        val fields = TranslationPreset::class.java.declaredFields.filterNot {
            java.lang.reflect.Modifier.isStatic(it.modifiers) || it.name in metadata
        }

        fields.forEach { presetField ->
            presetField.isAccessible = true
            val settingsField = Settings::class.java.getDeclaredField(presetField.name).apply {
                isAccessible = true
            }
            val expected = presetField.get(preset)
            assertEquals("applyTo ${presetField.name}", expected, settingsField.get(applied))
            assertEquals("fromSettings ${presetField.name}", expected, presetField.get(rebuilt))
        }
    }
}
