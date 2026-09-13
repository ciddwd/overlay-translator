package com.gameocr.app.translate

import kotlinx.coroutines.withTimeout

internal object MlKitModelDownloadPolicy {
    fun timeoutMillis(timeoutSeconds: Int): Long = timeoutSeconds.coerceIn(5, 120) * 1_000L

    // Bounds one language-pair operation, not each language separately. Cancelling await()
    // releases our wait/client; ML Kit does not expose cancellation of its background download.
    suspend fun <T> awaitDownload(timeoutSeconds: Int, download: suspend () -> T): T =
        withTimeout(timeoutMillis(timeoutSeconds)) { download() }
}
