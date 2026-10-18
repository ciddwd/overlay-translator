package com.gameocr.app.asr

import com.gameocr.app.data.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import org.junit.Assert.*
import org.junit.Test

class TencentSpeechServicesTest {
    private val config = AsrCloudConnections(tencentAppId = "123456", tencentModel = TencentAsrModel.STANDARD)

    @Test fun standardEnginesUseThePublishedManualLanguageCatalogAndOrdinaryBillingParameter() {
        assertEquals(setOf("zh", "zh-TW", "en", "yue", "ko", "ja", "th", "id", "vi", "ms", "fil", "pt", "tr", "ar", "es", "hi", "fr", "de"),
            TencentSpeechLanguagePolicy.standard.map { it.code }.toSet())
        TencentSpeechLanguagePolicy.standard.forEach { language ->
            val request = TencentSpeechProtocol.request(config, "voice", 1700000000, 1, "id", "secret", language)
            assertEquals("/asr/v2/123456", request.url.encodedPath)
            assertEquals("16k_${language.code}", request.url.queryParameter("engine_model_type"))
            assertNull(request.url.queryParameter("sentence_strategy"))
            assertNull(request.url.queryParameter("trans_model"))
        }
        assertEquals(AsrLanguage.ZH, AsrLanguagePolicy.effective(AsrEngineKind.PARAFORMER_CLOUD, AsrLanguage.AUTO,
            provider = AsrCloudProvider.TENCENT, connections = config))
    }

    @Test fun regularPartialsAndFinalsReuseOneRecordAndIgnoreRepeatedFinals() {
        val items = NativeSpeechItems()
        val events = listOf(0, 1, 2, 2).map { slice ->
            TencentSpeechProtocol.parse("""{"code":0,"result":{"slice_type":$slice,"index":4,"voice_text_str":"hello"}}""").results.single()
        }.map { items.accept(it) }
        assertNotNull(events[0]); assertNull(events[1]); assertTrue(events[2]!!.isFinal); assertNull(events[3])
        assertEquals(events[0]!!.segmentId, events[2]!!.segmentId)
    }

