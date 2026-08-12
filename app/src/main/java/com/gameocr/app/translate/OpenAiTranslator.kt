package com.gameocr.app.translate

import android.content.Context
import com.gameocr.app.R
import com.gameocr.app.data.Languages
import com.gameocr.app.data.Settings
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * OpenAI 兼容 chat completions（支持 stream / non-stream）。
 *
 * 适配：OpenAI、DeepSeek、Kimi、SiliconFlow、Ollama OpenAI 兼容端点。
 * Base URL 写到 `/v1/`（带斜杠），最终请求 `${baseUrl}chat/completions`。
 */
@Singleton
class OpenAiTranslator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache
) : Translator {

    override val supportsStructuredContextBatch: Boolean = true

    override fun handlesTranslationFailureRetry(settings: Settings): Boolean =
        shouldUseStructuredBatch(settings)

    override fun batchPromptScope(settings: Settings): BatchPromptScope =
        if (shouldUseStructuredBatch(settings)) {
            BatchPromptScope.SHARED_PAGE
        } else {
            BatchPromptScope.ISOLATED_ITEMS
        }

    override suspend fun translateBatch(
        sources: List<String>,
        settings: Settings,
    ): List<String?> = if (shouldUseStructuredBatch(settings)) {
        translateStructuredBatch(sources, settings) { }
    } else {
        super.translateBatch(sources, settings)
    }

    override suspend fun translateBatchIncremental(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> = if (shouldUseStructuredBatch(settings)) {
        translateStructuredBatch(sources, settings, onUpdate)
    } else {
        super.translateBatchIncremental(sources, settings, onUpdate)
    }

    private suspend fun translateStructuredBatch(
        sources: List<String>,
        settings: Settings,
        onUpdate: (BatchTranslationUpdate) -> Unit,
    ): List<String?> {
        if (sources.isEmpty()) return emptyList()
        validate(settings)
        val capabilityKey = RemoteStructuredOutputCapability.openAiKey(settings)
        if (!RemoteStructuredOutputCapability.tracker.shouldAttemptStructured(capabilityKey)) {
            Timber.w(
                "OpenAI structuredBatch bypassed count=%d model=%s reason=capability_cooldown",
                sources.size,
                settings.model,
            )
            return translateStructuredFallbackIndividually(
                sources = sources,
                settings = settings,
                onUpdate = onUpdate,
                translateOne = ::translate,
            )
        }
        Timber.i(
            "OpenAI structuredBatch started count=%d model=%s",
            sources.size,
            settings.model,
        )
        var completeStructuredBatchObserved = false
        val results = try {
            StructuredBatchTranslationRunner.translate(
                sources = sources,
                json = json,
                onUpdate = onUpdate,
                retryEnabled = settings.retryFailedTranslation,
                shouldRetryTransportFailure = StructuredBatchTransportRetryPolicy::shouldRetry,
                onParsed = { attempt, parsed ->
                    completeStructuredBatchObserved =
                        completeStructuredBatchObserved || parsed.batchComplete
                    Timber.i(
                        "OpenAI structuredBatch parsed ids=%s candidates=%d accepted=%d complete=%s unresolved=%s duplicates=%s unknown=%s structured=%s",
                        attempt.activeIds,
                        parsed.candidateCount,
                        parsed.translationsByIndex.size,
                        parsed.batchComplete,
                        parsed.unresolvedIndexes.map { it + 1 },
                        parsed.duplicateIds,
                        parsed.unknownIds,
                        parsed.structuredPayloadFound,
                    )
                },
            ) { attempt ->
                executeStructuredBatchAttempt(attempt, settings)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!settings.retryFailedTranslation) throw error
            Timber.w(
                error,
                "OpenAI structuredBatch fallback count=%d model=%s reason=transport_failure",
                sources.size,
                settings.model,
            )
            return translateStructuredFallbackIndividually(
                sources = sources,
                settings = settings,
                onUpdate = onUpdate,
                translateOne = ::translate,
            )
        }
        RemoteStructuredOutputCapability.tracker.record(
            capabilityKey,
            completeStructuredBatchObserved,
        )
        if (results.any { !it.isNullOrBlank() } || !settings.retryFailedTranslation) return results
        Timber.w(
            "OpenAI structuredBatch fallback count=%d model=%s reason=no_accepted_results",
            sources.size,
            settings.model,
        )
        return translateStructuredFallbackIndividually(
            sources = sources,
            settings = settings,
            onUpdate = onUpdate,
            translateOne = ::translate,
        )
    }

    private suspend fun executeStructuredBatchAttempt(
        attempt: StructuredBatchAttempt,
        settings: Settings,
    ): String {
        val resolvedRequest = resolveRequest(
            text = StructuredBatchPromptPolicy.buildUserPayload(
                attempt,
                settings.openAiRequestOptions,
            ),
            settings = settings,
            runtimeContext = StructuredBatchPromptPolicy.buildSystemSuffix(
                settings.runtimeTranslationPromptContext,
                settings.openAiRequestOptions,
                activeSources = attempt.allSources,
            ),
            textAlreadyPrepared = true,
        )
        val requestId = UUID.randomUUID().toString().take(8)
        val startedAt = System.currentTimeMillis()
        val stream = settings.streamingTranslate
        val request = buildRequest(
            resolved = resolvedRequest,
            settings = settings,
            stream = stream,
            responseFormat = deepSeekJsonObjectResponseFormatOrNull(settings.baseUrl),
        )
        TranslationRequestAudit.log(
            requestId, "OPENAI", "translation_batch", stream, request,
        )
        Timber.i(
            "OpenAI request=%s started kind=translation_batch ids=%s model=%s",
            requestId,
            attempt.activeIds,
            settings.model,
        )
        return try {
            withContext(Dispatchers.IO) {
                client.withApiTimeout(resolvedRequest.timeoutSeconds).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        throw TranslationException("HTTP ${response.code}: ${raw.take(200)}")
                    }
                    if (stream) {
                        readStructuredOpenAiStream(response, requestId, startedAt)
                    } else {
                        val raw = response.body?.string().orEmpty()
                        val parsed = runCatching { json.decodeFromString<ChatResponse>(raw) }
                            .getOrElse { error ->
                                throw TranslationException(
                                    appContext.getString(R.string.err_openai_parse_failed_format, raw.take(200)),
                                    error,
                                )
                            }
                        parsed.choices.firstOrNull()?.message?.content?.trim()
                            ?: throw TranslationException(appContext.getString(R.string.err_openai_no_choices))
                    }
                }
            }.also { translated ->
                TranslationRequestAudit.logStructuredResponse(
                    requestId = requestId,
                    engine = "OPENAI",
                    body = translated,
                )
                Timber.i(
                    "OpenAI request=%s completed kind=translation_batch elapsedMs=%d outputChars=%d",
                    requestId,
                    System.currentTimeMillis() - startedAt,
                    translated.length,
                )
            }
        } catch (error: CancellationException) {
            Timber.i(
                "OpenAI request=%s cancelled kind=translation_batch elapsedMs=%d",
                requestId,
                System.currentTimeMillis() - startedAt,
            )
            throw error
        } catch (error: Throwable) {
            Timber.w(
                error,
                "OpenAI request=%s failed kind=translation_batch elapsedMs=%d",
                requestId,
                System.currentTimeMillis() - startedAt,
            )
            throw error
        }
    }

    private fun shouldUseStructuredBatch(settings: Settings): Boolean =
        settings.runtimeTranslationPromptContext.currentPage.isNotEmpty()

    private fun readStructuredOpenAiStream(
        response: okhttp3.Response,
        requestId: String,
        startedAt: Long,
    ): String {
        val body = response.body ?: throw TranslationException("empty response body")
        val accumulated = StringBuilder()
        var firstTokenLogged = false
        var lineCount = 0
        var keepAliveCount = 0
        var dataEventCount = 0
        var contentEventCount = 0
        var malformedEventCount = 0
        var finishReason: String? = null
        var endReason = "eof"
        try {
            body.source().use { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    lineCount += 1
                    when (val event = parseOpenAiStreamLine(line, json)) {
                        OpenAiStreamEvent.KeepAlive -> keepAliveCount += 1
                        OpenAiStreamEvent.Done -> {
                            endReason = "done"
                            break
                        }
                        OpenAiStreamEvent.Ignore -> Unit
                        is OpenAiStreamEvent.Malformed -> {
                            malformedEventCount += 1
                            if (malformedEventCount <= MAX_LOGGED_MALFORMED_STREAM_EVENTS) {
                                TranslationRequestAudit.logMalformedStreamEvent(
                                    requestId = requestId,
                                    engine = "OPENAI",
                                    eventIndex = malformedEventCount,
                                    payload = event.payload,
                                )
                            }
                        }
                        is OpenAiStreamEvent.Data -> {
                            dataEventCount += 1
                            event.finishReason?.let { finishReason = it }
                            if (event.content.isEmpty()) continue
                            contentEventCount += 1
                            if (!firstTokenLogged) {
                                firstTokenLogged = true
                                Timber.i(
                                    "OpenAI request=%s firstTokenMs=%d kind=translation_batch",
                                    requestId,
                                    System.currentTimeMillis() - startedAt,
                                )
                            }
                            accumulated.append(event.content)
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            endReason = "error:${error.javaClass.simpleName}"
            throw error
        } finally {
            Timber.i(
                "OpenAI request=%s streamSummary kind=translation_batch elapsedMs=%d end=%s " +
                    "lines=%d keepAlive=%d dataEvents=%d contentEvents=%d malformed=%d " +
                    "finishReason=%s outputChars=%d",
                requestId,
                System.currentTimeMillis() - startedAt,
                endReason,
                lineCount,
                keepAliveCount,
                dataEventCount,
                contentEventCount,
                malformedEventCount,
                finishReason ?: "none",
                accumulated.length,
            )
        }
        return accumulated.toString().trim()
            .takeIf(String::isNotEmpty)
            ?: throw TranslationException(appContext.getString(R.string.err_openai_no_choices))
    }

    override suspend fun translate(source: String, settings: Settings): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        validate(settings)

        val resolvedRequest = resolveRequest(trimmed, settings)

        val cacheKey = cache.key(
            trimmed,
            settings.model,
            settings.targetLang,
            resolvedRequest.cacheFingerprint,
        )
        cache.get(cacheKey, settings)?.let { return it }

        val requestId = UUID.randomUUID().toString().take(8)
        val startedAt = System.currentTimeMillis()
        Timber.i("OpenAI request=%s started stream=false model=%s", requestId, settings.model)
        val request = buildRequest(resolvedRequest, settings, stream = false)
        TranslationRequestAudit.log(
            requestId, "OPENAI", "translation", false, request,
        )
        val timedClient = client.withApiTimeout(resolvedRequest.timeoutSeconds)
        val translated = try {
            withContext(Dispatchers.IO) {
                timedClient.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw TranslationException("HTTP ${resp.code}: ${raw.take(200)}")
                    val parsed = runCatching { json.decodeFromString<ChatResponse>(raw) }
                        .getOrElse {
                            throw TranslationException(
                                appContext.getString(R.string.err_openai_parse_failed_format, raw.take(200)),
                                it
                            )
                        }
                    parsed.choices.firstOrNull()?.message?.content?.trim()
                        ?: throw TranslationException(appContext.getString(R.string.err_openai_no_choices))
                }
            }
        } catch (error: CancellationException) {
            Timber.i(
                "OpenAI request=%s cancelled elapsedMs=%d reason=coroutine_cancelled",
                requestId,
                System.currentTimeMillis() - startedAt,
            )
            throw error
        } catch (error: Throwable) {
            Timber.w(
                error,
                "OpenAI request=%s failed elapsedMs=%d",
                requestId,
                System.currentTimeMillis() - startedAt,
            )
            throw error
        }
        Timber.i(
            "OpenAI request=%s completed elapsedMs=%d outputChars=%d",
            requestId,
            System.currentTimeMillis() - startedAt,
            translated.length,
        )
        cache.put(cacheKey, translated, settings)
        return translated
    }

    override fun translateStream(source: String, settings: Settings): Flow<String> = flow {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return@flow
        validate(settings)

        val resolvedRequest = resolveRequest(trimmed, settings)

        val cacheKey = cache.key(
            trimmed,
            settings.model,
            settings.targetLang,
            resolvedRequest.cacheFingerprint,
        )
        cache.get(cacheKey, settings)?.let {
            emit(it)
            return@flow
        }

        val requestId = UUID.randomUUID().toString().take(8)
        val startedAt = System.currentTimeMillis()
        var firstTokenLogged = false
        Timber.i("OpenAI request=%s started stream=true model=%s", requestId, settings.model)
        val request = buildRequest(resolvedRequest, settings, stream = true)
        TranslationRequestAudit.log(
            requestId, "OPENAI", "translation", true, request,
        )
        val timedClient = client.withApiTimeout(resolvedRequest.timeoutSeconds)
        val response = try {
            timedClient.newCall(request).execute()
        } catch (error: CancellationException) {
            Timber.i(
                "OpenAI request=%s cancelled elapsedMs=%d reason=coroutine_cancelled",
                requestId,
                System.currentTimeMillis() - startedAt,
            )
            throw error
        } catch (error: Throwable) {
            Timber.w(
                error,
                "OpenAI request=%s failed elapsedMs=%d before_response=true",
                requestId,
                System.currentTimeMillis() - startedAt,
            )
            throw error
        }
        if (!response.isSuccessful) {
            val raw = response.body?.string().orEmpty()
            response.close()
            throw TranslationException("HTTP ${response.code}: ${raw.take(200)}")
        }
        val body = response.body ?: run {
            response.close()
            throw TranslationException("empty response body")
        }

        val acc = StringBuilder()
        try {
            body.source().use { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val delta = runCatching {
                        json.decodeFromString<ChatStreamChunk>(payload)
                    }.getOrNull()?.choices?.firstOrNull()?.delta?.content ?: continue
                    if (!firstTokenLogged && delta.isNotEmpty()) {
                        firstTokenLogged = true
                        Timber.i(
                            "OpenAI request=%s firstTokenMs=%d",
                            requestId,
                            System.currentTimeMillis() - startedAt,
                        )
                    }
                    acc.append(delta)
                    emit(acc.toString())
                }
            }
        } catch (error: CancellationException) {
            Timber.i(
                "OpenAI request=%s cancelled elapsedMs=%d reason=stream_collector_cancelled",
                requestId,
                System.currentTimeMillis() - startedAt,
            )
            throw error
        } catch (error: Throwable) {
            Timber.w(
                error,
                "OpenAI request=%s failed elapsedMs=%d outputChars=%d",
                requestId,
                System.currentTimeMillis() - startedAt,
                acc.length,
            )
            throw error
        } finally {
            response.close()
        }
        Timber.i(
            "OpenAI request=%s completed elapsedMs=%d outputChars=%d",
            requestId,
            System.currentTimeMillis() - startedAt,
            acc.length,
        )
        if (acc.isNotEmpty()) cache.put(cacheKey, acc.toString(), settings)
    }.flowOn(Dispatchers.IO)

    /**
     * 测试连通性：优先 `GET ${baseUrl}models` 拉 model 列表（多数 OpenAI 兼容厂商都提供，
     * 且不消耗 token / 配额），成功就把 model id 列表回给 UI 当下拉候选。失败则降级发一次
     * 最小 chat completions 当探活（max_tokens=1，"ping"）。
     */
    override suspend fun testConnection(settings: Settings): TestResult {
        if (settings.apiKey.isBlank()) {
            return TestResult(false, appContext.getString(R.string.err_openai_no_api_key))
        }
        val timedClient = client.withApiTimeout(
            OpenAiRequestPolicy.remoteLlmTimeoutSeconds(settings.apiTimeoutSeconds),
        )
        val modelsUrl = ensureSlash(settings.baseUrl) + "models"
        val modelsReq = Request.Builder()
            .url(modelsUrl)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Accept", "application/json")
            .get()
            .build()
        val modelsResult = runCatching {
            withContext(Dispatchers.IO) {
                timedClient.newCall(modelsReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val raw = resp.body?.string().orEmpty()
                    runCatching { json.decodeFromString<ModelsResponse>(raw) }
                        .getOrNull()
                        ?.data
                        ?.mapNotNull { it.id }
                        ?.distinct()
                        ?.sorted()
                }
            }
        }.getOrNull()
        if (!modelsResult.isNullOrEmpty()) {
            return TestResult(
                true,
                appContext.getString(R.string.settings_test_ok_openai_models_format, modelsResult.size),
                models = modelsResult
            )
        }
        // 降级：发一次 max_tokens=1 的最小 chat completions。能跑通说明 baseUrl/key/model 都对。
        return runCatching {
            val body = ChatRequest(
                model = settings.model,
                messages = listOf(ChatMessage(role = "user", content = "ping")),
                temperature = 0.0,
                stream = false,
                maxTokens = 1
            )
            val payload = json.encodeToString(body)
            val chatReq = Request.Builder()
                .url(ensureSlash(settings.baseUrl) + "chat/completions")
                .header("Authorization", "Bearer ${settings.apiKey}")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val start = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                timedClient.newCall(chatReq).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@use TestResult(false, "HTTP ${resp.code}: ${raw.take(200)}")
                    }
                    val latency = System.currentTimeMillis() - start
                    TestResult(
                        true,
                        appContext.getString(
                            R.string.settings_test_ok_openai_chat_format,
                            settings.model,
                            latency.toInt()
                        )
                    )
                }
            }
        }.getOrElse { e ->
            TestResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun validate(settings: Settings) {
        if (settings.apiKey.isBlank()) {
            throw TranslationException(appContext.getString(R.string.err_openai_no_api_key))
        }
    }

    /**
     * 划词翻译：用 [Settings.dictionaryPrompt] 让 LLM 返回 JSON。
     *
     * 流程：
     * 1) 替换 prompt 里 {source}/{target} 占位符
     * 2) temperature=0 + max_tokens 留够（默认 600）让 LLM 严肃输出 JSON
     * 3) 容错地从响应里抽 JSON（部分模型会包 ```json 代码块或多余前后缀），解析失败回退 null
     * 4) 解析成功但所有字段都空 → 也回 null，让调用方走纯 [translate]
     */
    override suspend fun translateWord(source: String, settings: Settings): WordResult? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        if (settings.apiKey.isBlank()) return null

        val targetDisplay = Languages.nameOf(appContext, settings.targetLang)
        val sourceDisplay = Languages.nameOf(appContext, settings.sourceLang)
        val systemPrompt = settings.dictionaryPrompt
            .replace("{source}", sourceDisplay)
            .replace("{source_lang}", sourceDisplay)
            .replace("{target}", targetDisplay)
            .replace("{target_lang}", targetDisplay)
            .withDifficultyNotesContract(targetDisplay)
            .withLexicalDetailsContract(sourceDisplay) + settings.runtimeTranslationContext

        val reqBody = ChatRequest(
            model = settings.model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = trimmed)
            ),
            temperature = 0.0,
            stream = false,
            maxTokens = DICTIONARY_MAX_TOKENS,
            responseFormat = dictionaryJsonResponseFormatOrNull(settings.baseUrl),
            reasoningEffort = RemoteThinkingPolicy.openAi(
                settings.baseUrl,
                settings.openAiRequestOptions.thinkingModeEnabled,
            ).reasoningEffort,
            thinking = RemoteThinkingPolicy.openAi(
                settings.baseUrl,
                settings.openAiRequestOptions.thinkingModeEnabled,
            ).thinking,
            enableThinking = RemoteThinkingPolicy.openAi(
                settings.baseUrl,
                settings.openAiRequestOptions.thinkingModeEnabled,
            ).enableThinking,
        )
        val payload = json.encodeToString(reqBody)
        val request = Request.Builder()
            .url(ensureSlash(settings.baseUrl) + "chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val requestId = UUID.randomUUID().toString().take(8)
        TranslationRequestAudit.log(
            requestId, "OPENAI", "dictionary", false, request,
        )
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)

        val raw = runCatching {
            withContext(Dispatchers.IO) {
                timedClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Timber.w("translateWord HTTP ${resp.code}: ${body.take(200)}")
                        return@use null
                    }
                    runCatching { json.decodeFromString<ChatResponse>(body) }
                        .getOrNull()
                        ?.choices?.firstOrNull()?.message?.content?.trim()
                }
            }
        }.getOrNull() ?: return null

        return parseWordResult(raw, json).also { result ->
            Timber.i(
                "translateWord parsed=%s rawLength=%d phonetic=%s pos=%d definitions=%d inflections=%d synonyms=%d notes=%d examples=%d",
                result != null,
                raw.length,
                result?.phonetic?.isNotBlank() == true,
                result?.pos?.size ?: 0,
                result?.definitions?.size ?: 0,
                result?.inflections?.size ?: 0,
                result?.synonyms?.size ?: 0,
                result?.difficultyNotes?.size ?: 0,
                result?.examples?.size ?: 0,
            )
        }
    }

    private fun resolveRequest(
        text: String,
        settings: Settings,
        runtimeContext: String = settings.runtimeTranslationContext,
        textAlreadyPrepared: Boolean = false,
    ): ResolvedOpenAiRequest {
        val targetDisplay = Languages.nameOf(appContext, settings.targetLang)
        val sourceDisplay = Languages.nameOf(appContext, settings.sourceLang)
        return OpenAiRequestPolicy.resolve(
            text = text,
            systemPromptTemplate = settings.promptTemplate,
            sourceDisplay = sourceDisplay,
            targetDisplay = targetDisplay,
            runtimeContext = runtimeContext,
            options = settings.openAiRequestOptions,
            networkRequestTimeoutSeconds = settings.apiTimeoutSeconds,
            textAlreadyPrepared = textAlreadyPrepared,
        )
    }

    private fun buildRequest(
        resolved: ResolvedOpenAiRequest,
        settings: Settings,
        stream: Boolean,
        responseFormat: ChatResponseFormat? = null,
    ): Request {
        val thinking = RemoteThinkingPolicy.openAi(
            settings.baseUrl,
            resolved.thinkingModeEnabled,
        )
        val body = ChatRequest(
            model = settings.model,
            messages = listOf(
                ChatMessage(role = "system", content = resolved.systemMessage),
                ChatMessage(role = "user", content = resolved.userMessage),
            ),
            temperature = resolved.temperature,
            topP = resolved.topP,
            stream = stream,
            maxTokens = resolved.maxTokens,
            responseFormat = responseFormat,
            reasoningEffort = thinking.reasoningEffort,
            thinking = thinking.thinking,
            enableThinking = thinking.enableThinking,
        )
        val payload = json.encodeToString(body)
        val url = ensureSlash(settings.baseUrl) + "chat/completions"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun ensureSlash(url: String): String = if (url.endsWith("/")) url else "$url/"

    private companion object {
        const val DICTIONARY_MAX_TOKENS = 800
        const val MAX_LOGGED_MALFORMED_STREAM_EVENTS = 3
    }
}

