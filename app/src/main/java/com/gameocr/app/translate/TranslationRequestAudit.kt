package com.gameocr.app.translate

import com.gameocr.app.BuildConfig
import java.nio.charset.StandardCharsets
import okhttp3.Request
import okio.Buffer
import timber.log.Timber

/** Debug-only LLM request/response audit. Authentication headers are never inspected or logged. */
internal object TranslationRequestAudit {
    private const val REQUEST_TAG = "TranslationRequest"
    private const val RESPONSE_TAG = "TranslationResponse"
    private const val MAX_CHUNK_UTF8_BYTES = 3_000

    fun log(
        requestId: String,
        engine: String,
        kind: String,
        stream: Boolean,
        request: Request,
    ) {
        if (!BuildConfig.DEBUG) return
        val payload = runCatching {
            requestPayload(request)
        }.getOrElse { error ->
            Timber.tag(REQUEST_TAG).w(
                error,
                "outbound request=%s engine=%s kind=%s body=unavailable",
                requestId,
                engine,
                kind,
            )
            return
        }
        val parts = chunkUtf8(payload)
        Timber.tag(REQUEST_TAG).i(
            "outbound request=%s engine=%s kind=%s stream=%s method=%s url=%s " +
                "bodyChars=%d bodyUtf8Bytes=%d parts=%d",
            requestId,
            engine,
            kind,
            stream,
            request.method,
            request.url,
            payload.length,
            payload.toByteArray(StandardCharsets.UTF_8).size,
            parts.size,
        )
        parts.forEachIndexed { index, part ->
            Timber.tag(REQUEST_TAG).i(
                "outbound request=%s bodyPart=%d/%d body=%s",
                requestId,
                index + 1,
                parts.size,
                part,
            )
        }
    }

    /** Logs the exact model text passed to the structured page response parser. */
    fun logStructuredResponse(
        requestId: String,
        engine: String,
        body: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val parts = chunkUtf8(body)
        Timber.tag(RESPONSE_TAG).i(
            "inbound request=%s engine=%s kind=translation_batch bodyChars=%d bodyUtf8Bytes=%d parts=%d",
            requestId,
            engine,
            body.length,
            body.toByteArray(StandardCharsets.UTF_8).size,
            parts.size,
        )
        parts.forEachIndexed { index, part ->
            Timber.tag(RESPONSE_TAG).i(
                "inbound request=%s bodyPart=%d/%d body=%s",
                requestId,
                index + 1,
                parts.size,
                part,
            )
        }
    }

    /** Logs malformed structured stream events in Debug builds without inspecting headers. */
    fun logMalformedStreamEvent(
        requestId: String,
        engine: String,
        eventIndex: Int,
        payload: String,
    ) {
        if (!BuildConfig.DEBUG) return
        val parts = chunkUtf8(payload)
        Timber.tag(RESPONSE_TAG).w(
            "inbound request=%s engine=%s kind=translation_batch malformedEvent=%d " +
                "bodyChars=%d bodyUtf8Bytes=%d parts=%d",
            requestId,
            engine,
            eventIndex,
            payload.length,
            payload.toByteArray(StandardCharsets.UTF_8).size,
            parts.size,
        )
        parts.forEachIndexed { index, part ->
            Timber.tag(RESPONSE_TAG).w(
                "inbound request=%s malformedEvent=%d bodyPart=%d/%d body=%s",
                requestId,
                eventIndex,
                index + 1,
                parts.size,
                part,
            )
        }
    }

    internal fun requestPayload(request: Request): String = Buffer().use { buffer ->
        request.body?.writeTo(buffer)
        buffer.readUtf8()
    }

    internal fun chunkUtf8(
        value: String,
        maxUtf8Bytes: Int = MAX_CHUNK_UTF8_BYTES,
    ): List<String> {
        require(maxUtf8Bytes >= 4) { "maxUtf8Bytes must fit one Unicode code point" }
        if (value.isEmpty()) return listOf("")

        val chunks = mutableListOf<String>()
        var chunkStart = 0
        var cursor = 0
        var chunkBytes = 0
        while (cursor < value.length) {
            val codePoint = value.codePointAt(cursor)
            val codePointChars = Character.charCount(codePoint)
            val codePointBytes = when {
                codePoint <= 0x7F -> 1
                codePoint <= 0x7FF -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
            if (chunkBytes > 0 && chunkBytes + codePointBytes > maxUtf8Bytes) {
                chunks += value.substring(chunkStart, cursor)
                chunkStart = cursor
                chunkBytes = 0
            }
            cursor += codePointChars
            chunkBytes += codePointBytes
        }
        chunks += value.substring(chunkStart)
        return chunks
    }
}
