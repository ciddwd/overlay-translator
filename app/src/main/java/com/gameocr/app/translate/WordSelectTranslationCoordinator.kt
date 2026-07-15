package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

internal data class WordSelectTranslationOutcome(
    val translation: String?,
    val wordResult: WordResult?,
    val translationError: Throwable?,
    val dictionaryError: Throwable?,
    val chunkCount: Int,
    val firstChunkLatencyMs: Long?,
)

internal enum class WordSelectTranslationStage {
    PRIMARY_STARTED,
    FIRST_PARTIAL_VISIBLE,
    PRIMARY_FINISHED,
    DICTIONARY_STARTED,
    DICTIONARY_FINISHED,
}

/** Tries a structured dictionary result first, then falls back to primary translation. */
internal class WordSelectTranslationCoordinator(
    private val translator: Translator,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        source: String,
        settings: Settings,
        dictionaryTerm: String?,
        onPartialTranslation: suspend (String) -> Unit,
        onWordResult: suspend (WordResult) -> Unit,
        onStage: (WordSelectTranslationStage) -> Unit = {},
    ): WordSelectTranslationOutcome {
        val startedAt = nowMs()
        var dictionaryError: Throwable? = null
        val wordResult = dictionaryTerm?.let { term ->
            onStage(WordSelectTranslationStage.DICTIONARY_STARTED)
            try {
                withContext(Dispatchers.IO) {
                    translator.translateWord(term, settings)
                }?.takeUnless(WordResult::isEmpty)?.also { onWordResult(it) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                dictionaryError = error
                null
            } finally {
                onStage(WordSelectTranslationStage.DICTIONARY_FINISHED)
            }
        }

        if (wordResult != null) {
            return WordSelectTranslationOutcome(
                translation = null,
                wordResult = wordResult,
                translationError = null,
                dictionaryError = dictionaryError,
                chunkCount = 0,
                firstChunkLatencyMs = null,
            )
        }

        var translation: String? = null
        var translationError: Throwable? = null
        var chunkCount = 0
        var firstChunkLatencyMs: Long? = null
        onStage(WordSelectTranslationStage.PRIMARY_STARTED)
        try {
            if (settings.streamingTranslate) {
                translator.translateStream(source, settings).collect { partial ->
                    val isFirst = firstChunkLatencyMs == null
                    if (isFirst) firstChunkLatencyMs = nowMs() - startedAt
                    chunkCount += 1
                    translation = partial
                    onPartialTranslation(partial)
                    if (isFirst) {
                        onStage(WordSelectTranslationStage.FIRST_PARTIAL_VISIBLE)
                    }
                }
            } else {
                translation = translator.translate(source, settings)
                translation?.let {
                    chunkCount = 1
                    firstChunkLatencyMs = nowMs() - startedAt
                    onPartialTranslation(it)
                    onStage(WordSelectTranslationStage.FIRST_PARTIAL_VISIBLE)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            translationError = error
        } finally {
            onStage(WordSelectTranslationStage.PRIMARY_FINISHED)
        }

        return WordSelectTranslationOutcome(
            translation = translation,
            wordResult = null,
            translationError = translationError,
            dictionaryError = dictionaryError,
            chunkCount = chunkCount,
            firstChunkLatencyMs = firstChunkLatencyMs,
        )
    }
}
