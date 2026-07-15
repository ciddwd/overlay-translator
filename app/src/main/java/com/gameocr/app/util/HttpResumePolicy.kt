package com.gameocr.app.util

data class HttpResumePlan(
    val append: Boolean,
    val initialDownloaded: Long,
    val expectedTotal: Long,
)

/** Pure HTTP Range decisions shared by every model installer. */
object HttpResumePolicy {
    fun rangeHeader(existingBytes: Long): String? =
        existingBytes.takeIf { it > 0 }?.let { "bytes=$it-" }

    fun responsePlan(
        existingBytes: Long,
        responseCode: Int,
        contentLength: Long,
    ): HttpResumePlan {
        val append = existingBytes > 0 && responseCode == 206
        val initialDownloaded = if (append) existingBytes else 0L
        val expectedTotal = if (contentLength > 0) initialDownloaded + contentLength else -1L
        return HttpResumePlan(
            append = append,
            initialDownloaded = initialDownloaded,
            expectedTotal = expectedTotal,
        )
    }
}
