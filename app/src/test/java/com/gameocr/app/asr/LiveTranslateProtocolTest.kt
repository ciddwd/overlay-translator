package com.gameocr.app.asr

import com.gameocr.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LiveTranslateProtocolTest {
    @Test fun modelSpecificSessionsUseTextOnlyAndExistingTargetLanguage_tableDriven() {
        listOf(AsrCloudModel.QWEN_LIVE_TRANSLATE_38, AsrCloudModel.QWEN_LIVE_TRANSLATE_35).forEach { model ->
            listOf(AsrLanguage.AUTO, AsrLanguage.EN).forEach { language ->
                val session = Json.parseToJsonElement(LiveTranslateProtocol.session(model, language, "zh-CN"))
                    .jsonObject.getValue("session").jsonObject
                assertEquals("zh", session.getValue("translation").jsonObject.getValue("language").jsonPrimitive.content)
                val modern = model == AsrCloudModel.QWEN_LIVE_TRANSLATE_38
                assertEquals(listOf("text"), session.getValue(if (modern) "output_modalities" else "modalities").jsonArray.map { it.jsonPrimitive.content })
                assertFalse(session.containsKey(if (modern) "modalities" else "output_modalities"))
                if (modern) {
                    assertEquals("Tina", session.getValue("voice").jsonPrimitive.content)
                    assertFalse(session.getValue("enable_voice_clone").jsonPrimitive.boolean)
                    assertFalse(session.containsKey("input_audio_transcription"))
                    assertEquals(listOf(AsrLanguage.AUTO), AsrLanguagePolicy.options(AsrEngineKind.PARAFORMER_CLOUD, model))
                } else {
                    val transcription = session.getValue("input_audio_transcription").jsonObject
                    assertEquals("qwen3-asr-flash-realtime", transcription.getValue("model").jsonPrimitive.content)
                    assertEquals(if (language == AsrLanguage.AUTO) null else "en", transcription["language"]?.jsonPrimitive?.content)
                    assertEquals("false", session.getValue("turn_detection").jsonObject.getValue("interrupt_response").jsonPrimitive.content)
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { LiveTranslateProtocol.session(AsrCloudModel.PARAFORMER, AsrLanguage.AUTO, "en") }
    }

    @Test fun targetChangesRestartOnlySpeechTranslationSessions_tableDriven() {
        AsrCloudModel.entries.forEach { model ->
            val a = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrCloudModel = model, targetLang = "en")
            assertEquals(model.translatesSpeech, SpeechStartPolicy.engineKey(a) != SpeechStartPolicy.engineKey(a.copy(targetLang = "ja")))
            assertEquals(model.translatesSpeech, SpeechStartPolicy.engineKey(a.copy(targetLang = "zh-CN")) != SpeechStartPolicy.engineKey(a.copy(targetLang = "zh-TW")))
        }
        mapOf("zh-CN" to "zh", "zh_TW" to "zh", "en-US" to "en", "NO" to "nb", "tl" to "fil").forEach { (input, expected) ->
            assertEquals(expected, LiveTranslateProtocol.targetLanguage(input))
        }
    }

    private fun link(items: LiveTranslateItems, output: String, source: String) = items.accept(
        """{"type":"conversation.item.created","previous_item_id":"$source","item":{"id":"$output","role":"assistant"}}""")
    private fun source(items: LiveTranslateItems, id: String, text: String, final: Boolean = false) = items.accept(
        if (final) """{"type":"conversation.item.input_audio_transcription.completed","item_id":"$id","transcript":"$text"}"""
        else """{"type":"conversation.item.input_audio_transcription.delta","item_id":"$id","delta":"$text"}""")
    private fun output(items: LiveTranslateItems, id: String, text: String, final: Boolean = false) = items.accept(
        """{"type":"response.text.${if (final) "done" else "delta"}","item_id":"$id","${if (final) "text" else "delta"}":"$text"}""")

    @Test fun interleavedSourcesAndTranslationsStayPairedByPreviousItemId() {
        val items = LiveTranslateItems()
        val first = source(items, "a", "first").single()
        val second = source(items, "b", "second").single()
        link(items, "tb", "b"); link(items, "ta", "a")
        assertEquals(second.segmentId, output(items, "tb", "第二").single().segmentId)
        val partial = output(items, "ta", "第").single()
        assertEquals(first.segmentId, partial.segmentId)
        assertEquals("first", partial.text)
        assertEquals("第一", output(items, "ta", "一").single().providerTranslation)
        val done = output(items, "ta", "第一段", true).single()
        assertTrue(done.isFinal)
        assertEquals("第一段", source(items, "a", "first!", true).single().providerTranslation)
        assertTrue(source(items, "a", "late partial").isEmpty())
        assertEquals("第一段", output(items, "ta", "late partial").single().providerTranslation)
    }

    @Test fun translationBeforeLinkIsBufferedAndSourceMayArriveAfterTranslationDone() {
        val items = LiveTranslateItems()
        assertTrue(output(items, "t", "译文", true).isEmpty())
        val joined = link(items, "t", "s").single()
        assertEquals("", joined.text)
        assertTrue(joined.isFinal)
        val updated = source(items, "s", "source", true).single()
        assertEquals(joined.segmentId, updated.segmentId)
        assertEquals("译文", updated.providerTranslation)
        assertTrue(updated.isFinal)
    }

    @Test fun snapshotProtocolReplacesStashAndResponseFailureDoesNotMixRows() {
        val items = LiveTranslateItems()
        source(items, "s", "原文"); link(items, "t", "s")
        listOf("hello?", "hello!").forEach { text ->
            assertEquals(text, items.accept("""{"type":"response.text.text","item_id":"t","text":"hello","stash":"${text.removePrefix("hello")}"}""").single().providerTranslation)
        }
        val failed = items.accept("""{"type":"response.done","response":{"status":"incomplete","output":[{"id":"t","content":[{"type":"text","text":"partial"}]}]}}""").single()
        assertTrue(failed.providerTranslationFailed)
        assertEquals("partial", failed.providerTranslation)
    }

    @Test fun duplicateMalformedResetAndBoundedHistory_tableDriven() {
        val items = LiveTranslateItems()
        listOf("bad", "[]", "null", "{}", """{"type":"response.text.delta","delta":"no id"}""").forEach { assertTrue(items.accept(it).isEmpty()) }
        val delta = """{"event_id":"event1","type":"conversation.item.input_audio_transcription.delta","item_id":"s","delta":"x"}"""
        val before = items.accept(delta).single()
        assertTrue(items.accept(delta).isEmpty())
        repeat(300) { source(items, "s$it", "$it", true) }
        items.reset()
        val after = items.accept(delta).single()
        assertTrue(after.segmentId > before.segmentId)
        assertEquals("", after.providerTranslation)
        assertNull(QwenRealtimeProtocol.parse("""{"type":"conversation.item.input_audio_transcription.failed","error":{"code":"bad"}}""", includeTranscription = false))
        assertEquals(ParaformerEvent.Failed("bad", ""), QwenRealtimeProtocol.parse("""{"type":"error","error":{"code":"bad"}}""", includeTranscription = false))
    }

    @Test fun failedResponseWithoutOutputFinishesItsOwnPendingTranslation() {
        val items = LiveTranslateItems()
        source(items, "s", "原文"); link(items, "t", "s")
        items.accept("""{"type":"response.text.delta","response_id":"r","item_id":"t","delta":"partial"}""")
        val failed = items.accept("""{"type":"response.done","response":{"id":"r","status":"failed","output":[]}}""").single()
        assertTrue(failed.isFinal)
        assertTrue(failed.providerTranslationFailed)
        assertEquals("partial", failed.providerTranslation)
        // The independently arriving source can still finish without restarting translation.
        assertEquals("partial", source(items, "s", "原文。", true).single().providerTranslation)
    }

    @Test fun transcriptKeepsChronologyDoesNotReviveTrimmedRowsAndPreservesCompletedHistory() {
        val transcript = SpeechTranscript()
        fun event(id: Long, text: String, translation: String, final: Boolean = false) = SpeechEvent(id, text, final, translation)
        transcript.onProviderResult(1, event(2, "b", "B"))
        transcript.onProviderResult(1, event(1, "a", "A", true))
        val id = transcript.all.first().id
        transcript.onProviderResult(1, event(1, "a!", "A!", true))
        assertEquals(id, transcript.all.first().id)
        assertEquals(listOf("a!", "b"), transcript.all.map { it.source })
        transcript.trim(1)
        transcript.onProviderResult(1, event(1, "old", "late", true))
        assertEquals(listOf("b"), transcript.all.map { it.source })
        transcript.closeGeneration()
        transcript.onProviderResult(1, event(2, "late", "late", true))
        assertEquals("b", transcript.all.single().source)
        transcript.onProviderResult(2, event(2, "new", "NEW", true))
        assertEquals(listOf("b", "new"), transcript.all.map { it.source })
    }
}