internal fun String.withDifficultyNotesContract(targetDisplay: String): String {
    if (contains("\"difficulty_notes\"")) return this
    val requirement = """
        Additional required JSON field:
        "difficulty_notes": an array written in $targetDisplay. For rare words, specialized terms, acronyms, culture-specific references, or easily confused usages, briefly explain the domain/context, full form, concept, or ambiguity. Use an empty array for ordinary terms. Include at most 3 items and do not repeat the definitions.
    """.trimIndent()
    return trimEnd() + "\n\n" + requirement
}

internal fun String.withLexicalDetailsContract(sourceDisplay: String): String {
    val missingFields = buildList {
        if (!this@withLexicalDetailsContract.contains("\"inflections\"")) {
            add(
                "\"inflections\": an array of at most 6 concise strings in $sourceDisplay, " +
                    "each formatted as \"form label: inflected form\". Include only applicable " +
                    "forms such as base form, past tense, past participle, plural, comparative, " +
                    "conjugation, or declension; use an empty array when none apply."
            )
        }
        if (!this@withLexicalDetailsContract.contains("\"synonyms\"")) {
            add(
                "\"synonyms\": an array of at most 5 common synonyms or near-synonyms in " +
                    "$sourceDisplay; use an empty array when none are reliable."
            )
        }
    }
    if (missingFields.isEmpty()) return this
    return trimEnd() + "\n\nAdditional required JSON fields:\n" +
        missingFields.joinToString(separator = "\n")
}

