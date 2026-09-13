package com.gameocr.app.util

data class HttpResumePlan(
    val append: Boolean,
    val initialDownloaded: Long,
    val expectedTotal: Long,
)

/** Pure HTTP Range decisions shared by every model installer. */
object HttpResumePolicy {
    private val satisfiedRange = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
    private val unsatisfiedRange = Regex("bytes \\*/(\\d+)")

    fun unsatisfiedTotal(contentRange: String?): Long? = contentRange?.trim()
        ?.let { unsatisfiedRange.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** A 206 is appendable only when it describes exactly the bytes that were requested. */
    fun checkedResponsePlan(
        existingBytes: Long,
        responseCode: Int,
        contentLength: Long,
        contentRange: String?,
    ): HttpResumePlan? {
        if (responseCode == 200) return HttpResumePlan(false, 0, contentLength)
        if (responseCode != 206 || existingBytes < 0) return null
        val parts = contentRange?.trim()?.let(satisfiedRange::matchEntire)?.groupValues ?: return null
        val start = parts[1].toLongOrNull() ?: return null
        val end = parts[2].toLongOrNull() ?: return null
        val total = if (parts[3] == "*") -1 else parts[3].toLongOrNull() ?: return null
        if (start != existingBytes || end < start || end == Long.MAX_VALUE) return null
        if (total != -1L && total <= end) return null
        if (contentLength >= 0 && contentLength != end - start + 1) return null
        return HttpResumePlan(existingBytes > 0, existingBytes, total)
    }

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
