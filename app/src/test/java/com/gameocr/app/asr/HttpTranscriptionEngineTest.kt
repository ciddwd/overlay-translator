package com.gameocr.app.asr

import com.gameocr.app.data.AsrCloudConnections
import com.gameocr.app.data.AsrLanguage
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class HttpTranscriptionEngineTest {
    private class Segments : HttpAsrSegmenter {
        private val pending = ArrayDeque<ByteArray>()
        var closeCount = 0
        @Synchronized fun add(marker: Int) { pending.addLast(byteArrayOf(marker.toByte(), 0)) }
        @Synchronized override fun accept(samples: FloatArray): List<ByteArray> {
            check(closeCount == 0)
            return pending.toList().also { pending.clear() }
        }
        @Synchronized override fun reset() { check(closeCount == 0); pending.clear() }
        @Synchronized override fun close() { closeCount++ }
    }

    private class PendingCall(private val source: Request, private val ignoreCancel: Boolean) : Call {
        private val responses = LinkedBlockingQueue<Result<Response>>()
        val marker: Int
        @Volatile private var executed = false
        @Volatile var canceled = false
        init {
            val data = Buffer().also { source.body!!.writeTo(it) }.readByteString()
            val wave = data.indexOf("RIFF".encodeUtf8())
            check(wave >= 0)
            marker = data[wave + 44].toInt() and 255
        }
        fun complete(body: String, status: Int = 200) {
            responses.put(Result.success(Response.Builder().request(source).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body.toResponseBody("application/json".toMediaType())).build()))
        }
        override fun request() = source
        override fun execute(): Response { executed = true; return responses.take().getOrThrow() }
        override fun enqueue(responseCallback: Callback) = error("Expected blocking request on IO worker")
        override fun cancel() {
            canceled = true
            if (!ignoreCancel) responses.offer(Result.failure(IOException("canceled")))
        }
        override fun isExecuted() = executed
        override fun isCanceled() = canceled
        override fun timeout() = Timeout()
        override fun clone(): Call = PendingCall(source, ignoreCancel)
    }

    private class Harness(ignoreCancel: Boolean = false, hints: AsrRecognitionHints = AsrRecognitionHints()) : AutoCloseable {
        val hintSource = AsrHintSource(hints)
        val segments = Segments()
        val calls = CopyOnWriteArrayList<PendingCall>()
        val events = CopyOnWriteArrayList<SpeechEvent>()
        val errors = CopyOnWriteArrayList<CloudAsrFailure>()
        val metrics = SpeechMetrics()
        val engine = HttpTranscriptionEngine(OkHttpClient(), null,
            AsrCloudConnections(openAiBaseUrl = "https://speech.example/v1", openAiApiKey = "test-key", openAiModel = "asr-model"),
            AsrLanguage.AUTO, events::add, errors::add, metrics, segments,
            Call.Factory { PendingCall(it, ignoreCancel).also(calls::add) }, { System.nanoTime() / 1_000_000 }, hintSource = hintSource)
        fun submit(marker: Int) { segments.add(marker); engine.accept(FloatArray(1600), 0) }
        suspend fun call(marker: Int): PendingCall {
            await { calls.any { it.marker == marker && it.isExecuted() } }
            return calls.first { it.marker == marker }
        }
        override fun close() = engine.close()
    }

    private companion object {
        suspend fun await(condition: () -> Boolean) = withTimeout(3000) {
            while (!condition()) delay(10)
        }
    }

    @Test fun nativeVadUsesShortTurnsWithoutChangingTheAudioFormatOrSpeechAcceptanceThreshold() {
        val config = HttpAsrPolicy.vadConfig()
        assertEquals(16000, config.sampleRate)
        assertEquals(0.35f, config.sileroVadModelConfig.minSilenceDuration)
        assertEquals(4f, config.sileroVadModelConfig.maxSpeechDuration)
        assertEquals(0.25f, config.sileroVadModelConfig.minSpeechDuration)
    }

    @Test fun twoRequestsOverlapAndOutOfOrderResponsesPublishInSpeechOrder() = runBlocking {
        Harness().use { h ->
            h.submit(1); h.submit(2); h.submit(3)
            val first = h.call(1)
            val second = h.call(2)
            assertFalse(h.calls.any { it.marker == 3 })
            second.complete("""{"text":"second"}""")
            h.call(3).complete("""{"text":"third"}""")
            await { h.metrics.snapshot().cloudSentences == 2L }
            assertTrue(h.events.isEmpty())
            first.complete("""{"text":"first"}""")
            await { h.events.size == 3 }
            assertEquals(listOf("first", "second", "third"), h.events.map { it.text })
            assertEquals(listOf(0L, 1L, 2L), h.events.map { it.segmentId })
            assertTrue(h.events.all { it.isFinal })
        }
    }

    @Test fun emptyResultsAdvanceTheOrderWithoutPublishingBlankSubtitles() = runBlocking {
        for (firstText in listOf("", "   ", "first")) {
            Harness().use { h ->
                val expected = listOf(firstText.trim(), "second").filter { it.isNotEmpty() }
                h.submit(1); h.submit(2)
                h.call(2).complete("""{"text":"second"}""")
                h.call(1).complete("""{"text":"$firstText"}""")
                await { h.events.size == expected.size }
                assertEquals(expected, h.events.map { it.text })
            }
        }
    }

    @Test fun resetCancelsBothRequestsAndRejectsLateResultsBeforeResuming() = runBlocking {
        Harness(ignoreCancel = true).use { h ->
            h.submit(1); h.submit(2)
            val old = listOf(h.call(1), h.call(2))
            h.engine.reset()
            assertTrue(old.all { it.canceled })
            old.forEach { it.complete("""{"text":"stale"}""") }
            h.submit(3)
            h.call(3).complete("""{"text":"new"}""")
            await { h.events.isNotEmpty() }
            assertEquals(listOf("new"), h.events.map { it.text })
            assertEquals(2L, h.events.single().segmentId)
            assertTrue(h.errors.isEmpty())
        }
    }

    @Test fun httpAndMalformedResponseFailuresStopTheWholeTurnWindow() = runBlocking {
        listOf(401 to CloudAsrFailure.AUTH, 429 to CloudAsrFailure.RATE_LIMIT,
            503 to CloudAsrFailure.SERVICE, 200 to CloudAsrFailure.CONNECTION).forEach { (status, expected) ->
            Harness().use { h ->
                h.submit(1); h.submit(2)
                val first = h.call(1)
                h.call(2).complete("not a transcript", status)
                await { h.errors.isNotEmpty() }
                assertEquals(listOf(expected), h.errors.toList())
                assertTrue(first.canceled)
                h.submit(3)
                assertFalse(h.calls.any { it.marker == 3 })
                assertTrue(h.events.isEmpty())
            }
        }
    }

    @Test fun completedResultsBehindASlowRequestCountTowardTheBacklogLimit() = runBlocking {
        Harness().use { h ->
            h.submit(1)
            val first = h.call(1)
            for (marker in 2..HttpAsrPolicy.MAX_PENDING_SEGMENTS) {
                h.submit(marker)
                h.call(marker).complete("""{"text":"waiting $marker"}""")
                await { h.metrics.snapshot().cloudSentences == marker - 1L }
            }
            assertTrue(h.events.isEmpty())
            h.submit(HttpAsrPolicy.MAX_PENDING_SEGMENTS + 1)
            await { h.errors.isNotEmpty() }
            assertEquals(listOf(CloudAsrFailure.CONNECTION), h.errors.toList())
            assertTrue(first.canceled)
            assertTrue(h.events.isEmpty())
        }
    }

    @Test fun closeCancelsRequestsAndReleasesTheNativeSegmenterExactlyOnce() = runBlocking {
        val h = Harness()
        try {
            h.submit(1); h.submit(2)
            val calls = listOf(h.call(1), h.call(2))
            h.close()
            assertTrue(calls.all { it.canceled })
            h.engine.accept(FloatArray(1600), 0)
            h.engine.reset()
            h.close()
            assertEquals(1, h.segments.closeCount)
            assertTrue(h.events.isEmpty())
            assertTrue(h.errors.isEmpty())
        } finally { h.close() }
    }

    private fun requestText(call: PendingCall) = Buffer().also { call.request().body!!.writeTo(it) }.readUtf8()

    @Test fun defaultOffNeverCarriesPriorSourceIntoLaterRequests() = runBlocking {
        Harness(hints = AsrRecognitionHints(prompt = "scene")).use { h ->
            h.submit(1); h.call(1).complete("""{"text":"private first source"}""")
            await { h.events.size == 1 }
            h.submit(2)
            assertFalse(requestText(h.call(2)).contains("private first source"))
        }
    }

}
