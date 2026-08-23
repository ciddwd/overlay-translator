package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class FloatingWordLookupOutcome(
    val word: String,
    val translation: String?,
    val wordResult: WordResult?,
    val error: Throwable?,
) {
    val hasDetails: Boolean
        get() = !translation.isNullOrBlank() || wordResult?.hasFloatingWordDetails() == true
}

internal fun Settings.forFloatingEnglishWordLookup(): Settings = copy(
    sourceLang = "en",
    translationContextMode = TranslationContextMode.FAST_PER_SEGMENT,
    runtimeTranslationContext = "",
    runtimeTranslationPromptContext = RuntimeTranslationPromptContext(),
    runtimeTranslationVisualContext = null,
)

/** Looks up one tapped English word without inheriting page or previous-frame context. */
internal class FloatingWordLookupCoordinator(
    private val translator: Translator,
    private val lookupScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class CacheEntry(val outcome: FloatingWordLookupOutcome, val storedAtMs: Long)

    private val mutex = Mutex()
    private val inFlight = mutableMapOf<String, kotlinx.coroutines.Deferred<FloatingWordLookupOutcome>>()
    private val cache = object : LinkedHashMap<String, CacheEntry>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CacheEntry>?,
        ): Boolean = size > MAX_CACHE_ENTRIES
    }

    suspend fun execute(
        word: String,
        settings: Settings,
    ): FloatingWordLookupOutcome {
        val isolated = settings.forFloatingEnglishWordLookup()
        val key = lookupKey(word, isolated)
        val request = mutex.withLock {
            cache[key]
                ?.takeIf { nowMs() - it.storedAtMs <= CACHE_TTL_MS }
                ?.outcome
                ?.let { return it }
            inFlight[key] ?: lookupScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    executeUncached(word, isolated).also { outcome ->
                        if (outcome.hasDetails) {
                            mutex.withLock { cache[key] = CacheEntry(outcome, nowMs()) }
                        }
                    }
                } finally {
                    mutex.withLock { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        request.start()
        return request.await()
    }

    private suspend fun executeUncached(
        word: String,
        isolated: Settings,
    ): FloatingWordLookupOutcome {
        var dictionaryError: Throwable? = null
        val wordResult = try {
            withContext(Dispatchers.IO) {
                translator.translateWordCompact(word, isolated)
            }?.takeIf(WordResult::hasFloatingWordDetails)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            dictionaryError = error
            null
        }

        val dictionaryTranslation = wordResult?.effectiveDefinitions()
            ?.asSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            ?.joinToString("、")
            ?.takeIf(String::isNotBlank)
            ?: wordResult?.fallbackTranslation?.takeIf(String::isNotBlank)
        if (dictionaryTranslation != null) {
            return FloatingWordLookupOutcome(
                word = word,
                translation = dictionaryTranslation,
                wordResult = wordResult,
                error = dictionaryError,
            )
        }

        return try {
            val translation = withContext(Dispatchers.IO) {
                translator.translate(word, isolated)
            }?.takeIf { it.isNotBlank() }
            FloatingWordLookupOutcome(
                word = word,
                translation = translation,
                wordResult = wordResult,
                error = if (translation == null) dictionaryError else null,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            FloatingWordLookupOutcome(
                word = word,
                translation = null,
                wordResult = wordResult,
                error = error,
            )
        }
    }

    private fun lookupKey(word: String, settings: Settings): String = listOf(
        CACHE_PROMPT_VERSION,
        word.trim().lowercase(),
        settings.translatorEngine.name,
        settings.targetLang,
        settings.baseUrl.trimEnd('/'),
        settings.model,
        settings.anthropicBaseUrl.trimEnd('/'),
        settings.anthropicModel,
        settings.openAiRequestOptions.hashCode().toString(),
    ).joinToString("|")

    private companion object {
        const val MAX_CACHE_ENTRIES = 24
        const val CACHE_TTL_MS = 10 * 60 * 1000L
        const val CACHE_PROMPT_VERSION = "compact-dictionary-v2"
    }
}

private fun WordResult.hasFloatingWordDetails(): Boolean =
    !isEmpty() || !fallbackTranslation.isNullOrBlank()
