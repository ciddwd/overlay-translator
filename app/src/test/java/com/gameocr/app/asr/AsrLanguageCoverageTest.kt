package com.gameocr.app.asr

import com.gameocr.app.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AsrLanguageCoverageTest {
    @Test fun completeManualCatalogMatchesPublishedCountsAndEverySelectionReachesTheProtocol() {
        val expected = mapOf(AsrCloudModel.PARAFORMER to 8, AsrCloudModel.QWEN_AUDIO_STREAMING to 30,
            AsrCloudModel.FUN_ASR to 30, AsrCloudModel.QWEN_ASR_REALTIME to 27,
            AsrCloudModel.QWEN_LIVE_TRANSLATE_35 to 60, AsrCloudModel.QWEN_LIVE_TRANSLATE_38 to 0)
        expected.forEach { (model, count) ->
            val options = AsrLanguagePolicy.options(AsrEngineKind.PARAFORMER_CLOUD, model)
            assertEquals(model.name, count + 1, options.size)
            assertEquals(options.size, options.distinct().size)
            options.forEach { language ->
                assertEquals(language, Json.decodeFromString<AsrLanguage>(Json.encodeToString(language)))
                assertEquals(language, AsrLanguagePolicy.effective(AsrEngineKind.PARAFORMER_CLOUD, language, model))
                val message = when {
                    model.translatesSpeech -> LiveTranslateProtocol.session(model, language, "en")
                    model.usesRealtimeProtocol -> QwenRealtimeProtocol.session(language)
                    else -> ParaformerRealtimeEngine.runTask("test", language, model)
                }
                if (language != AsrLanguage.AUTO) assertTrue("${model.name} ${language.code}", message.contains("\"${language.code}\""))
            }
        }
    }

    @Test fun whisperMatchesTheOriginal99TokensAndSenseVoiceDoesNotInheritExtraLanguages() {
        val official = javaClass.getResourceAsStream("/asr/whisper-tiny-base-languages.txt")!!
            .bufferedReader().use { it.readText() }.trim().split(" ").toSet()
        assertEquals(99, official.size)
        assertEquals(official, AsrLanguagePolicy.options(AsrEngineKind.WHISPER).filter { it != AsrLanguage.AUTO }.map { it.code }.toSet())
        assertEquals(setOf("auto", "zh", "en", "ja", "ko", "yue"), AsrLanguagePolicy.options(AsrEngineKind.SENSE_VOICE).map { it.code }.toSet())
        assertEquals(AsrLanguage.AUTO, AsrLanguagePolicy.effective(AsrEngineKind.WHISPER, AsrLanguage.YUE))
        assertEquals(AsrLanguage.AUTO, AsrLanguagePolicy.effective(AsrEngineKind.SENSE_VOICE, AsrLanguage.FR))
    }

    @Test fun nativeModelChoicesReachRequestsAndRestartOnlyTheirOwnProvider() {
        val config = AsrCloudConnections(tencentAppId = "123456", volcApiKey = "key")
        val tencent = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrCloudProvider = AsrCloudProvider.TENCENT, asrCloudConnections = config)
        val volc = tencent.copy(asrCloudProvider = AsrCloudProvider.VOLCENGINE)
        val tencentKeys = TencentAsrModel.entries.map { model ->
            val changed = config.copy(tencentModel = model)
            val request = if (model.translatesSpeech) TencentSpeechTranslationProtocol.request(changed, "voice", 1700000000, 1, "id", "secret", AsrLanguage.ZH, "en")
                else TencentSpeechProtocol.request(changed, "voice", 1700000000, 1, "id", "secret")
            assertEquals(model.modelId, request.url.queryParameter(if (model.translatesSpeech) "trans_model" else "engine_model_type"))
            assertEquals(SpeechStartPolicy.engineKey(volc), SpeechStartPolicy.engineKey(volc.copy(asrCloudConnections = changed)))
            SpeechStartPolicy.engineKey(tencent.copy(asrCloudConnections = changed))
        }
        assertEquals(TencentAsrModel.entries.size, tencentKeys.distinct().size)
        val volcKeys = VolcAsrResource.entries.map { resource ->
            assertEquals(resource.resourceId, VolcSpeechProtocol.request("key", "id", resource.resourceId).header("X-Api-Resource-Id"))
            SpeechStartPolicy.engineKey(volc.copy(asrCloudConnections = config.copy(volcResource = resource)))
        }
        assertEquals(VolcAsrResource.entries.size, volcKeys.distinct().size)
        assertEquals(TencentAsrModel.GENERAL, Json.decodeFromString<AsrCloudConnections>("{}").tencentModel)
        assertEquals(VolcAsrResource.V2_DURATION, Json.decodeFromString<AsrCloudConnections>("{}").volcResource)
    }

    @Test fun genericAudioModelsAndAutoOnlyProtocolsUseIndependentLanguageCapabilities() {
        listOf(AsrCloudProvider.TENCENT, AsrCloudProvider.VOLCENGINE).forEach { provider ->
            assertEquals(listOf(AsrLanguage.AUTO), AsrLanguagePolicy.options(AsrEngineKind.PARAFORMER_CLOUD, provider = provider))
        }
        mapOf("FunAudioLLM/SenseVoiceSmall" to 6, "whisper-1" to 100, "whisper-large-v3" to 101,
            "Qwen/Qwen3-ASR-1.7B" to 28).forEach { (model, size) ->
            assertEquals(model, size, AsrLanguagePolicy.options(AsrEngineKind.PARAFORMER_CLOUD,
                provider = AsrCloudProvider.OPENAI_COMPATIBLE, connections = AsrCloudConnections(openAiModel = model)).size)
        }
    }
}
