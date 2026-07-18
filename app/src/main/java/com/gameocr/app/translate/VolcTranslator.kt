package com.gameocr.app.translate

import android.content.Context
import com.gameocr.app.data.Settings
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 火山引擎机器翻译（open.volcengineapi.com）。
 *
 * 协议：POST `https://open.volcengineapi.com/?Action=TranslateText&Version=2020-06-01`
 * 鉴权：Volcengine SignV4（HMAC-SHA256，模仿 AWS SigV4，service=translate / region=cn-north-1）
 * 批量：Body `TextList` 数组原生批量。`prefersBatch = true` 让 CaptureService 走批量路径。
 *
 * 语言码兼容 DeepL 用过的 BCP-47 集（"ja"/"zh-CN"/"en-US"/...）：[mapLang] 把它们映射到
 * 火山自己的码（"ja"/"zh"/"en"/...）。`Languages.AUTO` ("auto") 时不传 `SourceLanguage`，
 * 火山服务端自动识别。
 */
@Singleton
class VolcTranslator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache
) : Translator {

    override val prefersBatch: Boolean get() = true

    override suspend fun translate(source: String, settings: Settings): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        // 单段直接走批接口（TextList 长度 1），少一份重复代码。
        return translateBatch(listOf(trimmed), settings).firstOrNull()
    }

    override suspend fun translateBatch(sources: List<String>, settings: Settings): List<String?> {
        if (sources.isEmpty()) return emptyList()
        validate(settings)
        val targetCode = mapLang(settings.targetLang) ?: "zh"
        val sourceCode = mapLang(settings.sourceLang)  // null = auto

        // 先走 cache，构造 pending 子集
        val result = arrayOfNulls<String>(sources.size)
        val pending = mutableListOf<Int>()
        for ((i, raw) in sources.withIndex()) {
            val t = raw.trim()
            if (t.isEmpty()) {
                result[i] = null
                continue
            }
            val key = cache.key(t, "volc-$targetCode", targetCode, "")
            val hit = cache.get(key, settings)
            if (hit != null) result[i] = hit
            else pending.add(i)
        }
        if (pending.isEmpty()) return result.toList()

        val payload = buildString {
            // 用 kotlinx-serialization JsonObject 构造，避免手拼字符串转义出错。
            val obj = buildJsonObject {
                put("TargetLanguage", targetCode)
                if (sourceCode != null) put("SourceLanguage", sourceCode)
                put("TextList", JsonArray(pending.map { JsonPrimitive(sources[it].trim()) }))
            }
            append(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj))
        }

        val parsed = withContext(Dispatchers.IO) {
            doSignedCall(settings, payload)
        }
        if (parsed.responseMetadata?.error != null) {
            val e = parsed.responseMetadata.error
            throw TranslationException("Volc ${e.code ?: "?"}: ${e.message ?: "unknown"}")
        }
        val translations = parsed.translationList.orEmpty()
        for ((order, idx) in pending.withIndex()) {
            val text = translations.getOrNull(order)?.translation?.trim() ?: continue
            result[idx] = text
            val key = cache.key(sources[idx].trim(), "volc-$targetCode", targetCode, "")
            cache.put(key, text, settings)
        }
        return result.toList()
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        val full = translate(source, settings) ?: return@flow
        emit(full)
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(settings: Settings): TestResult {
        if (settings.volcAccessKeyId.isBlank() || settings.volcSecretAccessKey.isBlank()) {
            return TestResult(false, "缺少 Volc AccessKey / SecretKey")
        }
        return runCatching {
            val out = translate("hello", settings)
            if (out.isNullOrBlank()) TestResult(false, "返回空")
            else TestResult(true, "OK · 样例: ${out.take(40)}")
        }.getOrElse { TestResult(false, it.message ?: it.javaClass.simpleName) }
    }

    private fun validate(settings: Settings) {
        if (settings.volcAccessKeyId.isBlank() || settings.volcSecretAccessKey.isBlank()) {
            throw TranslationException("Volc AccessKey / SecretKey 未配置")
        }
    }

    private fun doSignedCall(settings: Settings, payload: String): VolcResponse {
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        val region = settings.volcRegion.ifBlank { "cn-north-1" }

        val now = System.currentTimeMillis()
        val dateStamp = compactDate(now)         // 20260627
        val amzDate = compactDateTime(now)        // 20260627T123456Z
        val credentialScope = "$dateStamp/$region/$SERVICE/request"

        // canonical query：按 key 排序 + RFC3986 percent-encode（safe = unreserved 4 字符）。
        // 当前 Action / Version 都是 ASCII 字母数字，不会被 encode，但显式过一遍以防后续加参数翻车。
        val canonicalQuery = canonicalQueryString(listOf("Action" to ACTION, "Version" to VERSION))
        val payloadHash = sha256Hex(payload)

        // SigV4 canonical headers：按 header 名小写字典序；value trim 首尾空白。
        // **Content-Type 不带 charset**——HTTP 实际发送时也保持完全相同（OkHttp 用我们手动设的 header）。
        val contentType = "application/json"
        val canonicalHeaders = buildString {
            append("content-type:$contentType\n")
            append("host:$HOST\n")
            append("x-content-sha256:$payloadHash\n")
            append("x-date:$amzDate\n")
        }
        val signedHeaders = "content-type;host;x-content-sha256;x-date"
        val canonicalRequest = listOf(
            "POST",
            "/",
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest)
        ).joinToString("\n")

        val kDate = hmacSha256(settings.volcSecretAccessKey.toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, SERVICE)
        val kSigning = hmacSha256(kService, "request")
        val signature = bytesToHex(hmacSha256(kSigning, stringToSign))

        val auth = "$ALGORITHM Credential=${settings.volcAccessKeyId}/$credentialScope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        // **关键坑**：`String.toRequestBody(MediaType)` 会在 MediaType 缺 charset 参数时
        // 自动追加 `; charset=utf-8`，然后 BridgeInterceptor 在请求发送前用 body.contentType()
        // 覆盖我们手动设的 Content-Type header，导致实际发的是 `application/json; charset=utf-8`
        // 而签名算的是 `application/json` —— SignatureDoesNotMatch。
        // 改用 `ByteArray.toRequestBody`，它不动 contentType，原样保留。
        val bodyBytes = payload.toByteArray(Charsets.UTF_8)
        val body = bodyBytes.toRequestBody(contentType.toMediaType())
        val request = Request.Builder()
            .url("https://$HOST/?$canonicalQuery")
            .header("Host", HOST)
            .header("Content-Type", contentType)
            .header("X-Date", amzDate)
            .header("X-Content-Sha256", payloadHash)
            .header("Authorization", auth)
            .post(body)
            .build()

        return timedClient.newCall(request).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                // 火山把错误也放在 ResponseMetadata.Error，错误码常见 SignatureDoesNotMatch / AccessDenied
                val parsed = runCatching { json.decodeFromString<VolcResponse>(raw) }.getOrNull()
                val err = parsed?.responseMetadata?.error
                if (err != null) throw TranslationException("Volc HTTP ${r.code} ${err.code}: ${err.message}")
                throw TranslationException("Volc HTTP ${r.code}: ${raw.take(200)}")
            }
            runCatching { json.decodeFromString<VolcResponse>(raw) }
                .getOrElse {
                    throw TranslationException("Volc 解析失败: ${raw.take(200)}", it)
                }
        }
    }

    /**
     * BCP-47 → 火山引擎语言码。覆盖 [com.gameocr.app.data.Languages.ALL] 中的常用语种，
     * 含 DeepL 现有用户配置过的所有源/目标语；不支持时返回 BCP-47 主码原样，让火山自己判断。
     * `"auto"` 返回 null（调用方据此跳过 SourceLanguage 字段）。
     */
    private fun mapLang(code: String): String? {
        val raw = code.trim()
        if (raw.isEmpty() || raw.equals("auto", true)) return null
        val lower = raw.lowercase()
        return when (lower) {
            "zh", "zh-cn", "zh-hans" -> "zh"
            "zh-tw", "zh-hant", "zh-hk" -> "zh-Hant"
            "en", "en-us", "en-gb" -> "en"
            else -> lower.substringBefore('-')
        }
    }

    companion object {
        private const val HOST = "open.volcengineapi.com"
        private const val SERVICE = "translate"
        private const val VERSION = "2020-06-01"
        private const val ACTION = "TranslateText"
        private const val ALGORITHM = "HMAC-SHA256"
    }

    private fun compactDate(ms: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(ms))
    }

    private fun compactDateTime(ms: Long): String {
        val sdf = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(ms))
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return bytesToHex(md.digest(s.toByteArray(Charsets.UTF_8)))
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * 按 SigV4 规范生成 canonical query string：key 升序 + RFC3986 percent-encode（仅保留
     * `A-Z a-z 0-9 - _ . ~`），然后 `k=v` 用 `&` 拼。`URLEncoder` 默认按 `application/x-www-form-urlencoded`
     * 编码（空格→`+`、`~` 也会被 encode），所以这里手工纠回 SigV4 的 unreserved 集。
     */
    private fun canonicalQueryString(params: List<Pair<String, String>>): String {
        if (params.isEmpty()) return ""
        return params.sortedBy { it.first }
            .joinToString("&") { (k, v) -> "${rfc3986Encode(k)}=${rfc3986Encode(v)}" }
    }

    private fun rfc3986Encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    @Serializable
    private data class VolcResponse(
        @SerialName("TranslationList") val translationList: List<TranslationItem>? = null,
        @SerialName("ResponseMetadata") val responseMetadata: Metadata? = null
    )

    @Serializable
    private data class TranslationItem(
        @SerialName("Translation") val translation: String? = null,
        @SerialName("DetectedSourceLanguage") val detectedSourceLanguage: String? = null
    )

    @Serializable
    private data class Metadata(
        @SerialName("RequestId") val requestId: String? = null,
        @SerialName("Action") val action: String? = null,
        @SerialName("Error") val error: ErrorBody? = null
    )

    @Serializable
    private data class ErrorBody(
        @SerialName("Code") val code: String? = null,
        @SerialName("CodeN") val codeN: Int? = null,
        @SerialName("Message") val message: String? = null
    )
}
