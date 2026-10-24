package com.gameocr.app.asr

import com.gameocr.app.data.AsrCloudConnections
import com.gameocr.app.data.AsrLanguage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test

class OpenAiRealtimeAsrEngineTest {
    private class Turns : RealtimeTurnDetector {
        override var speaking = true
        var ended = 0
        var closed = false
        override fun accept(samples: FloatArray) = ended.also { ended = 0 }
        override fun reset() { ended = 0 }
        override fun close() { closed = true }
    }
    private class Socket(private val source: Request) : WebSocket {
        val sent = mutableListOf<String>()
        @Volatile var canceled = false
        var backlog = 0L
        var rejects = false
        override fun request() = source
        override fun queueSize() = backlog
        override fun send(text: String): Boolean { if (rejects) return false; sent += text; return true }
        override fun send(bytes: ByteString) = error("Expected JSON audio framing")
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { canceled = true }
    }
    private class Harness(startAudio: Boolean = true, hints: AsrRecognitionHints = AsrRecognitionHints()) : AutoCloseable {
        val hintSource = AsrHintSource(hints)
        val turns = Turns()
        val sockets = mutableListOf<Socket>()
        val listeners = mutableListOf<WebSocketListener>()
        val events = mutableListOf<SpeechEvent>()
        val errors = mutableListOf<CloudAsrFailure>()
        var ready = 0
        @Volatile var now = 1000L
        val engine = OpenAiRealtimeAsrEngine(OkHttpClient(), null,
            AsrCloudConnections(openAiBaseUrl = "https://asr.example/v1", openAiApiKey = "test-key", openAiModel = "custom"),
            AsrLanguage.AUTO, events::add, errors::add, null, { ready++ }, turns,
            WebSocket.Factory { request, listener -> Socket(request).also { sockets += it; listeners += listener } }, { now }, hintSource = hintSource)
        init { if (startAudio) engine.accept(FloatArray(1600), 0) }
        fun open(index: Int = sockets.lastIndex) {
            listeners[index].onOpen(sockets[index], Response.Builder().request(sockets[index].request())
                .protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
        }
        fun message(value: String, index: Int = sockets.lastIndex) = listeners[index].onMessage(sockets[index], value)
        fun confirmed() { open(); message("""{"type":"session.updated"}""") }
        override fun close() = engine.close()
    }

    @Test fun connectionStartsOnlyAfterCaptureToAvoidAnEarlyReadinessCallback() = Harness(false).use { h ->
        assertTrue(h.sockets.isEmpty())
        h.engine.accept(FloatArray(0), 0)
        assertTrue(h.sockets.isEmpty())
        h.engine.accept(FloatArray(1600), 0)
        assertEquals(1, h.sockets.size)
    }

    @Test fun audioWaitsForConfirmedSessionThenStreamsWithoutFiles() = Harness().use { h ->
        assertEquals("Bearer test-key", h.sockets.single().request().header("Authorization"))
        h.engine.accept(FloatArray(1600), 0)
        assertTrue(h.sockets.single().sent.isEmpty())
        h.open()
        assertEquals(1, h.sockets.single().sent.size)
        assertTrue(h.sockets.single().sent.single().contains("session.update"))
        assertEquals(0, h.ready)
        h.message("""{"type":"session.updated"}""")
        assertEquals(1, h.ready)
        assertTrue(h.sockets.single().sent.last().contains("input_audio_buffer.append"))
        h.message("""{"type":"session.updated"}""")
        assertEquals(1, h.ready)
        h.turns.ended = 1
        h.engine.accept(FloatArray(1600), 0)
        assertTrue(h.sockets.single().sent.last().contains("input_audio_buffer.commit"))
    }

    @Test fun pauseInvalidatesOldCallbacksAndResumeStartsFreshSession() = Harness().use { h ->
        h.confirmed()
        h.message("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"old","delta":"before"}""")
        h.engine.reset()
        assertTrue(h.sockets.first().canceled)
        h.message("""{"type":"conversation.item.input_audio_transcription.completed","item_id":"old","transcript":"late"}""", 0)
        assertEquals(listOf("before"), h.events.map { it.text })
        h.engine.accept(FloatArray(1600), 0)
        h.confirmed()
        h.message("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"new","delta":"after"}""")
        assertEquals(listOf("before", "after"), h.events.map { it.text })
        assertTrue(h.events.last().segmentId > h.events.first().segmentId)
    }

    @Test fun authenticationAndProtocolErrorsStopWithoutHttpFallback() {
        listOf("invalid_api_key" to CloudAsrFailure.AUTH, "invalid_request_error" to CloudAsrFailure.CONFIGURATION).forEach { (code, expected) ->
            Harness().use { h ->
                h.confirmed()
                h.message("""{"type":"error","error":{"code":"$code","message":"rejected"}}""")
                assertEquals(listOf(expected), h.errors)
                h.engine.accept(FloatArray(1600), 0)
                assertEquals(1, h.sockets.size)
                assertTrue(h.sockets.single().canceled)
            }
        }
        Harness().use { h ->
            h.confirmed()
            h.message("""{"type":"error","error":{"type":"invalid_request_error","message":"unsupported protocol"}}""")
            assertEquals(listOf(CloudAsrFailure.CONFIGURATION), h.errors)
        }
    }

    @Test fun transientFailureRetriesWithBoundAndIgnoresOldErrors() = Harness().use { h ->
        repeat(3) { attempt ->
            h.open()
            h.listeners.last().onFailure(h.sockets.last(), java.io.IOException("offline"), null)
            if (attempt < 2) {
                h.now += 5000
                h.engine.accept(FloatArray(1600), 0)
            }
        }
        assertEquals(listOf(CloudAsrFailure.CONNECTION), h.errors)
        assertEquals(3, h.sockets.size)
        h.listeners.first().onFailure(h.sockets.first(), java.io.IOException("late"), null)
        assertEquals(1, h.errors.size)
    }

    @Test fun sendsAreBoundedAndOverflowNeverSilentlyDropsAnUtterance() = Harness().use { h ->
        h.confirmed()
        h.sockets.single().backlog = 600000
        h.engine.accept(FloatArray(1600), 0)
        assertTrue(h.sockets.single().canceled)
        assertTrue(h.events.isEmpty())
    }

    @Test fun handshakeAndMissingFinalTimeoutCancelTheStalledSession() = runBlocking {
        listOf(0, 1, 2).forEach { state ->
            Harness().use { h ->
                if (state > 0) {
                    h.confirmed()
                    if (state == 1) h.message("""{"type":"input_audio_buffer.committed","item_id":"stalled"}""")
                    else {
                        h.turns.ended = 1
                        h.engine.accept(FloatArray(1600), 0) // No acknowledgement from the server.
                    }
                }
                h.now += 31000
                withTimeout(3000) { while (!h.sockets.single().canceled) delay(10) }
                assertEquals(1, h.sockets.size)
                assertTrue(h.events.isEmpty())
            }
        }
    }

    @Test fun closeReleasesTurnDetectorAndBlocksLaterAudioAndCallbacks() {
        val h = Harness()
        h.confirmed()
        h.close()
        assertTrue(h.turns.closed)
        h.engine.accept(FloatArray(1600), 0)
        h.message("""{"type":"conversation.item.input_audio_transcription.delta","item_id":"late","delta":"bad"}""")
        assertTrue(h.events.isEmpty())
        assertEquals(1, h.sockets.size)
        h.close()
    }
}