    @Test fun translationModelsSignAllParametersUseTheirOwnEndpointAndNeverEnableAudioOutput() {
        listOf(TencentAsrModel.TRANSLATE_LITE, TencentAsrModel.TRANSLATE_STANDARD).forEach { model ->
            val request = TencentSpeechTranslationProtocol.request(config.copy(tencentModel = model), "voice", 1700000000, 123,
                "test-id", "test-key", AsrLanguage.EN, "zh-CN")
            assertEquals("/asr/speech_translate/123456", request.url.encodedPath)
            assertEquals(model.modelId, request.url.queryParameter("trans_model"))
            assertEquals("en", request.url.queryParameter("source")); assertEquals("zh", request.url.queryParameter("target"))
            assertEquals("0", request.url.queryParameter("enable_tts"))
            assertNull(request.url.queryParameter("engine_model_type"))
            val params = request.url.queryParameterNames.filter { it != "signature" }.sorted()
                .joinToString("&") { "$it=${request.url.queryParameter(it)}" }
            val raw = "asr.cloud.tencent.com/asr/speech_translate/123456?$params"
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec("test-key".toByteArray(), "HmacSHA1")) }
            assertEquals(mac.doFinal(raw.toByteArray()).toByteString().base64(), request.url.queryParameter("signature"))
            assertFalse(request.url.toString().contains("test-key"))
            assertTrue(runCatching { TencentSpeechProtocol.request(config.copy(tencentModel = model), "v", 1, 1, "id", "key") }.isFailure)
        }
    }

    @Test fun translationPairsAreValidatedBeforeCaptureAndTargetChangesRestartOnlyTranslationModels() {
        val base = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrCloudProvider = AsrCloudProvider.TENCENT,
            asrCloudConnections = config, tencentSecretId = "id", tencentSecretKey = "key", asrLanguage = AsrLanguage.EN, targetLang = "zh")
        val pairs = mapOf(AsrLanguage.ZH to "zh en ja ko yue id th", AsrLanguage.EN to "zh en ja ko yue id th",
            AsrLanguage.ZH_EN to "zh_en zh en ja ko yue id th", AsrLanguage.JA to "zh en ja ko yue",
            AsrLanguage.KO to "zh en ja ko yue", AsrLanguage.YUE to "zh en ja ko yue",
            AsrLanguage.ID to "zh en id", AsrLanguage.TH to "zh en th", AsrLanguage.RU to "zh en ru")
        pairs.forEach { (source, targets) ->
            val settings = base.copy(asrCloudConnections = config.copy(tencentModel = TencentAsrModel.TRANSLATE_LITE), asrLanguage = source)
            TencentSpeechLanguagePolicy.translation.forEach { target ->
                val valid = target.code in targets.split(" ")
                assertEquals("$source->$target", valid, TencentSpeechLanguagePolicy.supports(source, target.code))
                assertEquals(if (valid) null else SpeechStartBlock.CLOUD_LANGUAGE_UNSUPPORTED,
                    SpeechStartPolicy.blockReason(settings.copy(targetLang = target.code), null))
            }
        }
        TencentAsrModel.entries.forEach { model ->
            val settings = base.copy(asrCloudConnections = config.copy(tencentModel = model))
            assertEquals(model.translatesSpeech, SpeechStartPolicy.engineKey(settings) != SpeechStartPolicy.engineKey(settings.copy(targetLang = "ja")))
            assertEquals(model, Json.decodeFromString<AsrCloudConnections>(Json.encodeToString(settings.asrCloudConnections)).tencentModel)
        }
    }

    @Test fun translationResultsGoDirectlyToProviderRowsAndResetCannotAppendToTheOldSentence() {
        val items = TencentSpeechTranslationItems()
        val transcript = SpeechTranscript()
        fun parse(source: String, target: String, final: Boolean) = TencentSpeechTranslationProtocol.parse(
            """{"code":0,"sentence_id":"sentence-a","result":{"source_text":"$source","target_text":"$target","sentence_end":$final}}""").translations.single()
        val first = items.accept(parse("Hello", "你好", false))!!
        transcript.onProviderResult(1, first)
        val final = items.accept(parse("Hello world", "你好，世界", true))!!
        transcript.onProviderResult(1, final)
        assertEquals(first.segmentId, final.segmentId)
        assertEquals(1, transcript.all.size); assertEquals(SpeechRecordState.DONE, transcript.all.single().state)
        assertEquals("你好，世界", transcript.all.single().translation)
        assertTrue(transcript.all.single().providerTranslated)
        assertNull(items.accept(parse("Hello world", "你好，世界", true)))
        items.reset()
        assertTrue(items.accept(parse("new", "新的", true))!!.segmentId > final.segmentId)
        assertTrue(TencentSpeechTranslationProtocol.parse("""{"code":0}""").translations.isEmpty())
        assertTrue(TencentSpeechTranslationProtocol.parse("""{"code":0,"final":1}""").finished)
        assertTrue(runCatching { TencentSpeechTranslationProtocol.parse("""{"code":0,"result":{"source_text":"x"}}""") }.isFailure)
    }

    @Test fun speechTranslationErrorCodesDoNotUseTheAsrCodeSpace() {
        mapOf(6001 to CloudAsrFailure.CONFIGURATION, 6002 to CloudAsrFailure.AUTH, 6003 to CloudAsrFailure.NOT_ACTIVATED,
            6004 to CloudAsrFailure.QUOTA, 6005 to CloudAsrFailure.OVERDUE, 6006 to CloudAsrFailure.RATE_LIMIT,
            6007 to CloudAsrFailure.AUDIO, 6008 to CloudAsrFailure.CONNECTION, 6012 to CloudAsrFailure.SERVICE).forEach { (code, expected) ->
            val packet = TencentSpeechTranslationProtocol.parse("""{"code":$code,"message":"failure"}""")
            assertEquals(expected, CloudSpeechFailurePolicy.native(AsrCloudProvider.TENCENT, packet.error!!, true))
        }
        assertEquals(CloudAsrFailure.REGION, CloudSpeechFailurePolicy.native(AsrCloudProvider.TENCENT, 6001))
    }
}
