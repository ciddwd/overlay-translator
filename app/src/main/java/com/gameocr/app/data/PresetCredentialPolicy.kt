package com.gameocr.app.data

import kotlinx.serialization.Serializable

/** Local storage only. Deliberately not a member of Settings or TranslationPreset. */
@Serializable
internal data class PresetCredentialRecord(
    val ownerId: String,
    val groups: Map<String, PresetCredentialGroup>,
) {
    override fun toString(): String = "PresetCredentialRecord(<redacted>)"
}

@Serializable
internal data class PresetCredentialGroup(val binding: String, val values: Map<String, String>) {
    override fun toString(): String = "PresetCredentialGroup(<redacted>)"
}

internal object PresetCredentialPolicy {
    // Keep shared account credentials in one group even when both OCR and translation use them.
    private enum class Group(val fields: List<String>, val endpoint: (Settings) -> String = { "" }) {
        OPENAI(listOf("apiKey"), { it.baseUrl }),
        ANTHROPIC(listOf("anthropicApiKey"), { it.anthropicBaseUrl }),
        DEEPL(listOf("deeplApiKey")),
        DEEPL_CUSTOM(listOf("deeplCustomToken"), { it.deeplBaseUrl }),
        NIUTRANS(listOf("niuTransApiKey", "niuTransAppId")),
        YOUDAO(listOf("youdaoAppKey", "youdaoAppSecret")),
        VOLC(listOf("volcAccessKeyId", "volcSecretAccessKey")),
        BAIDU_FANYI(listOf("baiduFanyiAppId", "baiduFanyiSecretKey")),
        TENCENT(listOf("tencentSecretId", "tencentSecretKey")),
        BAIDU_OCR(listOf("baiduOcrApiKey", "baiduOcrSecretKey")),
        PADDLE(listOf("paddleAiStudioToken")),
        TTS_HTTP(listOf("ttsHttpBearerToken"), { it.ttsHttpBaseUrl }),
        TTS_VOLC(listOf("ttsVolcengineApiKey"), { it.ttsVolcengineBaseUrl }),
        TTS_MINIMAX(listOf("ttsMiniMaxApiKey"), { it.ttsMiniMaxBaseUrl }),
        TTS_MIMO(listOf("ttsMimoApiKey"), { it.ttsMimoBaseUrl }),
    }

    val credentialFields: Set<String> = Group.entries.flatMapTo(linkedSetOf()) { it.fields }

    private fun selectedGroups(settings: Settings): Set<Group> = buildSet {
        when (settings.translatorEngine) {
            // Offline presets must not own or clear the retained cloud compatibility connection.
            TranslatorEngine.OPENAI -> add(Group.OPENAI)
            TranslatorEngine.ANTHROPIC -> add(Group.ANTHROPIC)
            TranslatorEngine.DEEPL -> add(if (settings.deeplBaseUrl.isBlank()) Group.DEEPL else Group.DEEPL_CUSTOM)
            TranslatorEngine.NIUTRANS -> add(Group.NIUTRANS)
            TranslatorEngine.YOUDAO_PICTRANS -> add(Group.YOUDAO)
            TranslatorEngine.VOLC -> add(Group.VOLC)
            TranslatorEngine.BAIDU_FANYI -> add(Group.BAIDU_FANYI)
            TranslatorEngine.TENCENT -> add(Group.TENCENT)
            else -> Unit
        }
        when (settings.ocrEngine) {
            OcrEngineKind.BAIDU -> add(Group.BAIDU_OCR)
            OcrEngineKind.TENCENT -> add(Group.TENCENT)
            OcrEngineKind.YOUDAO -> add(Group.YOUDAO)
            OcrEngineKind.PADDLE_AI_STUDIO -> add(Group.PADDLE)
            else -> Unit
        }
        if (settings.ttsEnabled) when (settings.ttsProvider) {
            TtsProvider.GENERIC_HTTP -> add(Group.TTS_HTTP)
            TtsProvider.VOLCENGINE -> add(Group.TTS_VOLC)
            TtsProvider.MINIMAX -> add(Group.TTS_MINIMAX)
            TtsProvider.MIMO -> add(Group.TTS_MIMO)
            TtsProvider.SYSTEM -> Unit
        }
    }

    private fun Group.binding(settings: Settings): String = name + ":" + endpoint(settings).trim().trimEnd('/')

