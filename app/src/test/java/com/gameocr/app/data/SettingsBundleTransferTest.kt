package com.gameocr.app.data

import com.gameocr.app.glossary.GlossaryTermCategory
import com.gameocr.app.glossary.GlossaryTermEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBundleTransferTest {

    @Test
    fun portableSettings_excludesEveryCredentialAndKeepsPortableValues() {
        val original = sampleSettings()
        val portable = SettingsBundleTransfer.portableSettings(original)

        data class SecretCase(val name: String, val value: (Settings) -> String)
        val secretCases = listOf(
            SecretCase("OpenAI API key", Settings::apiKey),
            SecretCase("Baidu OCR API key", Settings::baiduOcrApiKey),
            SecretCase("Baidu OCR secret", Settings::baiduOcrSecretKey),
            SecretCase("Paddle token", Settings::paddleAiStudioToken),
            SecretCase("Tencent secret id", Settings::tencentSecretId),
            SecretCase("Tencent secret key", Settings::tencentSecretKey),
            SecretCase("DeepL API key", Settings::deeplApiKey),
            SecretCase("DeepL custom token", Settings::deeplCustomToken),
            SecretCase("Youdao app key", Settings::youdaoAppKey),
            SecretCase("Youdao app secret", Settings::youdaoAppSecret),
            SecretCase("Volc access key", Settings::volcAccessKeyId),
            SecretCase("Volc secret key", Settings::volcSecretAccessKey),
            SecretCase("Baidu Fanyi app id", Settings::baiduFanyiAppId),
            SecretCase("Baidu Fanyi secret", Settings::baiduFanyiSecretKey),
        )
        secretCases.forEach { case ->
            assertTrue(case.name, case.value(original).isNotBlank())
            assertEquals(case.name, "", case.value(portable))
        }

        data class PortableCase(val name: String, val expected: Any?, val actual: Any?)
        val portableCases = listOf(
            PortableCase("base URL", original.baseUrl, portable.baseUrl),
            PortableCase("prompt", original.promptTemplate, portable.promptTemplate),
            PortableCase("loop interval", original.captureLoopIntervalMs, portable.captureLoopIntervalMs),
            PortableCase("loop trigger mode", original.loopTriggerMode, portable.loopTriggerMode),
            PortableCase("text stability wait", original.loopTextStableDurationMs, portable.loopTextStableDurationMs),
            PortableCase("dialogue region mode", original.loopTextRegionMode, portable.loopTextRegionMode),
            PortableCase("translate region only", original.loopTranslateRegionOnly, portable.loopTranslateRegionOnly),
            PortableCase("empty translation retry", original.retryEmptyTranslation, portable.retryEmptyTranslation),
            PortableCase("translation output layout", original.translationOutputLayout, portable.translationOutputLayout),
            PortableCase("translation output direction", original.translationOutputDirection, portable.translationOutputDirection),
            PortableCase("glossary enabled", original.translationGlossaryEnabled, portable.translationGlossaryEnabled),
            PortableCase("foreground application detection", original.foregroundAppDetectionMode, portable.foregroundAppDetectionMode),
            PortableCase("send application name", original.sendAppNameToTranslator, portable.sendAppNameToTranslator),
            PortableCase("developer mode", original.developerOptionsEnabled, portable.developerOptionsEnabled),
            PortableCase("OCR red boxes", original.ocrRedBoxModeEnabled, portable.ocrRedBoxModeEnabled),
            PortableCase("OCR debug source", original.ocrRedBoxShowSourceText, portable.ocrRedBoxShowSourceText),
            PortableCase("OCR debug translation", original.ocrRedBoxShowTranslation, portable.ocrRedBoxShowTranslation),
            PortableCase("overlay style", original.overlayTextStyle, portable.overlayTextStyle),
            PortableCase("floating geometry", original.floatingWindowWidthDp, portable.floatingWindowWidthDp),
            PortableCase("pinned languages", original.pinnedLanguages, portable.pinnedLanguages),
            PortableCase("cleartext hosts", original.cleartextAllowedHosts, portable.cleartextAllowedHosts),
            PortableCase("menu order", original.floatingMenuItemOrder, portable.floatingMenuItemOrder),
            PortableCase("LLM mirror URL", original.localLlmMirrorUrl, portable.localLlmMirrorUrl),
            PortableCase("font list", original.overlayFonts, portable.overlayFonts),
            PortableCase("preset list", original.translationPresets, portable.translationPresets),
        )
        portableCases.forEach { case -> assertEquals(case.name, case.expected, case.actual) }
    }

    @Test
    fun settingsBundle_roundTripsSettingsPresetsAndFontBytes() {
        val original = sampleSettings()
        val fontBytes = "portable-font-fixture".toByteArray()
        val fontFile = File.createTempFile("settings-bundle-font", ".ttf").apply {
            writeBytes(fontBytes)
            deleteOnExit()
        }
        val fontName = storedName(fontBytes)
        val settings = original.copy(
            overlayFontFileName = fontName,
            overlayFontDisplayName = "Portable.ttf",
            overlayFonts = listOf(OverlayFontEntry(fontName, "Portable.ttf")),
        )
        val output = ByteArrayOutputStream()

        val exported = SettingsBundleTransfer.write(output, settings) { requested ->
            fontFile.takeIf { requested == fontName }
        }
        val importedFontBytes = mutableMapOf<String, ByteArray>()
        val decoded = SettingsBundleTransfer.read(ByteArrayInputStream(output.toByteArray())) { font, input ->
            importedFontBytes[font.fileName] = input.readBytes()
        }

        assertEquals(1, exported.presetCount)
        assertEquals(1, exported.fontCount)
        assertFalse(decoded.legacyPresetOnly)
        assertEquals(SettingsBundleTransfer.portableSettings(settings), decoded.settings)
        assertTrue(fontBytes.contentEquals(importedFontBytes.getValue(fontName)))
    }

    @Test
    fun settingsBundle_roundTripsPortableGlossaryTerms() {
        data class GlossaryCase(val name: String, val term: GlossaryTermEntity)
        val cases = listOf(
            GlossaryCase(
                name = "global person",
                term = GlossaryTermEntity(
                    id = 91,
                    sourceLang = "ja",
                    targetLang = "zh-CN",
                    sourceTerm = "アリス",
                    targetTerm = "爱丽丝",
                    category = GlossaryTermCategory.PERSON,
                ),
            ),
            GlossaryCase(
                name = "application term",
                term = GlossaryTermEntity(
                    id = 92,
                    scopePackage = "com.example.game",
                    appLabel = "Example Game",
                    sourceLang = "en",
                    targetLang = "zh-CN",
                    sourceTerm = "Mana",
                    targetTerm = "法力",
                    category = GlossaryTermCategory.TERM,
                    caseSensitive = true,
                ),
            ),
        )
        val output = ByteArrayOutputStream()

        val result = SettingsBundleTransfer.write(
            output = output,
            settings = sampleSettings().copy(
                overlayFontFileName = "",
                overlayFontDisplayName = "",
                overlayFonts = emptyList(),
            ),
            resolveFontFile = { null },
            glossaryTerms = cases.map(GlossaryCase::term),
        )
        val preview = SettingsBundleTransfer.readPreview(ByteArrayInputStream(output.toByteArray()))

        assertEquals(cases.size, result.glossaryTermCount)
        assertEquals(cases.size, preview.glossaryTerms.size)
        cases.zip(preview.glossaryTerms).forEach { (case, actual) ->
            assertEquals(case.name, 0L, actual.id)
            assertEquals(case.name, case.term.scopePackage, actual.scopePackage)
            assertEquals(case.name, case.term.sourceTerm, actual.sourceTerm)
            assertEquals(case.name, case.term.targetTerm, actual.targetTerm)
            assertEquals(case.name, case.term.category, actual.category)
            assertEquals(case.name, case.term.caseSensitive, actual.caseSensitive)
        }
    }

    @Test
    fun mergeImportedSettings_keepsLocalCredentialsAndAppliesPortableSettings() {
        val current = Settings(
            apiKey = "local-openai-key",
            deeplApiKey = "local-deepl-key",
            paddleAiStudioToken = "local-paddle-token",
            overlayFonts = listOf(OverlayFontEntry(storedName("old".toByteArray()), "Old.ttf")),
        )
        val imported = sampleSettings()
        val result = SettingsBundleTransfer.mergeImportedSettings(
            current = current,
            imported = imported,
            availableFonts = imported.overlayFonts + current.overlayFonts,
        )

        assertEquals("local-openai-key", result.settings.apiKey)
        assertEquals("local-deepl-key", result.settings.deeplApiKey)
        assertEquals("local-paddle-token", result.settings.paddleAiStudioToken)
        assertEquals(imported.baseUrl, result.settings.baseUrl)
        assertEquals(imported.captureLoopIntervalMs, result.settings.captureLoopIntervalMs)
        assertEquals(imported.loopTriggerMode, result.settings.loopTriggerMode)
        assertEquals(imported.loopTextStableDurationMs, result.settings.loopTextStableDurationMs)
        assertEquals(imported.loopSkipSimilarFrames, result.settings.loopSkipSimilarFrames)
        assertEquals(imported.loopFrameSimilarityThreshold, result.settings.loopFrameSimilarityThreshold)
        assertEquals(imported.loopTextRegionMode, result.settings.loopTextRegionMode)
        assertEquals(imported.loopTranslateRegionOnly, result.settings.loopTranslateRegionOnly)
        assertEquals(imported.retryEmptyTranslation, result.settings.retryEmptyTranslation)
        assertEquals(imported.developerOptionsEnabled, result.settings.developerOptionsEnabled)
        assertEquals(imported.ocrRedBoxModeEnabled, result.settings.ocrRedBoxModeEnabled)
        assertEquals(imported.ocrRedBoxShowSourceText, result.settings.ocrRedBoxShowSourceText)
        assertEquals(imported.ocrRedBoxShowTranslation, result.settings.ocrRedBoxShowTranslation)
        assertEquals(imported.floatingMenuItemOrder, result.settings.floatingMenuItemOrder)
        assertEquals(imported.overlayFontFileName, result.settings.overlayFontFileName)
        assertTrue(result.settings.overlayFonts.containsAll(current.overlayFonts))
        assertEquals(1, result.presetResult.importedCount)
    }

    @Test
    fun settingsBundleReader_acceptsLegacyPresetExports() {
        val legacyPreset = sampleSettings().translationPresets.single()
        val encoded = TranslationPresetTransfer.encodeEncrypted(listOf(legacyPreset))

        val preview = SettingsBundleTransfer.readPreview(
            ByteArrayInputStream(encoded.toByteArray(Charsets.UTF_8)),
        )

        assertTrue(preview.legacyPresetOnly)
        assertEquals(null, preview.settings)
        assertEquals(listOf(legacyPreset), preview.presets)
        assertTrue(preview.fonts.isEmpty())
        assertTrue(preview.glossaryTerms.isEmpty())
    }

    private fun sampleSettings(): Settings {
        val fontName = storedName("portable".toByteArray())
        val base = Settings(
            baseUrl = "https://portable.example/v1/",
            apiKey = "openai-secret",
            model = "portable-model",
            sourceLang = "ja",
            targetLang = "zh-TW",
            promptTemplate = "portable prompt",
            captureLoopIntervalMs = 3456L,
            loopTriggerMode = LoopTriggerMode.FIXED_INTERVAL,
            loopTextStableDurationMs = 1400L,
            loopSkipSimilarFrames = false,
            loopFrameSimilarityThreshold = 0.87f,
            loopTextRegionMode = LoopTextRegionMode.LOWER_SCREEN_FIRST,
            loopTranslateRegionOnly = false,
            retryEmptyTranslation = true,
            translationOutputLayout = TranslationOutputLayout.VERTICAL,
            translationOutputDirection = TranslationOutputDirection.LEFT_TO_RIGHT,
            translationGlossaryEnabled = false,
            foregroundAppDetectionMode = ForegroundAppDetectionMode.USAGE_ACCESS,
            sendAppNameToTranslator = true,
            developerOptionsEnabled = true,
            ocrRedBoxModeEnabled = true,
            ocrRedBoxShowSourceText = false,
            ocrRedBoxShowTranslation = true,
            overlayTextSizeSp = 21,
            overlayTextStyle = OverlayTextStyle(bold = true, italic = true, underline = true),
            overlayAlpha = 0.61f,
            overlayFontFileName = fontName,
            overlayFontDisplayName = "Portable.ttf",
            overlayFonts = listOf(OverlayFontEntry(fontName, "Portable.ttf")),
            baiduOcrApiKey = "baidu-key",
            baiduOcrSecretKey = "baidu-secret",
            paddleAiStudioToken = "paddle-token",
            tencentSecretId = "tencent-id",
            tencentSecretKey = "tencent-secret",
            deeplApiKey = "deepl-key",
            deeplCustomToken = "deepl-token",
            youdaoAppKey = "youdao-key",
            youdaoAppSecret = "youdao-secret",
            volcAccessKeyId = "volc-id",
            volcSecretAccessKey = "volc-secret",
            baiduFanyiAppId = "baidu-app-id",
            baiduFanyiSecretKey = "baidu-secret-key",
            floatingButtonSizeDp = 52,
            floatingWindowWidthDp = 444,
            floatingWindowHeightDp = 222,
            pinnedLanguages = listOf("ja", "zh-TW"),
            cleartextAllowedHosts = listOf("192.168.0.2"),
            floatingMenuItemOrder = FloatingMenu.DEFAULT_ORDER.reversed(),
            arcMenuPageSize = 5,
            floatingButtonSkill = FloatingSkill.LOOP,
            dictionaryPrompt = "portable dictionary prompt",
            localLlmMirror = LlmMirrorChoice.CUSTOM,
            localLlmMirrorUrl = "https://portable.example/models/",
            dbnetProbThresh = 0.31f,
            bubbleClusterGap = 41,
        )
        val preset = TranslationPresetCatalog.fromSettings(
            id = "custom_portable",
            name = "Portable",
            shortName = "Port",
            settings = base,
        )
        return base.copy(
            translationPresets = listOf(preset),
            activeTranslationPresetId = preset.id,
        )
    }

    private fun storedName(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return OverlayFontPolicy.storedFileNameForSha256(hex)
    }
}
