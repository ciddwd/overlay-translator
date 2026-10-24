package com.gameocr.app.translate

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class OpenAiModelListClientTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
    private val url = "https://speech.example/v1/models".toHttpUrl()

    @Test fun listOnlyHttpSmoke_normalizesModelsAndUsesCurrentCredentialAndTimeout_tableDriven() = runBlocking {
        data class Case(val body: String, val expected: List<String>)
        listOf(
            Case("""{"data":[{"id":" whisper-1 "},{"id":"SenseVoice"},{"id":"whisper-1"}]}""",
                listOf("SenseVoice", "whisper-1")),
            Case("""{"object":"list","data":[{"id":null},{},{"id":""},{"id":"  "},{"id":"org/语音","owned_by":"provider"}]}""",
                listOf("org/语音")),
            Case("""{"data":[]}""", emptyList()),
        ).forEach { case ->
            var observed: Call? = null
            var requests = 0
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { observed = call }
            }).addInterceptor { chain ->
                requests++
                val request = chain.request()
                assertEquals("GET", request.method)
                assertEquals(url, request.url)
                assertNull(request.body)
                assertEquals("Bearer speech-key", request.header("Authorization"))
                assertEquals("application/json", request.header("Accept"))
                assertEquals(19000, chain.readTimeoutMillis())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("test").body(case.body.toResponseBody()).build()
            }.build()
            try {
                assertEquals(case.expected, OpenAiModelListClient(client, json).fetch(url, " speech-key ", 19))
                assertEquals(1, requests)
                assertEquals(TimeUnit.SECONDS.toNanos(19), observed!!.timeout().timeoutNanos())
            } finally { close(client) }
        }
    }

    @Test fun httpFailuresAndRedirects_neverReturnModelsOrExposeResponseBody_tableDriven() = runBlocking {
        listOf(301, 302, 401, 403, 404, 429, 500).forEach { status ->
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status)
                    .message("test").header("Location", "https://other.example/v1/models")
                    .body("private-key-and-server-details".toResponseBody()).build()
            }.build()
            try {
                val error = runCatching { OpenAiModelListClient(client, json).fetch(url, "speech-key", 30) }.exceptionOrNull()
                assertTrue("HTTP $status", error is OpenAiModelListHttpException)
                assertEquals(status, (error as OpenAiModelListHttpException).status)
                assertEquals("HTTP $status", error.message)
                assertEquals(1, requests)
            } finally { close(client) }
        }
    }

    @Test fun malformedAndOversizedResponses_failWithoutInventingModels_tableDriven() = runBlocking {
        listOf("", "not JSON", "{}", "[]", "null", """{"data":null}""", """{"data":{}}""",
            """{"data":[{"id":{}}]}""", "x".repeat(1024 * 1024 + 1)).forEach { body ->
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                    .message("test").body(body.toResponseBody()).build()
            }.build()
            try {
                assertTrue(runCatching { OpenAiModelListClient(client, json).fetch(url, "speech-key", 30) }.isFailure)
            } finally { close(client) }
        }
    }

    @Test fun missingOrInvalidKey_neverStartsARequest_tableDriven() = runBlocking {
        listOf("", " ", "key\ninvalid").forEach { key ->
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { requests++; throw IOException("must not request") }.build()
            try {
                assertTrue(runCatching { OpenAiModelListClient(client, json).fetch(url, key, 30) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals(0, requests)
            } finally { close(client) }
        }
    }

    @Test fun cancellation_cancelsTheUnderlyingHttpCall() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            release.await(3, TimeUnit.SECONDS)
            throw IOException("cancelled")
        }.build()
        try {
            val job = async { OpenAiModelListClient(client, json).fetch(url, "speech-key", 30) }
            val call = withTimeout(3000) { started.await() }
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally { release.countDown(); close(client) }
    }

    private fun close(client: OkHttpClient) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
