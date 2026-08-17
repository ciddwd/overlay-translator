package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
) {
    suspend fun execute(
        word: String,
        settings: Settings,
    ): FloatingWordLookupOutcome {
        val isolated = settings.forFloatingEnglishWordLookup()
        var dictionaryError: Throwable? = null
        val wordResult = try {
            withContext(Dispatchers.IO) {
                translator.translateWord(word, isolated)
            }?.takeIf(WordResult::hasFloatingWordDetails)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            dictionaryError = error
            null
        }

        val dictionaryTranslation = wordResult?.definitions
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
}

private fun WordResult.hasFloatingWordDetails(): Boolean =
    !isEmpty() || !fallbackTranslation.isNullOrBlank()
