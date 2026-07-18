package com.gameocr.app.translate

import android.content.Context
import com.gameocr.app.data.Settings
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * Google 翻译（非官方端点）。无需 key，谷歌可能随时限流 / 改端点，仅供学习。国内需代理。
 *
 * 端点返回深嵌套数组：`[[["译文片段1","原文片段1",null,null,...],["译文片段2",...],...], null, "ja", ...]`
 * 取 result[0] 数组里每段的 [0]（译文片段），拼成完整字符串。
 */
@Singleton
class GoogleTranslator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache
) : Translator {

    override suspend fun translate(source: String, settings: Settings): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null

        val from = mapLang(settings.sourceLang)
        val to = mapLang(settings.targetLang)
        val cacheKey = cache.key(trimmed, "google", to, from)
        cache.get(cacheKey, settings)?.let { return it }

        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        val translated = callEndpoint(trimmed, from, to, timedClient)
        cache.put(cacheKey, translated, settings)
        return translated
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        val full = translate(source, settings) ?: return@flow
        emit(full)
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(settings: Settings): TestResult {
        return runCatching {
            val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
            val to = mapLang(settings.targetLang).ifEmpty { "zh-CN" }
            val result = callEndpoint("hello", "en", to, timedClient)
            TestResult(true, "OK · hello→$result")
        }.getOrElse { TestResult(false, it.message ?: it.javaClass.simpleName) }
    }

    private suspend fun callEndpoint(
        text: String,
        from: String,
        to: String,
        httpClient: OkHttpClient
    ): String {
        val sl = if (from.isEmpty()) "auto" else from
        val q = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
            "&sl=$sl&tl=$to&dt=t&q=$q"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) GameOcr")
            .get()
            .build()
        return withContext(Dispatchers.IO) {
            httpClient.newCall(req).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    throw TranslationException("Google HTTP ${r.code}: ${raw.take(200)}")
                }
                parseResponse(raw)
            }
        }
    }

    private fun parseResponse(raw: String): String {
        return runCatching {
            val root = json.parseToJsonElement(raw) as? JsonArray
                ?: throw TranslationException("Google 响应不是 JsonArray")
            val segments = root.firstOrNull() as? JsonArray
                ?: throw TranslationException("Google 响应缺 segments 数组")
            val sb = StringBuilder()
            for (seg in segments) {
                val arr = seg as? JsonArray ?: continue
                val first = arr.firstOrNull() as? JsonPrimitive ?: continue
                if (first.isString) sb.append(first.content)
            }
            sb.toString().ifEmpty { throw TranslationException("Google 译文为空") }
        }.getOrElse {
            Timber.tag("GoogleTrans").w(it, "解析失败: %s", raw.take(200))
            throw TranslationException("Google 解析失败: ${raw.take(200)}", it)
        }
    }

    /** BCP-47 → Google 翻译期望的语言码。 */
    private fun mapLang(s: String): String {
        val l = s.lowercase()
        return when {
            l == "auto" || l.isEmpty() -> "auto"
            l == "zh-cn" || l == "zh" -> "zh-CN"
            l == "zh-tw" || l == "zh-hant" -> "zh-TW"
            else -> l.substringBefore('-')  // ja-jp → ja
        }
    }
}
