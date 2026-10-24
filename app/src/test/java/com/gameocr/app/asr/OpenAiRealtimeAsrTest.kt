package com.gameocr.app.asr

import com.gameocr.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenAiRealtimeAsrTest {
    private fun event(type: String, id: String, text: String? = null) = buildJsonObject {
        put("type", type); put("item_id", id)
        if (text != null) put(if (type.endsWith(".delta")) "delta" else "transcript", text)
    }
    private val commit = "input_audio_buffer.committed"
    private val delta = "conversation.item.input_audio_transcription.delta"
    private val final = "conversation.item.input_audio_transcription.completed"

    @Test fun oldConnectionDefaultsToHttpAndRoundTripsTransport() {
        assertEquals(OpenAiAsrTransport.HTTP_TRANSCRIPTION,
            Json.decodeFromString<AsrCloudConnections>("""{"openAiModel":"previous"}""").openAiTransport)
        OpenAiAsrTransport.entries.forEach { mode ->
            val config = AsrCloudConnections(openAiModel = "custom", openAiTransport = mode)
            assertEquals(config, Json.decodeFromString<AsrCloudConnections>(Json.encodeToString(AsrCloudConnections.serializer(), config)))
        }
    }

    @Test fun endpointsPreserveCustomPathsAndRejectAmbiguousUrls() {
        mapOf(
            "https://speech.example" to "https://speech.example/v1/realtime?intent=transcription",
            "https://speech.example/proxy/v1/" to "https://speech.example/proxy/v1/realtime?intent=transcription",
            "https://speech.example/v1/audio/transcriptions" to "https://speech.example/v1/realtime?intent=transcription",
            "wss://speech.example/v1/realtime?intent=transcription" to "https://speech.example/v1/realtime?intent=transcription",
        ).forEach { (base, expected) -> assertEquals(base, expected, OpenAiRealtimeAsrProtocol.endpoint(base).toString()) }
        listOf("invalid", "https://user:secret@speech.example/v1", "https://speech.example/v1#fragment",
            "https://speech.example/v1?api_key=secret", "https://speech.example/v1/chat/completions",
            "https://speech.example/v1/messages").forEach { assertNull(it, OpenAiRealtimeAsrProtocol.endpoint(it)) }
    }

    @Test fun sessionsAreTranscriptionOnlyWithExplicitFormatAndClientTurnDetection() {
        listOf("gpt-live-transcribe", "gpt-4o-mini-transcribe", "custom-asr").forEach { model ->
            val root = Json.parseToJsonElement(OpenAiRealtimeAsrProtocol.session(model, AsrLanguage.EN)).jsonObject
            val session = root.getValue("session").jsonObject
            val input = session.getValue("audio").jsonObject.getValue("input").jsonObject
            val transcription = input.getValue("transcription").jsonObject
            assertEquals("transcription", session.getValue("type").jsonPrimitive.content)
            assertEquals(24000, input.getValue("format").jsonObject.getValue("rate").jsonPrimitive.int)
            assertEquals(JsonNull, input["turn_detection"])
            assertEquals(model, transcription.getValue("model").jsonPrimitive.content)
            if (model.startsWith("gpt-live")) {
                assertEquals(listOf("en"), transcription.getValue("languages").jsonArray.map { it.jsonPrimitive.content })
                assertFalse(transcription.containsKey("language"))
            } else assertEquals("en", transcription.getValue("language").jsonPrimitive.content)
        }
        assertFalse(OpenAiRealtimeAsrProtocol.session("custom", AsrLanguage.AUTO).contains("\"language\""))
    }

    @Test fun modelListingUsesTheSelectedProtocolsApiRootWithoutRealtimeParameters() {
        listOf("https://speech.example/proxy/v1", "wss://speech.example/proxy/v1/realtime?intent=transcription",
            "https://speech.example/proxy/v1/audio/transcriptions").forEach { base ->
            assertEquals("https://speech.example/proxy/v1/models",
                OpenAiAsrProtocol.modelsEndpoint(base, OpenAiAsrTransport.REALTIME_TRANSCRIPTION).toString())
        }
        assertNull(OpenAiAsrProtocol.modelsEndpoint("https://speech.example/v1?api_key=secret", OpenAiAsrTransport.REALTIME_TRANSCRIPTION))
    }

    @Test fun deltasReplaceOneRowAndFinalsStayInCommittedOrder() {
        val items = OpenAiRealtimeAsrItems()
        items.accept(event(commit, "a")); items.accept(event(commit, "b"))
        assertEquals("hel", items.accept(event(delta, "a", "hel")).single().text)
        assertEquals("hello", items.accept(event(delta, "a", "lo")).single().text)
        assertTrue(items.accept(event(final, "b", "second")).isEmpty())
        val results = items.accept(event(final, "a", "first"))
        assertEquals(listOf("first", "second"), results.map { it.text })
        assertTrue(results.all { it.isFinal })
        assertEquals(listOf(0L, 1L), results.map { it.segmentId })
        assertTrue(items.accept(event(final, "a", "duplicate")).isEmpty())
    }

    @Test fun resetDoesNotReuseIdsOrAcceptStaleFinals() {
        val items = OpenAiRealtimeAsrItems()
        val before = items.accept(event(delta, "old", "before")).single()
        items.reset()
        assertTrue(items.accept(event(final, "old", "late")).isEmpty())
        val after = items.accept(event(delta, "new", "after")).single()
        assertTrue(after.segmentId > before.segmentId)
        assertTrue(items.accept(buildJsonObject { put("type", delta) }).isEmpty())
        assertNull(OpenAiRealtimeAsrProtocol.parse("broken"))
    }

    @Test fun completedHistoryIsBoundedWithoutLimitingSessionLength() {
        val items = OpenAiRealtimeAsrItems()
        repeat(1000) {
            val id = "item-$it"
            items.accept(event(commit, id))
            assertEquals("text", items.accept(event(final, id, "text")).single().text)
        }
        assertTrue(items.accept(event(final, "item-0", "very late duplicate")).isEmpty())
    }

    @Test fun missingFinalAndOversizedTextAreBounded() {
        val items = OpenAiRealtimeAsrItems()
        repeat(64) { items.accept(event(commit, "$it")) }
        assertThrows(IllegalStateException::class.java) { items.accept(event(commit, "overflow")) }
        val textItems = OpenAiRealtimeAsrItems()
        assertThrows(IllegalStateException::class.java) { textItems.accept(event(delta, "a", "x".repeat(32769))) }
    }

    @Test fun pendingResultTimeoutCannotBeExtendedByDeltasOrLaterItems() {
        val items = OpenAiRealtimeAsrItems()
        assertFalse(items.timedOut(100000))
        items.accept(event(commit, "a"), 1000)
        items.accept(event(delta, "a", "unfinished"), 20000)
        items.accept(event(commit, "b"), 29000)
        assertFalse(items.timedOut(30999))
        assertTrue(items.timedOut(31000))
        items.accept(event(final, "a", "finished"), 31000)
        assertFalse(items.timedOut(31000))
        items.reset()
        assertFalse(items.timedOut(100000))
    }

    @Test fun pcmConversionIsIndependentOfChunkBoundariesAndHandlesInvalidSamples() {
        val samples = FloatArray(3200) { ((it % 31) - 15) / 15f }
        val full = RealtimePcmResampler().convert(samples)
        val chunked = RealtimePcmResampler()
        val buffer = okio.Buffer()
        samples.toList().chunked(127).forEach { buffer.write(chunked.convert(it.toFloatArray())) }
        assertEquals(full, buffer.readByteString())
        assertTrue(kotlin.math.abs(full.size / 2 - 4800) <= 1)
        chunked.reset()
        assertEquals(full, chunked.convert(samples))
        assertTrue(RealtimePcmResampler().convert(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, -2f, 2f)).size > 0)
        assertEquals(0, RealtimePcmResampler().convert(floatArrayOf()).size)
    }

    @Test fun protocolChangeRestartsSessionAndValidatesRealtimeEndpoint() {
        val http = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrCloudProvider = AsrCloudProvider.OPENAI_COMPATIBLE,
            asrCloudConnections = AsrCloudConnections(openAiApiKey = "key", openAiBaseUrl = "https://speech.example/v1", openAiModel = "test-model"))
        val realtime = http.copy(asrCloudConnections = http.asrCloudConnections.copy(openAiTransport = OpenAiAsrTransport.REALTIME_TRANSCRIPTION))
        assertNotEquals(SpeechStartPolicy.engineKey(http), SpeechStartPolicy.engineKey(realtime))
        assertNull(SpeechStartPolicy.blockReason(realtime, null))
        assertNotNull(SpeechStartPolicy.blockReason(realtime.copy(asrCloudConnections = realtime.asrCloudConnections.copy(openAiApiKey = "")), null))
    }

    @Test fun staleCredentialDraftDoesNotResetImmediateProtocolSelection() {
        val baseline = Settings()
        val draft = AsrCredentialDraft.from(baseline)
        val changed = baseline.copy(asrCloudConnections = baseline.asrCloudConnections.copy(openAiTransport = OpenAiAsrTransport.REALTIME_TRANSCRIPTION))
        assertEquals(OpenAiAsrTransport.REALTIME_TRANSCRIPTION, draft.applyTo(changed).asrCloudConnections.openAiTransport)
    }

    @Test fun emptyFinalDiscardsOnlyItsOwnPartial() {
        val transcript = SpeechTranscript()
        transcript.onPartial(1, 0, "temporary")
        transcript.discardPartial(2, 0)
        assertEquals(1, transcript.all.size)
        transcript.discardPartial(1, 0)
        assertTrue(transcript.all.isEmpty())
        transcript.onFinal(1, 1, "done")
        transcript.discardPartial(1, 1)
        assertEquals("done", transcript.all.single().source)
    }
}
