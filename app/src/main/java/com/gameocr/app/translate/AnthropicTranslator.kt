package com.gameocr.app.translate

import android.content.Context
import com.gameocr.app.R
import com.gameocr.app.data.Languages
import com.gameocr.app.data.Settings
import com.gameocr.app.data.withApiTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import timber.log.Timber

/** Anthropic Messages API compatible translator. */
@Singleton
class AnthropicTranslator @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val client: OkHttpClient,
    private val json: Json,
    private val cache: TranslationCache,
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
        val capabilityKey = RemoteStructuredOutputCapability.anthropicKey(settings)
        if (!RemoteStructuredOutputCapability.tracker.shouldAttemptStructured(capabilityKey)) {
            Timber.w(
                "Anthropic structuredBatch bypassed count=%d model=%s reason=capability_cooldown",
                sources.size,
                settings.anthropicModel,
            )
            return translateStructuredFallbackIndividually(
                sources = sources,
                settings = settings,
                onUpdate = onUpdate,
                translateOne = ::translate,
            )
        }
        Timber.i(
            "Anthropic structuredBatch started count=%d model=%s",
            sources.size,
            settings.anthropicModel,
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
                        "Anthropic structuredBatch parsed ids=%s candidates=%d accepted=%d complete=%s unresolved=%s duplicates=%s unknown=%s structured=%s",
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
                "Anthropic structuredBatch fallback count=%d model=%s reason=transport_failure",
                sources.size,
                settings.anthropicModel,
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
            "Anthropic structuredBatch fallback count=%d model=%s reason=no_accepted_results",
            sources.size,
            settings.anthropicModel,
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
        val stream = settings.streamingTranslate
        val request = buildAnthropicMessageRequest(
            settings = settings,
            systemPrompt = resolvedRequest.systemMessage,
            userText = resolvedRequest.userMessage,
            maxTokens = resolvedRequest.maxTokens ?: TRANSLATION_MAX_TOKENS,
            temperature = resolvedRequest.temperature,
            stream = stream,
            json = json,
            topP = resolvedRequest.topP,
            thinking = RemoteThinkingPolicy.anthropic(resolvedRequest.thinkingModeEnabled),
        )
        val requestId = UUID.randomUUID().toString().take(8)
        val startedAt = System.currentTimeMillis()
        TranslationRequestAudit.log(
            requestId, "ANTHROPIC", "translation_batch", stream, request,
        )
        Timber.i(
            "Anthropic request=%s started kind=translation_batch ids=%s model=%s",
            requestId,
            attempt.activeIds,
            settings.anthropicModel,
        )
        return try {
            withContext(Dispatchers.IO) {
                client.withApiTimeout(resolvedRequest.timeoutSeconds).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        throw TranslationException("HTTP ${response.code}: ${anthropicErrorDetail(raw, json)}")
                    }
                    if (stream) {
                        readStructuredAnthropicStream(response, requestId, startedAt)
                    } else {
                        val raw = response.body?.string().orEmpty()
                        parseAnthropicResponseText(raw, json)
                            ?: throw TranslationException(appContext.getString(R.string.err_anthropic_no_text))
                    }
                }
            }.also { translated ->
                TranslationRequestAudit.logStructuredResponse(
                    requestId = requestId,
                    engine = "ANTHROPIC",
                    body = translated,
                )
                Timber.i(
                    "Anthropic request=%s completed kind=translation_batch elapsedMs=%d outputChars=%d",
                    requestId,
                    System.currentTimeMillis() - startedAt,
                    translated.length,
                )
            }
        } catch (error: CancellationException) {
            Timber.i(
                "Anthropic request=%s cancelled kind=translation_batch elapsedMs=%d",
                requestId,
                System.currentTimeMillis() - startedAt,
            )
            throw error
        } catch (error: Throwable) {
            Timber.w(
                error,
                "Anthropic request=%s failed kind=translation_batch elapsedMs=%d",
                requestId,
                System.currentTimeMillis() - startedAt,
            )
            throw error
        }
    }

    private fun shouldUseStructuredBatch(settings: Settings): Boolean =
        settings.runtimeTranslationPromptContext.currentPage.isNotEmpty()

    private fun readStructuredAnthropicStream(
        response: okhttp3.Response,
        requestId: String,
        startedAt: Long,
    ): String {
        val body = response.body
            ?: throw TranslationException(appContext.getString(R.string.err_anthropic_empty_body))
        val accumulated = StringBuilder()
        var firstTokenLogged = false
        var lineCount = 0
        var keepAliveCount = 0
        var dataEventCount = 0
        var contentEventCount = 0
        var malformedEventCount = 0
        var endReason = "eof"
        try {
            body.source().use { source ->
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    lineCount += 1
                    if (line.startsWith(':')) {
                        keepAliveCount += 1
                        continue
                    }
                    if (!line.startsWith("data:")) continue
                    dataEventCount += 1
                    val payload = line.substring(5).trim()
                    when (val event = parseAnthropicStreamEvent(payload, json)) {
                        is AnthropicStreamEvent.Text -> {
                            contentEventCount += 1
                            if (!firstTokenLogged) {
                                firstTokenLogged = true
                                Timber.i(
                                    "Anthropic request=%s firstTokenMs=%d kind=translation_batch",
                                    requestId,
                                    System.currentTimeMillis() - startedAt,
                                )
                            }
                            accumulated.append(event.value)
                        }
                        is AnthropicStreamEvent.Error -> {
                            endReason = "server_error"
                            throw TranslationException("Anthropic stream error: ${event.detail}")
                        }
                        is AnthropicStreamEvent.Malformed -> {
                            malformedEventCount += 1
                            if (malformedEventCount <= MAX_LOGGED_MALFORMED_STREAM_EVENTS) {
                                TranslationRequestAudit.logMalformedStreamEvent(
                                    requestId = requestId,
                                    engine = "ANTHROPIC",
                                    eventIndex = malformedEventCount,
                                    payload = event.payload,
                                )
                            }
                        }
                        AnthropicStreamEvent.Stop -> {
                            endReason = "done"
                            break
                        }
                        AnthropicStreamEvent.Ignore -> Unit
                    }
                }
            }
        } catch (error: Throwable) {
            if (endReason != "server_error") {
                endReason = "error:${error.javaClass.simpleName}"
            }
            throw error
        } finally {
            Timber.i(
                "Anthropic request=%s streamSummary kind=translation_batch elapsedMs=%d end=%s " +
                    "lines=%d keepAlive=%d dataEvents=%d contentEvents=%d malformed=%d outputChars=%d",
                requestId,
                System.currentTimeMillis() - startedAt,
                endReason,
                lineCount,
                keepAliveCount,
                dataEventCount,
                contentEventCount,
                malformedEventCount,
                accumulated.length,
            )
        }
        return accumulated.toString().trim()
            .takeIf(String::isNotEmpty)
            ?: throw TranslationException(appContext.getString(R.string.err_anthropic_no_text))
    }

    override suspend fun translate(source: String, settings: Settings): String? {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return null
        validate(settings)

        val resolvedRequest = resolveRequest(trimmed, settings)
        val cacheKey = cache.key(
            trimmed,
            "anthropic:${settings.anthropicModel}",
            settings.targetLang,
            resolvedRequest.cacheFingerprint,
        )
        cache.get(cacheKey, settings)?.let { return it }
        val request = buildAnthropicMessageRequest(
            settings = settings,
            systemPrompt = resolvedRequest.systemMessage,
            userText = resolvedRequest.userMessage,
            maxTokens = resolvedRequest.maxTokens ?: TRANSLATION_MAX_TOKENS,
            temperature = resolvedRequest.temperature,
            stream = false,
            json = json,
            topP = resolvedRequest.topP,
            thinking = RemoteThinkingPolicy.anthropic(resolvedRequest.thinkingModeEnabled),
        )
        val requestId = UUID.randomUUID().toString().take(8)
        TranslationRequestAudit.log(
            requestId, "ANTHROPIC", "translation", false, request,
        )
        val translated = withContext(Dispatchers.IO) {
            client.withApiTimeout(resolvedRequest.timeoutSeconds).newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw TranslationException("HTTP ${response.code}: ${anthropicErrorDetail(raw, json)}")
                }
                parseAnthropicResponseText(raw, json)
                    ?: throw TranslationException(appContext.getString(R.string.err_anthropic_no_text))
            }
        }
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
            "anthropic:${settings.anthropicModel}",
            settings.targetLang,
            resolvedRequest.cacheFingerprint,
        )
        cache.get(cacheKey, settings)?.let {
            emit(it)
            return@flow
        }
        val request = buildAnthropicMessageRequest(
            settings = settings,
            systemPrompt = resolvedRequest.systemMessage,
            userText = resolvedRequest.userMessage,
            maxTokens = resolvedRequest.maxTokens ?: TRANSLATION_MAX_TOKENS,
            temperature = resolvedRequest.temperature,
            stream = true,
            json = json,
            topP = resolvedRequest.topP,
            thinking = RemoteThinkingPolicy.anthropic(resolvedRequest.thinkingModeEnabled),
        )
        val requestId = UUID.randomUUID().toString().take(8)
        TranslationRequestAudit.log(
            requestId, "ANTHROPIC", "translation", true, request,
        )
        val response = client.withApiTimeout(resolvedRequest.timeoutSeconds)
            .newCall(request)
            .execute()
        if (!response.isSuccessful) {
            val raw = response.body?.string().orEmpty()
            response.close()
            throw TranslationException("HTTP ${response.code}: ${anthropicErrorDetail(raw, json)}")
        }
        val body = response.body ?: run {
            response.close()
            throw TranslationException(appContext.getString(R.string.err_anthropic_empty_body))
        }

        val accumulated = StringBuilder()
        var malformedEventCount = 0
        try {
            body.source().use { sourceBuffer ->
                while (!sourceBuffer.exhausted()) {
                    val line = sourceBuffer.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    when (val event = parseAnthropicStreamEvent(payload, json)) {
                        is AnthropicStreamEvent.Text -> {
                            accumulated.append(event.value)
                            emit(accumulated.toString())
                        }
                        is AnthropicStreamEvent.Error ->
                            throw TranslationException("Anthropic stream error: ${event.detail}")
                        is AnthropicStreamEvent.Malformed -> {
                            malformedEventCount += 1
                            if (malformedEventCount <= MAX_LOGGED_MALFORMED_STREAM_EVENTS) {
                                TranslationRequestAudit.logMalformedStreamEvent(
                                    requestId = requestId,
                                    engine = "ANTHROPIC",
                                    eventIndex = malformedEventCount,
                                    payload = event.payload,
                                )
                            }
                        }
                        AnthropicStreamEvent.Stop -> break
                        AnthropicStreamEvent.Ignore -> Unit
                    }
                }
            }
        } finally {
            response.close()
        }
        if (accumulated.isNotEmpty()) {
            cache.put(cacheKey, accumulated.toString(), settings)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(settings: Settings): TestResult {
        connectionValidationMessage(settings)?.let { return TestResult(false, it) }
        val timedClient = client.withApiTimeout(settings.apiTimeoutSeconds)
        val models = runCatching {
            withContext(Dispatchers.IO) {
                timedClient.newCall(buildAnthropicModelsRequest(settings)).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    parseAnthropicModelIds(response.body?.string().orEmpty(), json)
                }
            }
        }.getOrDefault(emptyList())
        if (models.isNotEmpty()) {
            return TestResult(
                success = true,
                message = appContext.getString(
                    R.string.settings_test_ok_anthropic_models_format,
                    models.size,
                ),
                models = models,
            )
        }
        if (settings.anthropicModel.isBlank()) {
            return TestResult(false, appContext.getString(R.string.err_anthropic_no_model))
        }

        val request = buildAnthropicMessageRequest(
            settings = settings,
            systemPrompt = "Connectivity check.",
            userText = "Reply OK",
            maxTokens = 1,
            temperature = 0.0,
            stream = false,
            json = json,
            thinking = RemoteThinkingPolicy.anthropic(false),
        )
        return runCatching {
            val startedAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                timedClient.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use TestResult(
                            false,
                            "HTTP ${response.code}: ${anthropicErrorDetail(raw, json)}",
                        )
                    }
                    val latency = System.currentTimeMillis() - startedAt
                    TestResult(
                        true,
                        appContext.getString(
                            R.string.settings_test_ok_anthropic_message_format,
                            settings.anthropicModel,
                            latency.toInt(),
                        ),
                    )
                }
            }
        }.getOrElse { error ->
            TestResult(false, error.message ?: error.javaClass.simpleName)
        }
    }

    override suspend fun translateWord(source: String, settings: Settings): WordResult? {
        val trimmed = source.trim()
        if (trimmed.isEmpty() || validationMessage(settings) != null) return null

        val targetDisplay = Languages.nameOf(appContext, settings.targetLang)
        val sourceDisplay = Languages.nameOf(appContext, settings.sourceLang)
        val systemPrompt = settings.dictionaryPrompt
            .replace("{source}", sourceDisplay)
            .replace("{source_lang}", sourceDisplay)
            .replace("{target}", targetDisplay)
            .replace("{target_lang}", targetDisplay)
            .withDifficultyNotesContract(targetDisplay)
            .withLexicalDetailsContract(sourceDisplay) + settings.runtimeTranslationContext
        val request = buildAnthropicMessageRequest(
            settings = settings,
            systemPrompt = systemPrompt,
            userText = trimmed,
            maxTokens = DICTIONARY_MAX_TOKENS,
            temperature = 0.0,
            stream = false,
            json = json,
            thinking = RemoteThinkingPolicy.anthropic(
                settings.openAiRequestOptions.thinkingModeEnabled,
            ),
        )
        val requestId = UUID.randomUUID().toString().take(8)
        TranslationRequestAudit.log(
            requestId, "ANTHROPIC", "dictionary", false, request,
        )
        val raw = runCatching {
            withContext(Dispatchers.IO) {
                client.withApiTimeout(settings.apiTimeoutSeconds).newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Timber.w("Anthropic translateWord HTTP %d: %s", response.code, body.take(200))
                        return@use null
                    }
                    parseAnthropicResponseText(body, json)
                }
            }
        }.getOrNull() ?: return null
        return parseWordResult(raw, json)
    }

    private fun validate(settings: Settings) {
        validationMessage(settings)?.let { throw TranslationException(it) }
    }

    private fun validationMessage(settings: Settings): String? = when {
        settings.anthropicBaseUrl.isBlank() -> appContext.getString(R.string.err_anthropic_no_base_url)
        settings.anthropicApiKey.isBlank() -> appContext.getString(R.string.err_anthropic_no_api_key)
        settings.anthropicModel.isBlank() -> appContext.getString(R.string.err_anthropic_no_model)
        else -> null
    }

    private fun connectionValidationMessage(settings: Settings): String? = when {
        settings.anthropicBaseUrl.isBlank() -> appContext.getString(R.string.err_anthropic_no_base_url)
        settings.anthropicApiKey.isBlank() -> appContext.getString(R.string.err_anthropic_no_api_key)
        else -> null
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

    private companion object {
        const val TRANSLATION_MAX_TOKENS = 4096
        const val DICTIONARY_MAX_TOKENS = 800
        const val MAX_LOGGED_MALFORMED_STREAM_EVENTS = 3
    }
}