internal fun deepSeekJsonObjectResponseFormatOrNull(baseUrl: String): ChatResponseFormat? {
    val host = runCatching { URI(baseUrl.trim()).host }.getOrNull()
    return if (host.equals("api.deepseek.com", ignoreCase = true)) {
        ChatResponseFormat(type = "json_object")
    } else {
        null
    }
}

internal fun dictionaryJsonResponseFormatOrNull(baseUrl: String): ChatResponseFormat? =
    deepSeekJsonObjectResponseFormatOrNull(baseUrl)

internal fun parseWordResult(raw: String, json: Json): WordResult? {
    val jsonText = extractJsonObject(raw) ?: return null
    return runCatching {
        val root = json.parseToJsonElement(jsonText) as? JsonObject ?: return@runCatching null
        val obj = root.dictionaryPayload()
        WordResult(
            phonetic = obj.firstString("phonetic", "pronunciation", "ipa", "reading"),
            pos = obj.stringList(
                keys = listOf("pos", "part_of_speech", "partOfSpeech", "word_class", "wordClass"),
                objectValueKeys = listOf("pos", "type", "name", "label", "value", "text"),
            ),
            definitions = obj.stringList(
                keys = listOf("definitions", "definition", "meanings", "meaning", "translations"),
                objectValueKeys = listOf("definition", "meaning", "translation", "text", "value"),
            ),
            inflections = obj.stringList(
                keys = listOf(
                    "inflections",
                    "inflection",
                    "word_forms",
                    "wordForms",
                    "forms",
                    "conjugations",
                    "declensions",
                ),
                objectValueKeys = listOf("form", "inflection", "value", "text", "word", "label"),
            ),
            synonyms = obj.stringList(
                keys = listOf(
                    "synonyms",
                    "synonym",
                    "similar_words",
                    "similarWords",
                    "near_synonyms",
                    "nearSynonyms",
                ),
                objectValueKeys = listOf("word", "synonym", "term", "value", "text", "label"),
            ),
            difficultyNotes = obj.stringList(
                keys = listOf(
                    "difficulty_notes",
                    "difficultyNotes",
                    "usage_notes",
                    "usageNotes",
                    "notes",
                ),
                objectValueKeys = listOf("note", "description", "text", "value"),
            ),
            examples = obj.examplePairs(),
            fallbackTranslation = obj.firstString(
                "fallback_translation",
                "fallbackTranslation",
                "translation",
            ).takeIf(String::isNotBlank),
        ).takeUnless(WordResult::isEmpty)
    }.getOrNull()
}

