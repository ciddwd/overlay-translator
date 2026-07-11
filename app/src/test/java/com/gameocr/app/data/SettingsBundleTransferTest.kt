package com.gameocr.app.data

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
            floatingButtonSkill = FloatingSkill.WORD_SELECT,
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
