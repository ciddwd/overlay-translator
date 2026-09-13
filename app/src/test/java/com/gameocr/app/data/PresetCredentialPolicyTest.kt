package com.gameocr.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class PresetCredentialPolicyTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun activeServiceCredentials_tableDriven_areIsolatedFromOtherGroups() {
        data class Case(val settings: Settings, val fields: Set<String>)
        val cases = listOf(
            Case(Settings(), setOf("apiKey")),
            Case(Settings(translatorEngine = TranslatorEngine.ANTHROPIC), setOf("anthropicApiKey")),
            Case(Settings(translatorEngine = TranslatorEngine.DEEPL), setOf("deeplApiKey")),
            Case(Settings(translatorEngine = TranslatorEngine.DEEPL, deeplBaseUrl = "https://custom.test"), setOf("deeplCustomToken")),
            Case(Settings(translatorEngine = TranslatorEngine.NIUTRANS), setOf("niuTransApiKey", "niuTransAppId")),
            Case(Settings(translatorEngine = TranslatorEngine.YOUDAO_PICTRANS), setOf("youdaoAppKey", "youdaoAppSecret")),
            Case(Settings(translatorEngine = TranslatorEngine.VOLC), setOf("volcAccessKeyId", "volcSecretAccessKey")),
            Case(Settings(translatorEngine = TranslatorEngine.BAIDU_FANYI), setOf("baiduFanyiAppId", "baiduFanyiSecretKey")),
            Case(Settings(translatorEngine = TranslatorEngine.TENCENT, ocrEngine = OcrEngineKind.TENCENT), setOf("tencentSecretId", "tencentSecretKey")),
            Case(Settings(translatorEngine = TranslatorEngine.GOOGLE, ocrEngine = OcrEngineKind.BAIDU), setOf("baiduOcrApiKey", "baiduOcrSecretKey")),
            Case(Settings(translatorEngine = TranslatorEngine.GOOGLE, ocrEngine = OcrEngineKind.PADDLE_AI_STUDIO), setOf("paddleAiStudioToken")),
            Case(Settings(translatorEngine = TranslatorEngine.GOOGLE, ocrEngine = OcrEngineKind.YOUDAO), setOf("youdaoAppKey", "youdaoAppSecret")),
            Case(Settings(translatorEngine = TranslatorEngine.GOOGLE, ttsEnabled = true, ttsProvider = TtsProvider.GENERIC_HTTP), setOf("ttsHttpBearerToken")),
            Case(Settings(translatorEngine = TranslatorEngine.GOOGLE, ttsEnabled = true, ttsProvider = TtsProvider.VOLCENGINE), setOf("ttsVolcengineApiKey")),
            Case(Settings(translatorEngine = TranslatorEngine.GOOGLE, ttsEnabled = true, ttsProvider = TtsProvider.MINIMAX), setOf("ttsMiniMaxApiKey")),
            Case(Settings(translatorEngine = TranslatorEngine.GOOGLE, ttsEnabled = true, ttsProvider = TtsProvider.MIMO), setOf("ttsMimoApiKey")),
            Case(Settings(translatorEngine = TranslatorEngine.LOCAL_HY_MT2), setOf("apiKey")),
            Case(Settings(translatorEngine = TranslatorEngine.LOCAL_SAKURA), setOf("apiKey")),
            Case(Settings(translatorEngine = TranslatorEngine.GOOGLE_ML_KIT), emptySet()),
        )
        cases.forEachIndexed { index, case ->
            val saved = withCredentials(case.settings, "saved")
            val record = PresetCredentialPolicy.capture("preset", saved)
            assertEquals("$index captured", case.fields, record.groups.values.flatMap { it.values.keys }.toSet())
            val current = withCredentials(case.settings, "current")
            val restored = json.encodeToJsonElement(PresetCredentialPolicy.apply("preset", current, record)).jsonObject
            PresetCredentialPolicy.credentialFields.forEach { field ->
                assertEquals("$index $field", "${if (field in case.fields) "saved" else "current"}-$field", restored[field]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun missingEmptyWrongOwnerAndChangedEndpoint_tableDriven_neverReuseCurrentKey() {
        val saved = Settings(apiKey = "saved-key", baseUrl = "https://service.test/v1/")
        val record = PresetCredentialPolicy.capture("a", saved)
        data class Case(val id: String, val current: Settings, val record: PresetCredentialRecord?, val expected: String)
        listOf(
            Case("a", saved.copy(apiKey = "previous"), null, ""),
            Case("b", saved.copy(apiKey = "previous"), record, ""),
            Case("a", saved.copy(baseUrl = "https://other.test/v1/"), record, ""),
            Case("a", saved.copy(baseUrl = "https://service.test/another"), record, ""),
            Case("a", saved.copy(baseUrl = "http://service.test/v1"), record, ""),
            Case("a", saved.copy(baseUrl = "https://service.test/v1"), record, "saved-key"),
            Case("a", saved.copy(model = "another-model"), record, "saved-key"),
            Case("a", saved, PresetCredentialPolicy.capture("a", saved.copy(apiKey = "")), ""),
        ).forEachIndexed { index, case ->
            assertEquals("case $index", case.expected, PresetCredentialPolicy.apply(case.id, case.current, case.record).apiKey)
        }
    }

    @Test
    fun credentialRegistryCoversEveryCredentialAndPreservesRuntimeContext() {
        assertEquals(
            SettingsFieldPolicy.rules.filter { it.portability == SettingsPortability.CREDENTIAL }.map { it.name }.toSet(),
            PresetCredentialPolicy.credentialFields,
        )
        val source = Settings(apiKey = "secret", runtimeTranslationContext = "history", runtimeTranslationScopePackage = "app.test", runtimeTranslationScopeLabel = "label")
        val result = PresetCredentialPolicy.apply("a", source, PresetCredentialPolicy.capture("a", source))
        assertEquals(source, result)
        assertFalse(PresetCredentialPolicy.capture("a", source).toString().contains("secret"))
    }

    internal fun withCredentials(settings: Settings, prefix: String): Settings {
        val values = json.encodeToJsonElement(settings).jsonObject.toMutableMap()
        PresetCredentialPolicy.credentialFields.forEach { values[it] = JsonPrimitive("$prefix-$it") }
        return json.decodeFromJsonElement(JsonObject(values))
    }
}