private fun JsonObject.dictionaryPayload(): JsonObject {
    val directKeys = setOf(
        "phonetic",
        "pronunciation",
        "ipa",
        "pos",
        "part_of_speech",
        "partOfSpeech",
        "definitions",
        "definition",
        "meanings",
        "meaning",
        "inflections",
        "inflection",
        "word_forms",
        "wordForms",
        "forms",
        "synonyms",
        "synonym",
        "similar_words",
        "similarWords",
    )
    if (keys.any { it in directKeys }) return this
    return listOf("data", "result", "word", "entry")
        .firstNotNullOfOrNull { key -> this[key] as? JsonObject }
        ?: this
}

private fun JsonObject.firstString(vararg keys: String): String = keys
    .firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }
    .orEmpty()

private fun JsonObject.stringList(
    keys: List<String>,
    objectValueKeys: List<String>,
): List<String> {
    val value = keys.firstNotNullOfOrNull { key -> this[key] } ?: return emptyList()
    return value.stringValues(objectValueKeys)
}

private fun JsonElement.stringValues(objectValueKeys: List<String>): List<String> = when (this) {
    is JsonPrimitive -> listOfNotNull(contentOrNull?.takeIf(String::isNotBlank))
    is JsonArray -> mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.takeIf(String::isNotBlank)
            is JsonObject -> objectValueKeys.firstNotNullOfOrNull { key ->
                (element[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            }
            else -> null
        }
    }
    is JsonObject -> objectValueKeys.mapNotNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }.take(1)
    else -> emptyList()
}

