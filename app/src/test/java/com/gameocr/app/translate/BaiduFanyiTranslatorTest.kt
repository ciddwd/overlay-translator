package com.gameocr.app.translate

import android.content.Context
import android.content.ContextWrapper
import com.gameocr.app.data.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaiduFanyiTranslatorTest {
    private val settings = Settings(
        baiduFanyiAppId = "test-app-id",
        baiduFanyiSecretKey = "test-secret",
        sourceLang = "auto",
        targetLang = "zh-CN",
    )

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun translator(respond: (FormBody) -> String): BaiduFanyiTranslator {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(respond(request.body as FormBody).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return BaiduFanyiTranslator(TestContext(), client, Json { ignoreUnknownKeys = true }, TranslationCache())
    }

    private fun FormBody.field(key: String): String =
        (0 until size).first { name(it) == key }.let { value(it) }

    private fun response(from: String, to: String, items: List<Pair<String, String>>) = buildJsonObject {
        put("from", from)
        put("to", to)
        putJsonArray("trans_result") {
            items.forEach { (src, dst) -> add(buildJsonObject { put("src", src); put("dst", dst) }) }
        }
    }.toString()

    @Test fun autoDetectedSameLanguageMergedEchoRestoresAll47BlocksAndCache() = runBlocking {
        val sources = (1..47).map { "第${it}段" }
        var requests = 0
        val engine = translator { form ->
            requests++
            assertEquals("auto", form.field("from"))
            assertEquals("zh", form.field("to"))
            val query = form.field("q")
            assertEquals(sources.joinToString("\n"), query)
            response("zh", "zh", listOf(query to query))
        }
        assertEquals(sources, engine.translateBatch(sources, settings))
        assertEquals(sources, engine.translateBatch(sources, settings))
        assertEquals("one HTTP call, then every restored item is cached", 1, requests)
    }

    @Test fun normalExplicitLanguageBatchPreservesOrder() = runBlocking {
        val sources = (1..30).map { "source $it" }
        val engine = translator { form ->
            assertEquals("en", form.field("from"))
            response("en", "zh", sources.map { it to "译文:$it" })
        }
        assertEquals(sources.map { "译文:$it" }, engine.translateBatch(sources, settings.copy(sourceLang = "en")))
    }

    @Test fun echoRestorationPreservesBlankSlotsCachedSlotsAndInternalNewlineNormalization() = runBlocking {
        var requests = 0
        val engine = translator { form ->
            requests++
            val query = form.field("q")
            response("zh", "zh", listOf(query to query))
        }
        assertEquals(listOf("缓存"), engine.translateBatch(listOf("缓存"), settings))
        assertEquals(listOf("缓存", null, "第一 第二", "末段"),
            engine.translateBatch(listOf("缓存", "  ", "第一\n第二", "末段"), settings))
        assertEquals(2, requests)
    }

    @Test fun genuineMissingOrChangedResultsStillFailInsteadOfGuessingTranslations() = runBlocking {
        data class Case(val from: String, val src: String, val dst: String)
        listOf(
            Case("en", "a\nb", "一\n二"),
            Case("zh", "a", "a"),
            Case("zh", "a\nb", "a"),
            Case("zh", "a\nb", "甲\n乙"),
        ).forEach { case ->
            val engine = translator { response(case.from, "zh", listOf(case.src to case.dst)) }
            val failure = runCatching { engine.translateBatch(listOf("a", "b"), settings) }.exceptionOrNull()
            assertTrue(case.toString(), failure is TranslationException)
            assertTrue(failure?.message.orEmpty().contains("expected=2, actual=1"))
        }
    }

    @Test fun providerErrorAndEmptyResultRemainErrors() = runBlocking {
        listOf(
            """{"error_code":"54003","error_msg":"rate limited"}""" to "54003",
            response("zh", "zh", emptyList()) to "expected=2, actual=0",
        ).forEach { (body, message) ->
            val engine = translator { body }
            val failure = runCatching { engine.translateBatch(listOf("a", "b"), settings) }.exceptionOrNull()
            assertTrue(failure is TranslationException)
            assertTrue(failure?.message.orEmpty().contains(message))
        }
    }
}
