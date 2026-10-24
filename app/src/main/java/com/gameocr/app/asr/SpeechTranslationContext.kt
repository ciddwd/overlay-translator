package com.gameocr.app.asr

import com.gameocr.app.data.RuntimeDialogueTurn
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.glossary.supportsTranslationPromptContext
import com.gameocr.app.translate.DialogueContextFrame
import com.gameocr.app.translate.DialogueContextItem
import com.gameocr.app.translate.DialogueHistorySession
import com.gameocr.app.translate.DialogueTranslationContextPolicy

/**
 * Recent finished speech segments, used as read-only context for the next translation.
 *
 * Segments are keyed by record id, which grows in speech order. Two translations can run at once,
 * so a segment may start before the one before it has a translation; that turn is then given as
 * source only. A change of [DialogueTranslationContextPolicy.contextKey] (engine, languages, model)
 * drops history written for the old settings.
 *
 * Not thread-safe; [SpeechSubtitleCoordinator] serializes access.
 */
internal class SpeechTranslationHistory(
    private val maxTurns: Int = DEFAULT_MAX_TURNS,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private var key: String? = null
    private val turns = LinkedHashMap<Long, RuntimeDialogueTurn>()

    fun add(recordId: Long, source: String) {
        val text = source.trim()
        if (text.isEmpty()) return
        turns[recordId] = RuntimeDialogueTurn(text)
        while (turns.size > capacity) turns.remove(turns.keys.first())
    }

    /** Keeps only a usable translation, matching the OCR dialogue history. */
    fun complete(recordId: Long, translation: String) {
        val turn = turns[recordId] ?: return
        val text = translation.trim().takeIf { it.isNotEmpty() && it != turn.source } ?: return
        turns[recordId] = turn.copy(translation = text)
    }

    /** Up to [maxTurns] segments spoken before [recordId], oldest first. */
    fun before(recordId: Long, contextKey: String): List<RuntimeDialogueTurn> {
        if (key != contextKey) {
            // The first request adopts the key; only a real change makes old turns stale.
            if (key != null) turns.keys.removeAll { it < recordId }
            key = contextKey
        }
        return turns.filterKeys { it < recordId }.values.toList().takeLast(maxTurns)
    }

    fun clear() {
        key = null
        turns.clear()
    }

    companion object {
        /** A few sentences cover pronouns, names and topic without much extra prompt. */
        const val DEFAULT_MAX_TURNS = 4
        /** Leaves room for queued segments behind the ones still translating. */
        const val DEFAULT_CAPACITY = 32
    }
}

/**
 * Feeds earlier speech segments to translators that accept dialogue context. Follows the user's
 * context mode: Fast keeps each segment independent, Screen and Continuous both use the previous
 * segments, because speech has no "screen" of its own.
 */
internal object SpeechTranslationContextPolicy {
    /** Required by [DialogueTranslationContextPolicy.contextualize]; never read when history is passed. */
    private val unusedSession = DialogueHistorySession()

    fun enabled(settings: Settings): Boolean =
        settings.translationContextMode != TranslationContextMode.FAST_PER_SEGMENT &&
            supportsTranslationPromptContext(settings.translatorEngine)

    fun contextualize(settings: Settings, source: String, history: List<RuntimeDialogueTurn>): Settings {
        if (!enabled(settings) || history.isEmpty()) return settings
        val continuous = settings.copy(translationContextMode = TranslationContextMode.CONTINUOUS_CONTEXT)
        return DialogueTranslationContextPolicy.contextualize(
            settings = continuous,
            currentSources = listOf(source),
            historySession = unusedSession,
            availableHistory = DialogueContextFrame(
                history.mapIndexed { index, turn -> DialogueContextItem(index + 1, turn.source, turn.translation) },
            ),
        )
    }
}
