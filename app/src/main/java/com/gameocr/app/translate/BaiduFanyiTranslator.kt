package com.gameocr.app.translate

import android.content.Context
import com.gameocr.app.data.Settings
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 百度翻译开放平台（fanyi-api.baidu.com）。
 *
 * 协议：POST form-urlencoded `https://fanyi-api.baidu.com/api/trans/vip/translate`
 * 签名：`sign = md5(appid + q + salt + secretKey)`，**全字段（含 q 中的 \n）参与 MD5**。
 * 批量：q 字段用真实 `\n` 分隔多段；响应 `trans_result` 是 list，索引一一对应。
 *
 * **不同于百度智能云 OCR**：那是 aip.baidubce.com + access_token 体系，本类是另一套老牌
 * 翻译开放平台账号（fanyi-api.baidu.com，appid + key），用户在百度翻译开放平台注册即可。
 *
 * 限频：个人免费档 1 QPS / 5 万字符/月；超出会返回 error_code=54003 / 54004 / 54005。
 */
@Singleton
class BaiduFanyiTranslator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache
) : Translator {

    override val prefersBatch: Boolean get() = true

    override suspend fun translate(source: String, settings: Settings): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        return translateBatch(listOf(trimmed), settings).firstOrNull()
    }

    override suspend fun translateBatch(sources: List<String>, settings: Settings): List<String?> {
        if (sources.isEmpty()) return emptyList()
        validate(settings)

        val targetCode = mapLang(settings.targetLang) ?: "zh"
        val sourceCode = mapLang(settings.sourceLang) ?: "auto"

        val result = arrayOfNulls<String>(sources.size)
        val pending = mutableListOf<Int>()
        for ((i, raw) in sources.withIndex()) {
            val t = raw.trim()
            if (t.isEmpty()) {
                result[i] = null
                continue
            }
            val key = cache.key(t, "baidu-fanyi-$targetCode", targetCode, "")
            val hit = cache.get(key)
            if (hit != null) result[i] = hit
            else pending.add(i)
        }
        if (pending.isEmpty()) return result.toList()

        // 多段拼成一个 q：真实换行，FormBody 会负责 URL-encode。
        val q = pending.joinToString("\n") { sources[it].trim() }
        val salt = System.currentTimeMillis().toString()
        val sign = md5Hex(settings.baiduFanyiAppId + q + salt + settings.baiduFanyiSecretKey)

        val form = FormBody.Builder()
            .add("q", q)
            .add("from", sourceCode)
            .add("to", targetCode)
            .add("appid", settings.baiduFanyiAppId)
            .add("salt", salt)
            .add("sign", sign)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(form)
            .build()

        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        val parsed = withContext(Dispatchers.IO) {
            timedClient.newCall(request).execute().use { r ->
                val raw = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    throw TranslationException("百度翻译 HTTP ${r.code}: ${raw.take(200)}")
                }
                runCatching { json.decodeFromString<BaiduFanyiResponse>(raw) }
                    .getOrElse {
                        throw TranslationException("百度翻译解析失败: ${raw.take(200)}", it)
                    }
            }
        }
        if (parsed.errorCode != null && parsed.errorCode != "52000") {
            throw TranslationException("百度翻译 ${parsed.errorCode}: ${parsed.errorMsg ?: "unknown"}")
        }
        val results = parsed.transResult.orEmpty()
        for ((order, idx) in pending.withIndex()) {
            val text = results.getOrNull(order)?.dst?.trim() ?: continue
            result[idx] = text
            val key = cache.key(sources[idx].trim(), "baidu-fanyi-$targetCode", targetCode, "")
            cache.put(key, text)
        }
        return result.toList()
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        val full = translate(source, settings) ?: return@flow
        emit(full)
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(settings: Settings): TestResult {
        if (settings.baiduFanyiAppId.isBlank() || settings.baiduFanyiSecretKey.isBlank()) {
            return TestResult(false, "缺少百度翻译 APPID / 密钥")
        }
        return runCatching {
            val out = translate("hello", settings)
            if (out.isNullOrBlank()) TestResult(false, "返回空")
            else TestResult(true, "OK · 样例: ${out.take(40)}")
        }.getOrElse { TestResult(false, it.message ?: it.javaClass.simpleName) }
    }

    private fun validate(settings: Settings) {
        if (settings.baiduFanyiAppId.isBlank() || settings.baiduFanyiSecretKey.isBlank()) {
            throw TranslationException("百度翻译 APPID / 密钥 未配置")
        }
    }

    /**
     * BCP-47 → 百度翻译 API 语言码（见 https://fanyi-api.baidu.com/doc/21）。
     * 注意百度有几个反直觉的码：日文 `jp` / 韩文 `kor` / 法文 `fra` / 西语 `spa` / 阿语 `ara`
     * / 繁中 `cht` / 越语 `vie`。其余直接复用 BCP-47 主码。"auto" 返回 null（调用方填 "auto"）。
     */
    private fun mapLang(code: String): String? {
        val raw = code.trim()
        if (raw.isEmpty() || raw.equals("auto", true)) return null
        val lower = raw.lowercase()
        return when (lower) {
            "zh", "zh-cn", "zh-hans" -> "zh"
            "zh-tw", "zh-hant", "zh-hk" -> "cht"
            "yue" -> "yue"
            "ja", "jp" -> "jp"
            "ko", "kor" -> "kor"
            "fr", "fra" -> "fra"
            "es", "spa" -> "spa"
            "ar", "ara" -> "ara"
            "vi", "vie" -> "vie"
            "en", "en-us", "en-gb" -> "en"
            "th" -> "th"
            "ru" -> "ru"
            "pt", "pt-br", "pt-pt" -> "pt"
            "de" -> "de"
            "it" -> "it"
            "nl" -> "nl"
            "pl" -> "pl"
            "bg" -> "bul"
            "et" -> "est"
            "da" -> "dan"
            "fi" -> "fin"
            "cs" -> "cs"
            "ro" -> "rom"
            "sl" -> "slo"
            "sv" -> "swe"
            "hu" -> "hu"
            "el" -> "el"
            else -> lower.substringBefore('-')
        }
    }

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"
    }

    @Serializable
    private data class BaiduFanyiResponse(
        val from: String? = null,
        val to: String? = null,
        @SerialName("trans_result") val transResult: List<TransItem>? = null,
        @SerialName("error_code") val errorCode: String? = null,
        @SerialName("error_msg") val errorMsg: String? = null
    )

    @Serializable
    private data class TransItem(
        val src: String? = null,
        val dst: String? = null
    )
}
