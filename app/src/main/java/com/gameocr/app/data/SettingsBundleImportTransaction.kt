package com.gameocr.app.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Commit order for a settings bundle: fonts, then glossary, then settings, then font finalization.
 * A failure undoes only the steps that already completed, newest first. Undo runs even when the
 * import was cancelled, so a half-applied bundle never remains.
 */
internal class SettingsBundleImportTransaction<S, C, P, R>(
    private val commitFont: suspend (S) -> C,
    private val prepareSettings: suspend (fonts: List<C>) -> P,
    private val importGlossary: suspend () -> Int,
    private val commitSettings: suspend (P) -> R,
    private val finishFont: (C) -> Unit,
    private val restoreSettings: suspend () -> Unit,
    private val restoreGlossary: suspend () -> Unit,
    private val rollbackFont: (C) -> Unit,
) {
    data class Outcome<C, P, R>(val fonts: List<C>, val prepared: P, val glossaryCount: Int, val settings: R)

    suspend fun run(staged: List<S>): Outcome<C, P, R> {
        val commits = mutableListOf<C>()
        var glossaryCommitted = false
        var settingsCommitted = false
        try {
            staged.forEach { commits += commitFont(it) }
            val prepared = prepareSettings(commits.toList())
            val glossaryCount = importGlossary()
            glossaryCommitted = true
            val settings = commitSettings(prepared)
            settingsCommitted = true
            commits.forEach(finishFont)
            return Outcome(commits.toList(), prepared, glossaryCount, settings)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                if (settingsCommitted) runCatching { restoreSettings() }.exceptionOrNull()?.let(error::addSuppressed)
                if (glossaryCommitted) runCatching { restoreGlossary() }.exceptionOrNull()?.let(error::addSuppressed)
                commits.asReversed().forEach { commit ->
                    runCatching { rollbackFont(commit) }.exceptionOrNull()?.let(error::addSuppressed)
                }
            }
            throw error
        }
    }
}
