package com.gameocr.app.translate

import android.content.Context
import com.gameocr.app.R
import com.gameocr.app.data.DeeplProtocol
import com.gameocr.app.data.Settings
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * DeepL 翻译 API。
 *
 * 三种协议（[Settings.deeplProtocol]）独立切换、与 base URL 解耦：
 * - **OFFICIAL**：DeepL 官方 v2/translate；form-urlencoded；`DeepL-Auth-Key`；走 free/pro 端点。
 *   忽略 [Settings.deeplBaseUrl]。
 * - **DEEPLX**：OwO-Network/DeepLX 协议；JSON body；endpoint 用 [Settings.deeplBaseUrl] + `translate`；
 *   鉴权用 [Settings.deeplCustomToken]（可空 = 裸 deeplx）+ 可选 Bearer。
 * - **AUTO**：先 deeplx，失败 / 返回空时 fallback 到 OFFICIAL。需要同时配 deeplx URL 与 DeepL 官方 key。
 *
 * **token 隔离**：OFFICIAL 用 [Settings.deeplApiKey]，DEEPLX 用 [Settings.deeplCustomToken]，
 * 切换协议时官方 key 不会被发到第三方。
 */
@Singleton
class DeepLTranslator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache
) : Translator {

    override suspend fun translate(source: String, settings: Settings): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null

        val targetCode = mapTargetLang(settings.targetLang)
        val cacheKey = cache.key(trimmed, "deepl-$targetCode", targetCode, "")
        cache.get(cacheKey, settings)?.let { return it }

        val translated = when (settings.deeplProtocol) {
            DeeplProtocol.OFFICIAL -> {
                requireOfficialKey(settings)
                translateOnce(trimmed, settings, DeeplProtocol.OFFICIAL, targetCode)
            }
            DeeplProtocol.DEEPLX -> {
                requireDeeplxUrl(settings)
                translateOnce(trimmed, settings, DeeplProtocol.DEEPLX, targetCode)
            }
            DeeplProtocol.AUTO -> {
                requireDeeplxUrl(settings)
                requireOfficialKey(settings)
                val primary = runCatching {
                    translateOnce(trimmed, settings, DeeplProtocol.DEEPLX, targetCode)
                }
                primary.getOrNull() ?: runCatching {
                    translateOnce(trimmed, settings, DeeplProtocol.OFFICIAL, targetCode)
                }.getOrElse { officialErr ->
                    throw TranslationException(
                        "AUTO 两路都失败: deeplx=${primary.exceptionOrNull()?.message}; official=${officialErr.message}"
                    )
                }
            }
        }
        cache.put(cacheKey, translated, settings)
        return translated
    }

    /** 单段翻译（不查/不写 cache，由调用方负责）。protocol 决定 endpoint + body + parse。 */
    private suspend fun translateOnce(
        text: String,
        settings: Settings,
        protocol: DeeplProtocol,
        targetCode: String
    ): String {
        val (endpoint, request) = buildSingleRequest(settings, text, targetCode, protocol)
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        return withContext(Dispatchers.IO) {
            timedClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val message = parseError(raw) ?: raw.take(200)
                    throw TranslationException("DeepL HTTP ${resp.code} ($endpoint): $message")
                }
                if (protocol == DeeplProtocol.DEEPLX) parseDeeplxBody(raw)
                else parseOfficialFirst(raw)
            }
        }
    }

    /** 按协议构建单段请求。返回 endpoint 字符串 + Request 用于错误信息追踪。 */
    private fun buildSingleRequest(
        settings: Settings,
        text: String,
        targetCode: String,
        protocol: DeeplProtocol
    ): Pair<String, Request> {
        val sourceCode = mapSourceLang(settings.sourceLang)
        val endpoint = endpointFor(settings, protocol, path = "translate")
        val builder = Request.Builder().url(endpoint).header("Accept", "application/json")
        authHeader(settings, protocol)?.let { (k, v) -> builder.header(k, v) }

        val body = if (protocol == DeeplProtocol.DEEPLX) {
            val payload = buildJsonObject {
                put("text", JsonPrimitive(text))
                put("source_lang", JsonPrimitive(sourceCode ?: "auto"))
                put("target_lang", JsonPrimitive(targetCode))
            }
            json.encodeToString(JsonObject.serializer(), payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
        } else {
            val form = FormBody.Builder()
                .add("text", text)
                .add("target_lang", targetCode)
            if (sourceCode != null) form.add("source_lang", sourceCode)
            form.build()
        }
        return endpoint to builder.post(body).build()
    }

    /**
     * DeepL 免费档限频严格（429 容易触发），开启批处理可让 CaptureService 把一帧 OCR
     * 出的 N 段合并成 1 次 HTTP 请求，N 段计 1 次配额。OFFICIAL 协议下走真 batch；
     * DEEPLX / AUTO 退化为逐条 translate（带各自 fallback）。
     */
    override val prefersBatch: Boolean get() = true

    override suspend fun translateBatch(sources: List<String>, settings: Settings): List<String?> {
        if (sources.isEmpty()) return emptyList()
        if (settings.deeplProtocol != DeeplProtocol.OFFICIAL) {
            // deeplx 协议每次只翻一段；AUTO 也走逐条，让每段独立 fallback
            return sources.map { runCatching { translate(it, settings) }.getOrNull() }
        }
        requireOfficialKey(settings)

        val targetCode = mapTargetLang(settings.targetLang)
        val sourceCode = mapSourceLang(settings.sourceLang)

        // 先按 cache 命中拆分
        val result = arrayOfNulls<String>(sources.size)
        val pending = mutableListOf<Int>()
        for ((i, raw) in sources.withIndex()) {
            val t = raw.trim()
            if (t.isEmpty()) {
                result[i] = null
                continue
            }
            val key = cache.key(t, "deepl-$targetCode", targetCode, "")
            val hit = cache.get(key, settings)
            if (hit != null) result[i] = hit
            else pending.add(i)
        }
        if (pending.isEmpty()) return result.toList()

        val endpoint = endpointFor(settings, DeeplProtocol.OFFICIAL, path = "translate")

        val formBuilder = FormBody.Builder()
        pending.forEach { idx -> formBuilder.add("text", sources[idx].trim()) }
        formBuilder.add("target_lang", targetCode)
        if (sourceCode != null) formBuilder.add("source_lang", sourceCode)

        val request = Request.Builder()
            .url(endpoint)
            .apply { authHeader(settings, DeeplProtocol.OFFICIAL)?.let { (k, v) -> header(k, v) } }
            .header("Accept", "application/json")
            .post(formBuilder.build())
            .build()

        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        val parsed = withContext(Dispatchers.IO) {
            timedClient.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val message = parseError(raw) ?: raw.take(200)
                    throw TranslationException("DeepL HTTP ${resp.code}: $message")
                }
                runCatching { json.decodeFromString<DeepLResponse>(raw) }
                    .getOrElse {
                        throw TranslationException(
                            appContext.getString(R.string.err_deepl_parse_failed_format, raw.take(200)),
                            it
                        )
                    }
            }
        }
        for ((order, idx) in pending.withIndex()) {
            val translated = parsed.translations.getOrNull(order)?.text?.trim() ?: continue
            result[idx] = translated
            val key = cache.key(sources[idx].trim(), "deepl-$targetCode", targetCode, "")
            cache.put(key, translated, settings)
        }
        return result.toList()
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        val full = translate(source, settings) ?: return@flow
        emit(full)
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(settings: Settings): TestResult {
        return when (settings.deeplProtocol) {
            DeeplProtocol.OFFICIAL -> {
                if (settings.deeplApiKey.isBlank()) {
                    TestResult(false, appContext.getString(R.string.err_deepl_no_api_key))
                } else testOfficialUsage(settings)
            }
            DeeplProtocol.DEEPLX -> {
                if (settings.deeplBaseUrl.isBlank()) {
                    TestResult(false, "deeplx 协议需要先填 Base URL")
                } else testTranslateProbe(settings, DeeplProtocol.DEEPLX)
            }
            DeeplProtocol.AUTO -> {
                if (settings.deeplBaseUrl.isBlank() || settings.deeplApiKey.isBlank()) {
                    TestResult(false, "AUTO 模式需要同时配 deeplx Base URL 和 DeepL 官方 API Key")
                } else {
                    val deeplxResult = testTranslateProbe(settings, DeeplProtocol.DEEPLX)
                    val officialResult = testOfficialUsage(settings)
                    val overallOk = deeplxResult.success || officialResult.success
                    TestResult(
                        overallOk,
                        "[deeplx] ${deeplxResult.message}  ·  [official] ${officialResult.message}"
                    )
                }
            }
        }
    }

    private suspend fun testOfficialUsage(settings: Settings): TestResult {
        val endpoint = endpointFor(settings, DeeplProtocol.OFFICIAL, path = "usage")
        val request = Request.Builder()
            .url(endpoint)
            .apply { authHeader(settings, DeeplProtocol.OFFICIAL)?.let { (k, v) -> header(k, v) } }
            .header("Accept", "application/json")
            .get()
            .build()
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        return runCatching {
            withContext(Dispatchers.IO) {
                timedClient.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val hint = if (resp.code == 403) {
                            " (" + appContext.getString(R.string.settings_test_deepl_403_hint) + ")"
                        } else ""
                        val msg = parseError(raw) ?: raw.take(200)
                        return@use TestResult(false, "HTTP ${resp.code}: $msg$hint")
                    }
                    val usage = runCatching { json.decodeFromString<DeepLUsage>(raw) }
                        .getOrElse {
                            return@use TestResult(
                                false,
                                appContext.getString(R.string.err_deepl_parse_failed_format, raw.take(200))
                            )
                        }
                    val used = usage.characterCount
                    val limit = usage.characterLimit
                    val remainPct = if (limit > 0) ((limit - used) * 100 / limit).toInt() else 0
                    TestResult(
                        true,
                        appContext.getString(
                            R.string.settings_test_ok_deepl_format,
                            formatNumber(used),
                            formatNumber(limit),
                            remainPct
                        )
                    )
                }
            }
        }.getOrElse { e ->
            TestResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 用指定 protocol 发一次真实翻译 "Hello"，成功 → OK + 样例译文。用于 deeplx / AUTO 探活。 */
    private suspend fun testTranslateProbe(settings: Settings, protocol: DeeplProtocol): TestResult {
        val targetCode = mapTargetLang(settings.targetLang)
        val (_, request) = buildSingleRequest(settings, "Hello", targetCode, protocol)
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        return runCatching {
            withContext(Dispatchers.IO) {
                timedClient.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val msg = parseError(raw) ?: raw.take(200)
                        return@use TestResult(false, "HTTP ${resp.code}: $msg")
                    }
                    val sample = runCatching {
                        if (protocol == DeeplProtocol.DEEPLX) parseDeeplxBody(raw)
                        else parseOfficialFirst(raw)
                    }.getOrElse {
                        return@use TestResult(false, it.message ?: raw.take(200))
                    }
                    TestResult(
                        true,
                        appContext.getString(R.string.settings_test_ok_deepl_custom_format, sample.take(40))
                    )
                }
            }
        }.getOrElse { e ->
            TestResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 按 protocol 决定 endpoint：
     * - OFFICIAL：固定走 DeepL 官方 free/pro 端点（**不读** [Settings.deeplBaseUrl]）。
     * - DEEPLX：走 [Settings.deeplBaseUrl]（trim + 补尾斜杠）+ path。
     */
    private fun endpointFor(settings: Settings, protocol: DeeplProtocol, path: String): String {
        val base = when (protocol) {
            DeeplProtocol.OFFICIAL -> if (settings.deeplPro) "https://api.deepl.com/v2/"
            else "https://api-free.deepl.com/v2/"
            DeeplProtocol.DEEPLX -> {
                // 容错：用户可能填了完整 endpoint (`http://host/translate`) 或裸 base (`http://host/`)。
                // 统一剥掉尾部 `/` 和 `/translate`，再补 `/`，调用方再拼 path 不会重复。
                var raw = settings.deeplBaseUrl.trim().trimEnd('/')
                if (raw.endsWith("/translate", ignoreCase = true)) {
                    raw = raw.removeSuffix("/translate").removeSuffix("/TRANSLATE")
                }
                "$raw/"
            }
            DeeplProtocol.AUTO -> error("AUTO 不应直接到 endpointFor，由上层拆成两次单 protocol 调用")
        }
        return base + path
    }

    /**
     * 返回 Authorization header (name, value) 或 null（无 token 时跳过——裸 deeplx 部署）。
     * **token 源严格隔离**：OFFICIAL 用 [Settings.deeplApiKey]，DEEPLX 用 [Settings.deeplCustomToken]；
     * AUTO 不该走这里（已被上层拆开）。
     */
    private fun authHeader(settings: Settings, protocol: DeeplProtocol): Pair<String, String>? {
        return when (protocol) {
            DeeplProtocol.OFFICIAL -> {
                if (settings.deeplApiKey.isBlank()) null
                else "Authorization" to "DeepL-Auth-Key ${settings.deeplApiKey}"
            }
            DeeplProtocol.DEEPLX -> {
                val token = settings.deeplCustomToken
                if (token.isBlank()) return null
                val value = if (settings.deeplBearerAuth) "Bearer $token"
                else "DeepL-Auth-Key $token"
                "Authorization" to value
            }
            DeeplProtocol.AUTO -> error("AUTO 应已被拆分")
        }
    }

    private fun requireOfficialKey(settings: Settings) {
        if (settings.deeplApiKey.isBlank()) {
            throw TranslationException(appContext.getString(R.string.err_deepl_no_api_key))
        }
    }

    private fun requireDeeplxUrl(settings: Settings) {
        if (settings.deeplBaseUrl.isBlank()) {
            throw TranslationException("deeplx 协议需要在设置里填 Base URL")
        }
    }

    private fun parseOfficialFirst(raw: String): String {
        val parsed = runCatching { json.decodeFromString<DeepLResponse>(raw) }
            .getOrElse {
                throw TranslationException(
                    appContext.getString(R.string.err_deepl_parse_failed_format, raw.take(200)),
                    it
                )
            }
        return parsed.translations.firstOrNull()?.text?.trim()
            ?: throw TranslationException(appContext.getString(R.string.err_deepl_no_translation))
    }

    private fun parseDeeplxBody(raw: String): String {
        val parsed = runCatching { json.decodeFromString<DeeplxResponse>(raw) }
            .getOrElse {
                throw TranslationException(
                    appContext.getString(R.string.err_deepl_parse_failed_format, raw.take(200)),
                    it
                )
            }
        if (parsed.code != null && parsed.code != 200) {
            throw TranslationException(
                "DeepLX code ${parsed.code}: ${parsed.message ?: parsed.data ?: raw.take(120)}"
            )
        }
        return parsed.data?.trim()
            ?: throw TranslationException(appContext.getString(R.string.err_deepl_no_translation))
    }

    private fun formatNumber(n: Long): String = "%,d".format(n)

    private fun mapTargetLang(code: String): String = when (code.trim().lowercase()) {
        "zh-cn", "zh", "chinese" -> "ZH"
        "zh-tw", "zh-hant" -> "ZH-HANT"
        "en", "en-us" -> "EN-US"
        "en-gb" -> "EN-GB"
        "ja", "japanese" -> "JA"
        "ko", "korean" -> "KO"
        "fr" -> "FR"
        "de" -> "DE"
        "es" -> "ES"
        "ru" -> "RU"
        else -> code.uppercase()
    }

    private fun mapSourceLang(s: String): String? {
        val core = s.substringBefore('-').lowercase()
        return when (core) {
            "auto" -> null
            "ja", "zh", "en", "ko", "de", "fr", "es", "ru", "it", "pt", "pl",
            "nl", "tr", "uk", "id", "bg", "cs", "da", "el", "et", "fi", "hu",
            "lt", "lv", "ro", "sk", "sl", "sv" -> core.uppercase()
            else -> null
        }
    }

    private fun parseError(body: String): String? = runCatching {
        json.decodeFromString<DeepLError>(body).message
    }.getOrNull()

    @Serializable
    private data class DeepLResponse(
        val translations: List<Translation> = emptyList()
    )

    @Serializable
    private data class Translation(
        val text: String? = null,
        @SerialName("detected_source_language") val detectedSourceLanguage: String? = null
    )

    @Serializable
    private data class DeepLError(val message: String? = null)

    /**
     * deeplx 协议响应。code != 200 视为失败；data 是译文字符串。
     * 所有字段都设 nullable —— OwO-Network/DeepLX 实际响应里 `alternatives` 可能直接是 `null`
     * （而非缺失或空数组），非 null 类型反序列化会抛 MissingFieldException。
     */
    @Serializable
    private data class DeeplxResponse(
        val code: Int? = null,
        val data: String? = null,
        val message: String? = null,
        @SerialName("source_lang") val sourceLang: String? = null,
        @SerialName("target_lang") val targetLang: String? = null,
        val alternatives: List<String>? = null
    )

    /** `GET /v2/usage` 响应 schema。 */
    @Serializable
    private data class DeepLUsage(
        @SerialName("character_count") val characterCount: Long = 0,
        @SerialName("character_limit") val characterLimit: Long = 0
    )
}
