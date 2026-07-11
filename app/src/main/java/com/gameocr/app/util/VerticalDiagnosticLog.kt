package com.gameocr.app.util

import timber.log.Timber

internal object VerticalDiagnosticLog {
    const val TAG: String = "VerticalDiag"
    private const val MAX_CHUNK_CHARS: Int = 3000

    fun i(message: String) {
        val parts = chunks(message)
        parts.forEachIndexed { index, chunk ->
            if (parts.size == 1) {
                Timber.tag(TAG).i("%s", chunk)
            } else {
                Timber.tag(TAG).i("[%d/%d] %s", index + 1, parts.size, chunk)
            }
        }
    }

    fun w(t: Throwable, message: String) {
        chunks(message).forEachIndexed { index, chunk ->
            if (index == 0) {
                Timber.tag(TAG).w(t, "%s", chunk)
            } else {
                Timber.tag(TAG).w("%s", chunk)
            }
        }
    }

    fun w(message: String) {
        chunks(message).forEach { chunk -> Timber.tag(TAG).w("%s", chunk) }
    }

    fun text(value: String?): String =
        value.orEmpty()
            .replace("\r", "\\r")
            .replace("\n", "\\n")

    internal fun chunks(
        message: String,
        maxChunkChars: Int = MAX_CHUNK_CHARS
    ): List<String> {
        require(maxChunkChars > 0) { "maxChunkChars must be positive" }
        if (message.length <= maxChunkChars) return listOf(message)
        val out = mutableListOf<String>()
        var start = 0
        while (start < message.length) {
            val end = (start + maxChunkChars).coerceAtMost(message.length)
            out += message.substring(start, end)
            start = end
        }
        return out
    }
}