    // Never serialize the whole Settings object on every form recomposition just to compare credentials.
    private fun values(settings: Settings): Map<String, String> = with(settings) { mapOf(
        "apiKey" to apiKey, "anthropicApiKey" to anthropicApiKey,
        "deeplApiKey" to deeplApiKey, "deeplCustomToken" to deeplCustomToken,
        "niuTransApiKey" to niuTransApiKey, "niuTransAppId" to niuTransAppId,
        "youdaoAppKey" to youdaoAppKey, "youdaoAppSecret" to youdaoAppSecret,
        "volcAccessKeyId" to volcAccessKeyId, "volcSecretAccessKey" to volcSecretAccessKey,
        "baiduFanyiAppId" to baiduFanyiAppId, "baiduFanyiSecretKey" to baiduFanyiSecretKey,
        "tencentSecretId" to tencentSecretId, "tencentSecretKey" to tencentSecretKey,
        "baiduOcrApiKey" to baiduOcrApiKey, "baiduOcrSecretKey" to baiduOcrSecretKey,
        "paddleAiStudioToken" to paddleAiStudioToken, "ttsHttpBearerToken" to ttsHttpBearerToken,
        "ttsVolcengineApiKey" to ttsVolcengineApiKey, "ttsMiniMaxApiKey" to ttsMiniMaxApiKey,
        "ttsMimoApiKey" to ttsMimoApiKey,
    ) }

    fun capture(id: String, settings: Settings): PresetCredentialRecord {
        val values = values(settings)
        return PresetCredentialRecord(id, selectedGroups(settings).associate { group ->
            group.name to PresetCredentialGroup(
                group.binding(settings),
                group.fields.associateWith { values.getValue(it) },
            )
        })
    }

    /** Only the current account's matching connection may seed a legacy local preset. */
    fun legacyRecord(id: String, current: Settings, target: Settings): PresetCredentialRecord? {
        val source = capture(id, current)
        val expected = capture(id, target)
        val groups = source.groups.filter { (name, group) ->
            group.binding == expected.groups[name]?.binding && when (name) {
                Group.OPENAI.name -> current.openAiProvider == target.openAiProvider && current.model == target.model
                Group.ANTHROPIC.name -> current.anthropicModel == target.anthropicModel
                else -> true
            }
        }
        return source.copy(groups = groups).takeIf { groups.isNotEmpty() }
    }

    fun retainMatchingGroups(record: PresetCredentialRecord, target: Settings): PresetCredentialRecord {
        val expected = capture(record.ownerId, target)
        return record.copy(groups = record.groups.filter { (name, group) ->
            group.binding == expected.groups[name]?.binding
        })
    }

    fun matches(id: String, settings: Settings, record: PresetCredentialRecord?): Boolean {
        val expected = capture(id, settings)
        if (record == null) return expected.groups.values.all { group -> group.values.values.all(String::isEmpty) }
        // Older offline presets may contain an obsolete OPENAI group; unused groups do not
        // participate in restoration or the modified-state check.
        return expected == record.copy(groups = record.groups.filterKeys { it in expected.groups })
    }

    fun apply(id: String, settings: Settings, record: PresetCredentialRecord?): Settings {
        val values = values(settings).toMutableMap()
        for (group in selectedGroups(settings)) {
            val saved = record?.takeIf { it.ownerId == id }?.groups?.get(group.name)
                ?.takeIf { it.binding == group.binding(settings) }
            for (field in group.fields) values[field] = saved?.values?.get(field).orEmpty()
        }
        return settings.copy(
            apiKey = values.getValue("apiKey"), anthropicApiKey = values.getValue("anthropicApiKey"),
            deeplApiKey = values.getValue("deeplApiKey"), deeplCustomToken = values.getValue("deeplCustomToken"),
            niuTransApiKey = values.getValue("niuTransApiKey"), niuTransAppId = values.getValue("niuTransAppId"),
            youdaoAppKey = values.getValue("youdaoAppKey"), youdaoAppSecret = values.getValue("youdaoAppSecret"),
            volcAccessKeyId = values.getValue("volcAccessKeyId"), volcSecretAccessKey = values.getValue("volcSecretAccessKey"),
            baiduFanyiAppId = values.getValue("baiduFanyiAppId"), baiduFanyiSecretKey = values.getValue("baiduFanyiSecretKey"),
            tencentSecretId = values.getValue("tencentSecretId"), tencentSecretKey = values.getValue("tencentSecretKey"),
            baiduOcrApiKey = values.getValue("baiduOcrApiKey"), baiduOcrSecretKey = values.getValue("baiduOcrSecretKey"),
            paddleAiStudioToken = values.getValue("paddleAiStudioToken"), ttsHttpBearerToken = values.getValue("ttsHttpBearerToken"),
            ttsVolcengineApiKey = values.getValue("ttsVolcengineApiKey"), ttsMiniMaxApiKey = values.getValue("ttsMiniMaxApiKey"),
            ttsMimoApiKey = values.getValue("ttsMimoApiKey"),
        )
    }
}
