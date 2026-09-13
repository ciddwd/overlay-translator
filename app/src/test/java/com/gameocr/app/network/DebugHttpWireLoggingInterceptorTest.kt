package com.gameocr.app.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class DebugHttpWireLoggingInterceptorTest {
    private val request = Request.Builder().url("https://example.com/model").build()
    private fun client(log: (String) -> Unit, response: (Request) -> ResponseBody) = OkHttpClient.Builder()
        .addInterceptor(DebugHttpWireLoggingInterceptor(log))
        .addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("X-Request-Id", "public-id")
                .body(response(chain.request())).build()
        }.build()

    @Test fun bodyVisibilityAndCredentialRedaction_table() {
        data class Case(val type: String?, val model: Boolean, val text: String, val visible: Boolean)
        listOf(
            Case("application/json", false, "{\"apiKey\":\"private-key\",\"text\":\"hello\"}", true),
            Case("text/event-stream", false, "data: {\"text\":\"hello\"}\n\n", true),
            Case("application/octet-stream", false, "hello", false),
            Case(null, false, "hello", false),
            Case("application/json", true, "hello", false),
            Case("application/json", false, "hello".repeat(20_000), false),
        ).forEach { case ->
            val logs = mutableListOf<String>()
            val req = request.newBuilder().header("Authorization", "Bearer private-key").apply {
                if (case.model) tag(HttpBodyLogPolicy::class.java, HttpBodyLogPolicy.METADATA_ONLY)
            }.build()
            client(logs::add) { case.text.toResponseBody(case.type?.toMediaType()) }.newCall(req).execute().use {
                assertEquals(case.text, it.body!!.string())
            }
            val output = logs.joinToString("\n")
            assertTrue(output.contains("HTTP 200"))
            assertTrue(output.contains("response-header X-Request-Id: public-id"))
            assertTrue(output.contains("request-header Authorization: ***"))
            assertTrue(output.contains("totalMs="))
            assertFalse(output.contains("private-key"))
            assertEquals(case.toString(), case.visible, output.contains("hello"))
        }
    }

    @Test fun logFailuresNeverBreakResponses_table() {
        for (failure in listOf(IllegalStateException("log sink"), OutOfMemoryError("log sink"))) {
            client({ throw failure }) { "ok".toResponseBody("application/json".toMediaType()) }
                .newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
        }
    }

    @Test fun oneShotUnknownAndLargeRequestsAreNotSerializedByLogger_table() {
        data class Case(val size: Long, val oneShot: Boolean)
        for (case in listOf(Case(-1, false), Case(128 * 1024, false), Case(3, true))) {
            var writes = 0
            val body = object : RequestBody() {
                override fun contentType() = "application/json".toMediaType()
                override fun contentLength() = case.size
                override fun isOneShot() = case.oneShot
                override fun writeTo(sink: BufferedSink) { writes++; sink.writeUtf8("abc") }
            }
            val req = request.newBuilder().post(body).build()
            // A fake transport: any serialization here came from the observer, not the network.
            client({}) { "ok".toResponseBody() }.newCall(req).execute().close()
            assertEquals(case.toString(), 0, writes)
        }
    }

    @Test fun incompleteSecretIsOmittedRatherThanLeakingItsPrefix() {
        val capture = BoundedHttpBodyCapture()
        val raw = Buffer().writeUtf8("{\"apiKey\":\"unclosed-secret")
        capture.copyFrom(raw, 0, raw.size)
        assertFalse(capture.finish("application/json".toMediaType(), false).contains("secret"))
        capture.clear()
        assertEquals(0L, capture.retainedBytes)
    }

    @Test fun longEscapedAndUnterminatedSecretsAreRedactedWithoutRecursiveRegex() {
        for (secret in listOf("private".repeat(9000), "escaped\\\"secret", "escaped\\\\secret")) {
            val raw = "{\"apiKey\":\"$secret\",\"text\":\"visible\"}"
            assertEquals("{\"apiKey\":\"***\",\"text\":\"visible\"}", DebugHttpLogSanitizer.payload(raw))
        }
        assertEquals("{\"apiKey\":\"***", DebugHttpLogSanitizer.payload("{\"apiKey\":\"private"))
    }

    @Test fun hugeMislabelledAndBinaryResponsesUseBoundedMemory_table() {
        // Run under -Xmx64m as the low-memory smoke: never allocate the simulated 256 MiB payload.
        for ((type, model) in listOf("application/json" to false, "text/event-stream" to false,
            "application/octet-stream" to false, "application/json" to true)) {
            val size = 256L * 1024 * 1024
            val logs = mutableListOf<String>()
            val req = request.newBuilder().apply {
                if (model) tag(HttpBodyLogPolicy::class.java, HttpBodyLogPolicy.METADATA_ONLY)
            }.build()
            var read = 0L
            client(logs::add) { object : ResponseBody() {
                override fun contentType() = type.toMediaType()
                override fun contentLength() = size
                override fun source() = object : Source {
                    private val chunk = ByteArray(8192) { 120 }
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (read == size) return -1
                        val count = minOf(chunk.size.toLong(), byteCount, size - read).toInt()
                        sink.write(chunk, 0, count)
                        read += count
                        return count.toLong()
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
            } }.newCall(req).execute().use { response ->
                val sink = Buffer()
                while (response.body!!.source().read(sink, 8192) != -1L) sink.clear()
            }
            assertEquals(size, read)
            assertTrue(logs.sumOf { it.length } < 4000)
        }
    }

    @Test fun retainedCaptureNeverExceedsLimit_table() {
        for (size in listOf(0, 1, 65_535, 65_536, 65_537, 1_000_000)) {
            val capture = BoundedHttpBodyCapture()
            val source = Buffer().write(ByteArray(1024))
            repeat(size / 1024) {
                capture.copyFrom(source, 0, 1024)
                assertTrue(capture.retainedBytes <= BoundedHttpBodyCapture.MAX_BYTES)
            }
            capture.copyFrom(source, 0, (size % 1024).toLong())
            assertTrue(capture.retainedBytes <= BoundedHttpBodyCapture.MAX_BYTES)
        }
    }
}
