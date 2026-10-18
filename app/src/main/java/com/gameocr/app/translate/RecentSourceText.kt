package com.gameocr.app.translate

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last real source text this process translated, so a model trial can use what the user is
 * actually reading. Memory only: it is never written to disk and disappears with the process.
 */
@Singleton
class RecentSourceText @Inject constructor() {
    @Volatile
    var latest: String? = null
        private set

    fun record(sources: List<String>) {
        sources.map(String::trim).filter(String::isNotEmpty).joinToString("\n")
            .take(MAX_CHARS).takeIf(String::isNotEmpty)?.let { latest = it }
    }

    private companion object {
        /** A trial sentence, not a whole page; long pages would also be slow to retranslate. */
        const val MAX_CHARS = 300
    }
}