private fun JsonObject.examplePairs(): List<ExamplePair> {
    val value = this["examples"] ?: this["example"] ?: return emptyList()
    val items = if (value is JsonArray) value else JsonArray(listOf(value))
    return items.mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?.let { ExamplePair(src = it, dst = "") }
            is JsonObject -> {
                val src = element.firstString("src", "source", "original", "example")
                val dst = element.firstString("dst", "target", "translation", "translated")
                if (src.isBlank() && dst.isBlank()) null else ExamplePair(src, dst)
            }
            else -> null
        }
    }
}

/** Extracts the first complete JSON object from optional prose or a fenced response. */
private fun extractJsonObject(raw: String): String? {
    if (raw.isBlank()) return null
    val stripped = raw
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```")
        .trim()
    val start = stripped.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var index = start
    var inString = false
    var escaped = false
    while (index < stripped.length) {
        val char = stripped[index]
        if (escaped) {
            escaped = false
            index++
            continue
        }
        if (char == '\\' && inString) {
            escaped = true
            index++
            continue
        }
        if (char == '"') {
            inString = !inString
            index++
            continue
        }
        if (!inString) {
            if (char == '{') depth++
            if (char == '}') {
                depth--
                if (depth == 0) return stripped.substring(start, index + 1)
            }
        }
        index++
    }
    return null
}
