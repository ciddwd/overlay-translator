package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.DictionaryLookupMode
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.dictionary.EmptyOfflineWordDictionary
import com.gameocr.app.dictionary.OfflineWordDictionary
import com.gameocr.app.dictionary.supportsOnlineDictionaryLookup
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
    private val offlineDictionary: OfflineWordDictionary = EmptyOfflineWordDictionary,
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
    ): FloatingWordLookupOutcome = execute(word, settings, compact = true)

    suspend fun executeFull(
        word: String,
        settings: Settings,
    ): FloatingWordLookupOutcome = execute(word, settings, compact = false)

    private suspend fun execute(
        word: String,
        settings: Settings,
        compact: Boolean,
    ): FloatingWordLookupOutcome {
        val isolated = settings.forFloatingEnglishWordLookup()
        if (isolated.dictionaryLookupMode == DictionaryLookupMode.OFFLINE) {
            return executeOffline(word, isolated)
        }
        if (!supportsOnlineDictionaryLookup(isolated.translatorEngine)) {
            return FloatingWordLookupOutcome(
                word = word,
                translation = null,
                wordResult = null,
                error = OnlineDictionaryUnavailableException(isolated.translatorEngine.name),
            )
        }

        val key = lookupKey(word, isolated, compact)
        val request = mutex.withLock {
            cache[key]
                ?.takeIf { nowMs() - it.storedAtMs <= CACHE_TTL_MS }
                ?.outcome
                ?.let { return it }
            inFlight[key] ?: lookupScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    executeOnlineUncached(word, isolated, compact).also { outcome ->
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

    private suspend fun executeOffline(
        word: String,
        isolated: Settings,
    ): FloatingWordLookupOutcome = try {
        val result = withContext(Dispatchers.IO) {
            offlineDictionary.lookup(word, isolated.sourceLang)
        }?.takeIf(WordResult::hasFloatingWordDetails)
        FloatingWordLookupOutcome(
            word = word,
            translation = result.compactTranslation(),
            wordResult = result,
            error = null,
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        FloatingWordLookupOutcome(
            word = word,
            translation = null,
            wordResult = null,
            error = error,
        )
    }

    private suspend fun executeOnlineUncached(
        word: String,
        isolated: Settings,
        compact: Boolean,
    ): FloatingWordLookupOutcome {
        val wordResult = try {
            withContext(Dispatchers.IO) {
                if (compact) {
                    translator.translateWordCompact(word, isolated)
                } else {
                    translator.translateWord(word, isolated)
                }
            }?.takeIf(WordResult::hasFloatingWordDetails)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return FloatingWordLookupOutcome(
                word = word,
                translation = null,
                wordResult = null,
                error = error,
            )
        }
        return FloatingWordLookupOutcome(
            word = word,
            translation = wordResult.compactTranslation(),
            wordResult = wordResult,
            error = null,
        )
    }

    private fun lookupKey(word: String, settings: Settings, compact: Boolean): String = listOf(
        CACHE_PROMPT_VERSION,
        if (compact) "compact" else "full",
        word.trim().lowercase(),
        settings.dictionaryLookupMode.name,
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

internal class OnlineDictionaryUnavailableException(engine: String) :
    IllegalStateException("Online dictionary lookup is unavailable for $engine")

private fun WordResult?.compactTranslation(): String? = this?.effectiveDefinitions()
    ?.asSequence()
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.distinct()
    ?.joinToString("、")
    ?.takeIf(String::isNotBlank)
    ?: this?.fallbackTranslation?.takeIf(String::isNotBlank)

private fun WordResult.hasFloatingWordDetails(): Boolean =
    !isEmpty() || !fallbackTranslation.isNullOrBlank()
