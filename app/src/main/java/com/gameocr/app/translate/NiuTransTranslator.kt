package com.gameocr.app.translate

import com.gameocr.app.BuildConfig
import com.gameocr.app.data.NiuTransMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.withApiTimeout
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

internal object NiuTransAuthPolicy {
    fun authString(apiKey: String, parameters: Map<String, String>): String {
        val entries = buildMap {
            parameters.forEach { (key, value) ->
                if (key != "authStr" && value.isNotEmpty()) put(key, value)
            }
            put("apikey", apiKey)
        }
        val plain = entries.toSortedMap().entries.joinToString("&") { (key, value) -> "$key=$value" }
        return MessageDigest.getInstance("MD5")
            .digest(plain.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal object NiuTransBatchPolicy {
    const val MAX_ITEMS = 50
    const val MAX_CHARACTERS = 5_000

    data class Entry(val index: Int, val text: String)
    data class Plan(val chunks: List<List<Entry>>, val rejectedIndexes: Set<Int>)

    fun accepts(text: String): Boolean = text.codePointCount() <= MAX_CHARACTERS

    fun plan(entries: List<Entry>): Plan {
        val chunks = mutableListOf<List<Entry>>()
        val rejected = linkedSetOf<Int>()
        var current = mutableListOf<Entry>()
        var currentCharacters = 0

        fun flush() {
            if (current.isNotEmpty()) chunks += current.toList()
            current = mutableListOf()
            currentCharacters = 0
        }

        entries.forEach { entry ->
            val characters = entry.text.codePointCount()
            if (!accepts(entry.text)) {
                rejected += entry.index
                return@forEach
            }
            if (current.size >= MAX_ITEMS || currentCharacters + characters > MAX_CHARACTERS) {
                flush()
            }
            current += entry
            currentCharacters += characters
        }
        flush()
        return Plan(chunks = chunks, rejectedIndexes = rejected)
    }

    private fun String.codePointCount(): Int = codePointCount(0, length)
}

internal object NiuTransExecutionPolicy {
    fun prefersBatch(mode: NiuTransMode): Boolean = mode == NiuTransMode.FLASH

    fun canUseNativeBatch(mode: NiuTransMode, sourceLanguage: String): Boolean =
        mode == NiuTransMode.FLASH && sourceLanguage != "auto"
}

internal object NiuTransBatchResponsePolicy {
    fun parse(body: JsonObject, expectedSources: List<String>): List<String?> {
        val items = body["tgtList"] as? JsonArray
            ?: throw TranslationException("小牛翻译批量响应缺少 tgtList")
        if (items.size != expectedSources.size) {
            throw TranslationException(
                "小牛翻译批量结果数量不匹配: expected=${expectedSources.size}, actual=${items.size}",
            )
        }
        return items.mapIndexed { index, element ->
            val item = element as? JsonObject
                ?: throw TranslationException("小牛翻译批量结果格式错误: index=$index")
            fun value(key: String): String? = (item[key] as? JsonPrimitive)?.contentOrNull
            val returnedSource = value("srcText")
            if (returnedSource != null && returnedSource != expectedSources[index]) {
                throw TranslationException("小牛翻译批量结果顺序不匹配: index=$index")
            }
            val errorCode = value("errorCode")
            if (!errorCode.isNullOrBlank() && errorCode != "200") null
            else value("tgtText")?.trim()?.takeIf(String::isNotEmpty)
        }
    }
}

internal sealed interface NiuTransSseUpdate {
    data object Start : NiuTransSseUpdate
    data class Delta(val sequence: Long?, val text: String) : NiuTransSseUpdate
    data class Done(val result: String, val finishReason: String?) : NiuTransSseUpdate
    data class Error(val code: String?, val message: String) : NiuTransSseUpdate
}

internal object NiuTransSsePolicy {
    fun parse(data: String, json: Json): NiuTransSseUpdate {
        val body = runCatching { json.parseToJsonElement(data).jsonObject }
            .getOrElse { throw TranslationException("小牛翻译流式响应解析失败: ${data.take(200)}", it) }
        fun value(key: String): String? = (body[key] as? JsonPrimitive)?.contentOrNull
        return when (value("type")) {
            "start" -> NiuTransSseUpdate.Start
            "delta" -> NiuTransSseUpdate.Delta(
                sequence = value("seq")?.toLongOrNull(),
                text = value("delta").orEmpty(),
            )
            "done" -> NiuTransSseUpdate.Done(
                result = value("result").orEmpty(),
                finishReason = value("finishReason"),
            )
            "error" -> NiuTransSseUpdate.Error(
                code = value("errorCode"),
                message = value("message") ?: "未知错误",
            )
            else -> throw TranslationException("小牛翻译未知流式事件: ${data.take(200)}")
        }
    }
}

/**
 * NiuTrans Pro limits the default account to one request per second. The cooldown starts when a
 * request leaves the gate so connection setup differences cannot make two requests arrive inside
 * the same server-side QPS window. Keep this next to the HTTP call so every caller shares it.
 */
internal class NiuTransProRequestGate(
    private val cooldownAfterCompletionMs: Long = DEFAULT_COOLDOWN_AFTER_COMPLETION_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val waitMs: suspend (Long) -> Unit = { delay(it) },
) {
    private val semaphore = Semaphore(permits = 1)
    private var lastCompletionMs: Long? = null

    suspend fun <T> run(block: suspend () -> T): T {
        semaphore.acquire()
        return try {
            awaitCooldown()
            block()
        } finally {
            lastCompletionMs = nowMs()
            semaphore.release()
        }
    }

    private suspend fun awaitCooldown() {
        while (true) {
            val previous = lastCompletionMs ?: return
            val remaining = cooldownAfterCompletionMs - (nowMs() - previous)
            if (remaining <= 0L) return
            waitMs(remaining)
        }
    }

    private companion object {
        const val DEFAULT_COOLDOWN_AFTER_COMPLETION_MS = 1_000L
    }
}

internal object NiuTransProJsonResponsePolicy {
    fun parse(raw: String, json: Json): String {
        val body = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse {
                throw TranslationException("小牛翻译响应解析失败: ${raw.take(MAX_DIAGNOSTIC_LENGTH)}", it)
            }
        fun value(key: String): String? = (body[key] as? JsonPrimitive)?.contentOrNull

        val streamType = value("type")
        if (streamType == "error") {
            throw TranslationException(
                "小牛翻译 ${value("errorCode") ?: "error"}: " +
                    (value("message") ?: value("errorMsg") ?: "未知错误"),
            )
        }
        val errorCode = value("errorCode")
        if (!errorCode.isNullOrBlank() && errorCode != "200") {
            throw TranslationException(
                "小牛翻译 $errorCode: ${value("errorMsg") ?: value("message") ?: "未知错误"}",
            )
        }
        val resultCode = value("resultCode")
        if (!resultCode.isNullOrBlank() && resultCode != "200") {
            throw TranslationException(
                "小牛翻译 $resultCode: ${value("resultMsg") ?: value("message") ?: "请求失败"}",
            )
        }

        return when (streamType) {
            "done" -> value("result")
            else -> value("tgtText")
        }?.trim()?.takeIf(String::isNotEmpty)
            ?: throw TranslationException("小牛翻译返回空译文")
    }

    fun isJsonContentType(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.endsWith("/json", ignoreCase = true) == true ||
            contentType?.substringBefore(';')?.trim()?.endsWith("+json", ignoreCase = true) == true

    private const val MAX_DIAGNOSTIC_LENGTH = 200
}

internal object NiuTransDiagnosticPolicy {
    private val sensitiveJsonValue = Regex(
        pattern = "(?i)(\\\"(?:apikey|authStr|authorization|token)\\\"\\s*:\\s*\\\")[^\\\"]*",
    )

    fun sanitize(raw: String): String = raw
        .replace(sensitiveJsonValue, "$1***")
        .replace(Regex("[\\r\\n]+"), " ")
        .take(MAX_LENGTH)

    private const val MAX_LENGTH = 500
}

internal sealed interface NiuTransStreamEndResolution {
    data object Complete : NiuTransStreamEndResolution
    data class JsonFallback(val raw: String) : NiuTransStreamEndResolution
    data class Incomplete(val message: String) : NiuTransStreamEndResolution
}

internal object NiuTransStreamEndPolicy {
    fun resolve(
        completed: Boolean,
        eventCount: Int,
        deltaCount: Int,
        nonSseBody: String,
    ): NiuTransStreamEndResolution = when {
        completed -> NiuTransStreamEndResolution.Complete
        eventCount == 0 && nonSseBody.isNotBlank() ->
            NiuTransStreamEndResolution.JsonFallback(nonSseBody)
        else -> NiuTransStreamEndResolution.Incomplete(
            "小牛翻译流式响应未正常结束 (events=$eventCount, deltas=$deltaCount)",
        )
    }
}

/** Official NiuTrans v2 text translation integration. */
@Singleton
class NiuTransTranslator @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache,
) : Translator {
    private val proRequestGate = NiuTransProRequestGate()

    fun prefersBatch(settings: Settings): Boolean =
        NiuTransExecutionPolicy.prefersBatch(settings.niuTransMode)

    override suspend fun translate(source: String, settings: Settings): String? {
        val text = source.trim()
        if (text.isEmpty()) return null
        requireTextLength(text)
        val from = requireSource(settings)
        val to = requireTarget(settings)
        val cacheKey = cacheKey(text, settings, to)
        cache.get(cacheKey, settings)?.let { return it }

        val translated = when (settings.niuTransMode) {
            NiuTransMode.FLASH -> translateFlash(text, from, to, settings)
            NiuTransMode.PRO -> translatePro(text, from, to, settings, stream = false)
        }
        cache.put(cacheKey, translated, settings)
        return translated
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> {
        if (settings.niuTransMode != NiuTransMode.PRO) {
            return flow {
                translate(source, settings)?.let { emit(it) }
            }.flowOn(Dispatchers.IO)
        }
        return flow {
            val text = source.trim()
            if (text.isEmpty()) return@flow
            requireTextLength(text)
            validateCredentials(settings)
            val from = requireSource(settings)
            val to = requireTarget(settings)
            val cacheKey = cacheKey(text, settings, to)
            cache.get(cacheKey, settings)?.let {
                emit(it)
                return@flow
            }

            proRequestGate.run {
            val request = Request.Builder()
                .url(PRO_ENDPOINT)
                .header("Accept", "text/event-stream")
                .post(proPayload(text, from, to, settings, stream = true).asRequestBody())
                .build()
            val call = client.withApiTimeout(settings.apiTimeoutSeconds).newCall(request)
            val cancellation = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
            val startedAt = System.nanoTime()
            var completionOutcome = "exception"
            var responseSample = ""
            var contentType: String? = null
            var eventCount = 0
            var deltaCount = 0
            var keepAliveCount = 0
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        responseSample = raw
                        completionOutcome = "http_${response.code}"
                        throw TranslationException("小牛翻译 HTTP ${response.code}: ${raw.take(200)}")
                    }
                    val responseBody = response.body
                        ?: throw TranslationException("小牛翻译返回空响应")
                    contentType = responseBody.contentType()?.toString()
                    if (NiuTransProJsonResponsePolicy.isJsonContentType(contentType)) {
                        val raw = responseBody.string()
                        responseSample = raw
                        val finalText = NiuTransProJsonResponsePolicy.parse(raw, json)
                        emit(finalText)
                        cache.put(cacheKey, finalText, settings)
                        completionOutcome = "json"
                        return@use
                    }

                    val sourceBuffer = responseBody.source()
                    var eventName: String? = null
                    val dataLines = mutableListOf<String>()
                    val nonSseBody = StringBuilder()
                    val completedSequences = linkedSetOf<Long>()
                    val accumulated = StringBuilder()
                    var completed = false

                    suspend fun dispatch() {
                        if (dataLines.isEmpty()) return
                        eventCount++
                        if (eventName == "ping" || eventName == "keepalive") {
                            keepAliveCount++
                            dataLines.clear()
                            return
                        }
                        val rawEvent = dataLines.joinToString("\n")
                        val update = runCatching { NiuTransSsePolicy.parse(rawEvent, json) }
                            .getOrElse { error ->
                                completionOutcome = "sse_parse_error"
                                responseSample = rawEvent
                                throw error
                            }
                        when (update) {
                            NiuTransSseUpdate.Start -> Unit
                            is NiuTransSseUpdate.Delta -> {
                                deltaCount++
                                if (update.sequence == null || completedSequences.add(update.sequence)) {
                                    accumulated.append(update.text)
                                    if (accumulated.isNotEmpty()) emit(accumulated.toString())
                                }
                            }
                            is NiuTransSseUpdate.Done -> {
                                val finalText = update.result.trim().ifEmpty { accumulated.toString().trim() }
                                if (finalText.isEmpty()) throw TranslationException("小牛翻译返回空译文")
                                if (finalText != accumulated.toString()) emit(finalText)
                                cache.put(cacheKey, finalText, settings)
                                completed = true
                                completionOutcome = "sse_done"
                            }
                            is NiuTransSseUpdate.Error -> {
                                completionOutcome = "sse_error"
                                responseSample = dataLines.joinToString("\n")
                                throw TranslationException(
                                    "小牛翻译 ${update.code ?: "error"}: ${update.message}",
                                )
                            }
                        }
                        dataLines.clear()
                    }

                    while (!sourceBuffer.exhausted()) {
                        currentCoroutineContext().ensureActive()
                        val line = sourceBuffer.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> {
                                dispatch()
                                eventName = null
                            }
                            line.startsWith(":") -> keepAliveCount++
                            line.startsWith("event:") -> eventName = line.substringAfter(':').trim()
                            line.startsWith("data:") -> dataLines += line.substringAfter(':').trimStart()
                            else -> if (nonSseBody.length < MAX_FALLBACK_BODY_LENGTH) {
                                if (nonSseBody.isNotEmpty()) nonSseBody.append('\n')
                                nonSseBody.append(line.take(MAX_FALLBACK_BODY_LENGTH - nonSseBody.length))
                            }
                        }
                        if (completed) break
                    }
                    if (!completed && dataLines.isNotEmpty()) dispatch()
                    when (val end = NiuTransStreamEndPolicy.resolve(
                        completed = completed,
                        eventCount = eventCount,
                        deltaCount = deltaCount,
                        nonSseBody = nonSseBody.toString(),
                    )) {
                        NiuTransStreamEndResolution.Complete -> Unit
                        is NiuTransStreamEndResolution.JsonFallback -> {
                            responseSample = end.raw
                            val finalText = NiuTransProJsonResponsePolicy.parse(end.raw, json)
                            emit(finalText)
                            cache.put(cacheKey, finalText, settings)
                            completionOutcome = "json_fallback"
                        }
                        is NiuTransStreamEndResolution.Incomplete -> {
                            completionOutcome = "sse_incomplete"
                            responseSample = nonSseBody.toString()
                            throw TranslationException(end.message)
                        }
                    }
                }
            } finally {
                cancellation.dispose()
                logProStreamDiagnostic(
                    outcome = completionOutcome,
                    contentType = contentType,
                    eventCount = eventCount,
                    deltaCount = deltaCount,
                    keepAliveCount = keepAliveCount,
                    elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
                    responseSample = responseSample,
                )
            }
            }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun translateBatch(sources: List<String>, settings: Settings): List<String?> =
        translateBatchIncremental(sources, settings) { }

    override suspend fun translateBatchIncremental(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> {
        if (sources.isEmpty()) return emptyList()
        validateCredentials(settings)
        val result = MutableList<String?>(sources.size) { null }
        val from = requireSource(settings)
        val to = requireTarget(settings)
        val pending = mutableListOf<NiuTransBatchPolicy.Entry>()
        sources.forEachIndexed { index, raw ->
            val text = raw.trim()
            if (text.isEmpty()) return@forEachIndexed
            if (!NiuTransBatchPolicy.accepts(text)) {
                onUpdate(BatchTranslationUpdate(index, null, 0L))
                return@forEachIndexed
            }
            val key = cacheKey(text, settings, to)
            val cached = cache.get(key, settings)
            if (cached != null) {
                result[index] = cached
                onUpdate(BatchTranslationUpdate(index, cached, 0L))
            } else {
                pending += NiuTransBatchPolicy.Entry(index, text)
            }
        }
        if (pending.isEmpty()) return result

        if (!NiuTransExecutionPolicy.canUseNativeBatch(settings.niuTransMode, from)) {
            // Pro's documented default concurrency is 1. Flash array does not document `auto`, so
            // both cases stay inside this translator and execute sequentially.
            pending.forEach { entry ->
                val started = System.nanoTime()
                val translated = runCatching {
                    when (settings.niuTransMode) {
                        NiuTransMode.FLASH -> translateFlash(entry.text, from, to, settings)
                        NiuTransMode.PRO -> translatePro(entry.text, from, to, settings, stream = false)
                    }
                }.getOrNull()
                result[entry.index] = translated
                if (translated != null) {
                    cache.put(cacheKey(entry.text, settings, to), translated, settings)
                }
                onUpdate(
                    BatchTranslationUpdate(
                        entry.index,
                        translated,
                        (System.nanoTime() - started) / 1_000_000L,
                    )
                )
            }
            return result
        }

        val plan = NiuTransBatchPolicy.plan(pending)
        plan.rejectedIndexes.forEach { index -> onUpdate(BatchTranslationUpdate(index, null, 0L)) }
        plan.chunks.forEach { chunk ->
            val started = System.nanoTime()
            val translated = translateFlashBatch(chunk.map(NiuTransBatchPolicy.Entry::text), from, to, settings)
            translated.forEachIndexed { order, text ->
                val entry = chunk[order]
                result[entry.index] = text
                if (text != null) cache.put(cacheKey(entry.text, settings, to), text, settings)
                onUpdate(
                    BatchTranslationUpdate(
                        entry.index,
                        text,
                        (System.nanoTime() - started) / 1_000_000L,
                    )
                )
            }
        }
        return result
    }

    override suspend fun testConnection(settings: Settings): TestResult {
        return runCatching {
            validateCredentials(settings)
            val started = System.nanoTime()
            val translated = when (settings.niuTransMode) {
                NiuTransMode.FLASH -> translateFlash("hello", "en", "zh", settings)
                NiuTransMode.PRO -> translatePro("hello", "en", "zh", settings, stream = false)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            if (translated.isNullOrBlank()) TestResult(false, "返回空译文")
            else TestResult(true, "OK ${elapsedMs}ms")
        }.getOrElse { TestResult(false, it.message ?: it.javaClass.simpleName) }
    }

    private suspend fun translateFlash(
        text: String,
        from: String,
        to: String,
        settings: Settings,
    ): String {
        validateCredentials(settings)
        val timestamp = System.currentTimeMillis().toString()
        val signing = linkedMapOf(
            "appId" to settings.niuTransAppId.trim(),
            "from" to from,
            "srcText" to text,
            "timestamp" to timestamp,
            "to" to to,
            "termLibraryId" to settings.niuTransTermLibraryId.trim(),
            "memoryLibraryId" to settings.niuTransMemoryLibraryId.trim(),
        ).filterValues(String::isNotEmpty)
        val payload = buildJsonObject {
            signing.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put("authStr", JsonPrimitive(NiuTransAuthPolicy.authString(settings.niuTransApiKey, signing)))
        }
        return executeTextRequest(FLASH_ENDPOINT, payload, settings)
    }

    private suspend fun translatePro(
        text: String,
        from: String,
        to: String,
        settings: Settings,
        stream: Boolean,
    ): String = proRequestGate.run {
        executeTextRequest(
            PRO_ENDPOINT,
            proPayload(text, from, to, settings, stream),
            settings,
        )
    }

    private fun logProStreamDiagnostic(
        outcome: String,
        contentType: String?,
        eventCount: Int,
        deltaCount: Int,
        keepAliveCount: Int,
        elapsedMs: Long,
        responseSample: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val summary = buildString {
            append("outcome=").append(outcome)
            append(" contentType=").append(contentType ?: "unknown")
            append(" events=").append(eventCount)
            append(" deltas=").append(deltaCount)
            append(" keepAlive=").append(keepAliveCount)
            append(" elapsedMs=").append(elapsedMs)
        }
        if (outcome == "sse_done" || outcome == "json" || outcome == "json_fallback") {
            Timber.tag(PRO_STREAM_LOG_TAG).i(summary)
        } else {
            Timber.tag(PRO_STREAM_LOG_TAG).w(
                "%s response=%s",
                summary,
                NiuTransDiagnosticPolicy.sanitize(responseSample).ifBlank { "<empty>" },
            )
        }
    }

    private fun proPayload(
        text: String,
        from: String,
        to: String,
        settings: Settings,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("from", JsonPrimitive(from))
        put("to", JsonPrimitive(to))
        put("apikey", JsonPrimitive(settings.niuTransApiKey.trim()))
        put("srcText", JsonPrimitive(text))
        put("stream", JsonPrimitive(stream))
        settings.niuTransTermLibraryId.trim().takeIf(String::isNotEmpty)?.let {
            put("termLibraryId", JsonPrimitive(it))
        }
        settings.niuTransMemoryLibraryId.trim().takeIf(String::isNotEmpty)?.let {
            put("memoryLibraryId", JsonPrimitive(it))
        }
    }

    private suspend fun translateFlashBatch(
        texts: List<String>,
        from: String,
        to: String,
        settings: Settings,
    ): List<String?> {
        val timestamp = System.currentTimeMillis().toString()
        // Official array/json signing rules explicitly exclude srcText.
        val signing = linkedMapOf(
            "appId" to settings.niuTransAppId.trim(),
            "from" to from,
            "timestamp" to timestamp,
            "to" to to,
            "termLibraryId" to settings.niuTransTermLibraryId.trim(),
            "memoryLibraryId" to settings.niuTransMemoryLibraryId.trim(),
        ).filterValues(String::isNotEmpty)
        val payload = buildJsonObject {
            signing.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put("srcText", buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } })
            put("authStr", JsonPrimitive(NiuTransAuthPolicy.authString(settings.niuTransApiKey, signing)))
        }
        val raw = executeRaw(BATCH_ENDPOINT, payload, settings)
        val body = parseObject(raw)
        body.throwIfError()
        return NiuTransBatchResponsePolicy.parse(body, texts)
    }

    private suspend fun executeTextRequest(
        endpoint: String,
        payload: JsonObject,
        settings: Settings,
    ): String {
        val body = parseObject(executeRaw(endpoint, payload, settings))
        body.throwIfError()
        return body.string("tgtText")?.trim()?.takeIf(String::isNotEmpty)
            ?: throw TranslationException("小牛翻译返回空译文")
    }

    private suspend fun executeRaw(
        endpoint: String,
        payload: JsonObject,
        settings: Settings,
    ): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/json")
            .post(payload.asRequestBody())
            .build()
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            client.withApiTimeout(settings.apiTimeoutSeconds).newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw TranslationException("小牛翻译 HTTP ${response.code}: ${raw.take(200)}")
                }
                raw
            }
        }
    }

    private fun parseObject(raw: String): JsonObject =
        runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw TranslationException("小牛翻译响应解析失败: ${raw.take(200)}", it) }

    private fun JsonObject.throwIfError() {
        val errorCode = string("errorCode")
        if (!errorCode.isNullOrBlank() && errorCode != "200") {
            throw TranslationException("小牛翻译 $errorCode: ${string("errorMsg") ?: "未知错误"}")
        }
        val resultCode = string("resultCode")
        if (!resultCode.isNullOrBlank() && resultCode != "200" && this["tgtList"] == null) {
            throw TranslationException("小牛翻译 $resultCode: ${string("resultMsg") ?: "请求失败"}")
        }
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.asRequestBody() =
        json.encodeToString(JsonElement.serializer(), this)
            .toRequestBody(JSON_MEDIA_TYPE)

    private fun validateCredentials(settings: Settings) {
        if (settings.niuTransApiKey.isBlank()) throw TranslationException("缺少小牛翻译 API Key")
        if (settings.niuTransMode == NiuTransMode.FLASH && settings.niuTransAppId.isBlank()) {
            throw TranslationException("Flash 需要配置小牛翻译 App ID")
        }
    }

    private fun requireSource(settings: Settings): String =
        NiuTransLanguageCatalog.mapSource(settings.sourceLang, settings.niuTransMode)
            ?: throw TranslationException("小牛翻译不支持源语言: ${com.gameocr.app.data.languageDisplayName(settings.sourceLang)}")

    private fun requireTarget(settings: Settings): String =
        NiuTransLanguageCatalog.mapTarget(settings.targetLang, settings.niuTransMode)
            ?: throw TranslationException("小牛翻译不支持目标语言: ${com.gameocr.app.data.languageDisplayName(settings.targetLang)}")

    private fun requireTextLength(text: String) {
        if (text.codePointCount(0, text.length) > NiuTransBatchPolicy.MAX_CHARACTERS) {
            throw TranslationException("小牛翻译请求超过 5000 字符")
        }
    }

    private fun cacheKey(source: String, settings: Settings, target: String): String {
        val model = buildString {
            append("niutrans-")
            append(settings.niuTransMode.name.lowercase())
            append("-from=").append(settings.sourceLang.trim().lowercase())
            append("-term=").append(settings.niuTransTermLibraryId.trim())
            append("-memory=").append(settings.niuTransMemoryLibraryId.trim())
        }
        return cache.key(source, model, target, "")
    }

    companion object {
        private const val FLASH_ENDPOINT = "https://api.niutrans.com/v2/text/translate"
        private const val PRO_ENDPOINT = "https://api.niutrans.com/v2/text/translate/llm"
        private const val BATCH_ENDPOINT = "https://api.niutrans.com/v2/text/translate/array"
        private const val PRO_STREAM_LOG_TAG = "NiuTransSse"
        private const val MAX_FALLBACK_BODY_LENGTH = 4_096
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
