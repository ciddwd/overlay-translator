package com.gameocr.app.asr

import com.gameocr.app.data.AsrCloudModel
import com.gameocr.app.data.AsrEngineKind
import com.gameocr.app.data.AsrLanguage
import com.gameocr.app.data.AsrLanguagePolicy
import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsFieldPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CloudAsrModelTest {
    @Test fun everySelectionRoundTripsWithoutExportingCredentials_tableDriven() {
        assertEquals(AsrCloudModel.PARAFORMER, SettingsFieldPolicy.decodePortable(JsonObject(emptyMap())).settings.asrCloudModel)
        AsrCloudModel.entries.forEach { model ->
            val settings = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD,
                asrCloudModel = model, asrDashScopeApiKey = "private-cloud-key")
            val exported = SettingsFieldPolicy.encodePortable(settings)
            assertFalse(exported.toString().contains("private-cloud-key"))
            val decoded = SettingsFieldPolicy.decodePortable(exported).settings
            assertEquals(model, decoded.asrCloudModel)
            assertEquals(model, SettingsFieldPolicy.applyPortable(Settings(), decoded).asrCloudModel)
        }
    }

    @Test fun cloudModelChangesInvalidateTheRunningSessionButNotLocalSessions() {
        val cloud = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD)
        assertEquals(AsrCloudModel.entries.size, AsrCloudModel.entries.map { SpeechStartPolicy.engineKey(cloud.copy(asrCloudModel = it)) }.toSet().size)
        val local = Settings(asrEngine = AsrEngineKind.SENSE_VOICE)
        assertEquals(1, AsrCloudModel.entries.map { SpeechStartPolicy.engineKey(local.copy(asrCloudModel = it)) }.toSet().size)
    }

    @Test fun inferenceModelsUseTheirActualModelIds_tableDriven() {
        AsrCloudModel.entries.filterNot { it.usesRealtimeProtocol }.forEach { model ->
            val root = Json.parseToJsonElement(ParaformerRealtimeEngine.runTask("task", AsrLanguage.EN, model)).jsonObject
            val payload = root.getValue("payload").jsonObject
            assertEquals(model.modelId, payload.getValue("model").jsonPrimitive.content)
            assertEquals(16000, payload.getValue("parameters").jsonObject.getValue("sample_rate").jsonPrimitive.int)
            val parameters = payload.getValue("parameters").jsonObject
            if (model == AsrCloudModel.PARAFORMER) {
                assertEquals(1300, parameters.getValue("max_sentence_silence").jsonPrimitive.int)
                assertEquals("false", parameters.getValue("semantic_punctuation_enabled").jsonPrimitive.content)
            } else {
                assertFalse(parameters.containsKey("max_sentence_silence"))
                assertFalse(parameters.containsKey("semantic_punctuation_enabled"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ParaformerRealtimeEngine.runTask("task", AsrLanguage.AUTO, AsrCloudModel.QWEN_ASR_REALTIME)
        }
    }

    @Test fun languageHintsRespectModelSupport_tableDriven() {
        AsrCloudModel.entries.forEach { model ->
            val supportsYue = model == AsrCloudModel.PARAFORMER || model == AsrCloudModel.QWEN_ASR_REALTIME || model == AsrCloudModel.QWEN_LIVE_TRANSLATE_35
            assertEquals(supportsYue, AsrLanguage.YUE in AsrLanguagePolicy.options(AsrEngineKind.PARAFORMER_CLOUD, model))
            assertEquals(if (supportsYue) AsrLanguage.YUE else AsrLanguage.AUTO,
                AsrLanguagePolicy.effective(AsrEngineKind.PARAFORMER_CLOUD, AsrLanguage.YUE, model))
        }
    }

    @Test fun qwenRealtimeConfigurationAudioAndFinishMatchTheProtocol_tableDriven() {
        listOf(AsrLanguage.AUTO, AsrLanguage.EN).forEach { language ->
            val root = Json.parseToJsonElement(QwenRealtimeProtocol.session(language)).jsonObject
            assertEquals("session.update", root.getValue("type").jsonPrimitive.content)
            val session = root.getValue("session").jsonObject
            assertEquals("pcm", session.getValue("input_audio_format").jsonPrimitive.content)
            assertEquals(16000, session.getValue("sample_rate").jsonPrimitive.int)
            assertEquals(if (language == AsrLanguage.AUTO) null else "en",
                session.getValue("input_audio_transcription").jsonObject["language"]?.jsonPrimitive?.content)
            assertEquals("server_vad", session.getValue("turn_detection").jsonObject.getValue("type").jsonPrimitive.content)
        }
        val frame = ParaformerRealtimeEngine.pcm16(floatArrayOf(0f, 1f))
        val audio = Json.parseToJsonElement(QwenRealtimeProtocol.audio(frame)).jsonObject
        assertEquals("input_audio_buffer.append", audio.getValue("type").jsonPrimitive.content)
        assertEquals(frame.base64(), audio.getValue("audio").jsonPrimitive.content)
        assertEquals("session.finish", Json.parseToJsonElement(QwenRealtimeProtocol.finish()).jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun qwenRealtimeEventsHandleDraftFinalErrorsAndMalformedInput_tableDriven() {
        val cases = listOf(
            """{"type":"session.created"}""" to null,
            """{"type":"session.updated"}""" to ParaformerEvent.Started,
            """{"type":"session.finished"}""" to ParaformerEvent.Finished,
            """{"type":"conversation.item.input_audio_transcription.text","item_id":"a","text":"Hello","stash":" world"}""" to ParaformerEvent.Result("Hello world", false, false, "a"),
            """{"type":"conversation.item.input_audio_transcription.completed","item_id":"a","transcript":"Hello world!"}""" to ParaformerEvent.Result("Hello world!", true, false, "a"),
            """{"type":"error","error":{"code":"invalid_api_key","message":"denied"}}""" to ParaformerEvent.Failed("invalid_api_key", "denied"),
            """{"type":"conversation.item.input_audio_transcription.text","text":"missing id"}""" to null,
            "bad json" to null,
        )
        cases.forEach { (message, expected) -> assertEquals(message, expected, QwenRealtimeProtocol.parse(message)) }
    }

    @Test fun overlappingSentencesNeverOverwriteEachOtherAndResetDropsOldState() {
        val items = CloudSpeechItems()
        val a = items.accept("a", "first", false)!!
        val b = items.accept("b", "second", false)!!
        assertNotEquals(a.segmentId, b.segmentId)
        assertEquals(a.segmentId, items.accept("a", "first!", true)!!.segmentId)
        assertNull(items.accept("a", "late partial", false))
        assertNull(items.accept("a", "first!", true))
        assertNull(items.accept("b", "second", false))
        items.reset()
        assertTrue(items.accept("a", "fresh", false)!!.segmentId > b.segmentId)
        assertNull(items.accept("empty", "", true))
    }
}
