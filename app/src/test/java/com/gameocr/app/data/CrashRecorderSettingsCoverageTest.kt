package com.gameocr.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class CrashRecorderSettingsCoverageTest {

    @Test
    fun formatSettingsListsEverySettingsPropertyByName() {
        val formatted = CrashRecorder.formatSettings(Settings())
        val fields = Settings::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()

        fields.forEach { field ->
            assertTrue("missing crash setting field: $field", formatted.contains("  $field:"))
        }
    }

    @Test
    fun formatSettingsMasksSecretsAndLongPrompts() {
        val formatted = CrashRecorder.formatSettings(
            Settings(
                baseUrl = "https://private.example/v1/",
                umiOcrBaseUrl = "http://192.168.1.20:1224/api/ocr",
                cleartextAllowedHosts = listOf("192.168.1.20"),
                apiKey = "openai-secret",
                baiduOcrApiKey = "baidu-key",
                baiduOcrSecretKey = "baidu-secret",
                paddleAiStudioToken = "paddle-ai-studio-token",
                tencentSecretId = "tencent-id",
                tencentSecretKey = "tencent-key",
                deeplApiKey = "deepl-key",
                deeplCustomToken = "deepl-token",
                youdaoAppKey = "youdao-key",
                youdaoAppSecret = "youdao-secret",
                volcAccessKeyId = "volc-ak",
                volcSecretAccessKey = "volc-sk",
                baiduFanyiAppId = "baidu-app",
                baiduFanyiSecretKey = "baidu-fanyi-secret",
                promptTemplate = "first line\nsecond line that is intentionally longer than sixty characters for truncation",
                dictionaryPrompt = "dictionary line\nsecond line that is intentionally longer than sixty characters for truncation"
            )
        )

        listOf(
            "https://private.example/v1/",
            "http://192.168.1.20:1224/api/ocr",
            "192.168.1.20",
            "openai-secret",
            "baidu-key",
            "baidu-secret",
            "paddle-ai-studio-token",
            "tencent-id",
            "tencent-key",
            "deepl-key",
            "deepl-token",
            "youdao-key",
            "youdao-secret",
            "volc-ak",
            "volc-sk",
            "baidu-app",
            "baidu-fanyi-secret"
        ).forEach { secret ->
            assertFalse("secret leaked: $secret", formatted.contains(secret))
        }
        assertTrue(formatted.contains("  apiKey: <set>"))
        assertTrue(formatted.contains("  baseUrl: <configured;"))
        assertTrue(formatted.contains("  promptTemplate: <configured;"))
        assertTrue(formatted.contains("  dictionaryPrompt: <configured;"))
        assertFalse(formatted.contains("first line"))
        assertFalse(formatted.contains("dictionary line"))
    }
}
