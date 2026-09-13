package com.gameocr.app.network

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Sink
import okio.Timeout
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import timber.log.Timber

/** Debug-only HTTP wire log. Registration is guarded by BuildConfig.DEBUG in AppModule. */
internal enum class HttpBodyLogPolicy { METADATA_ONLY }

internal class DebugHttpWireLoggingInterceptor(
    private val logger: (String) -> Unit = DebugHttpWireLogger::log,
) : Interceptor {
    private fun log(message: String) = safeHttpLog { logger(message) }
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val requestId = nextRequestId.incrementAndGet()
        val prefix = "#$requestId"
        val startedAt = System.nanoTime()

        safeHttpLog {
            log("$prefix --> ${request.method} ${DebugHttpLogSanitizer.url(request.url)}")
            logHeaders(prefix, "request", request.headers)
            logRequestBody(prefix, request)
        }

        val response = try {
            chain.proceed(request)
        } catch (error: Throwable) {
            log(
                "$prefix <-- FAILED elapsedMs=${elapsedMs(startedAt)} " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
            throw error
        }

        log(
            "$prefix <-- HTTP ${response.code} ${response.message} headersMs=${elapsedMs(startedAt)}",
        )
        safeHttpLog { logHeaders(prefix, "response", response.headers) }
        val body = response.body ?: return response.also {
            log("$prefix response-body=<empty> totalMs=${elapsedMs(startedAt)}")
        }
        return response.newBuilder()
            .body(DebugLoggingResponseBody(prefix, body, startedAt,
                request.tag(HttpBodyLogPolicy::class.java) != HttpBodyLogPolicy.METADATA_ONLY, ::log))
            .build()
    }

    private fun logHeaders(prefix: String, direction: String, headers: Headers) {
        if (headers.size == 0) {
            log("$prefix $direction-headers=<empty>")
            return
        }
        headers.forEach { (name, value) ->
            log(
                "$prefix $direction-header $name: ${DebugHttpLogSanitizer.header(name, value)}",
            )
        }
    }

    private fun logRequestBody(prefix: String, request: Request) {
        val body = request.body ?: run {
            log("$prefix request-body=<empty>")
            return
        }
        if (request.tag(HttpBodyLogPolicy::class.java) == HttpBodyLogPolicy.METADATA_ONLY ||
            !DebugHttpLogSanitizer.isTextBody(body.contentType())) {
            log("$prefix request-body=<binary length=${body.contentLength()}>")
            return
        }
        if (body.isOneShot() || body.isDuplex() || body.contentLength() !in 0..BoundedHttpBodyCapture.MAX_BYTES) {
            log("$prefix request-body=<omitted length=${body.contentLength()}>")
            return
        }
        val capture = BoundedHttpBodyCapture()
        try {
            object : Sink {
                override fun write(source: Buffer, byteCount: Long) {
                    capture.copyFrom(source, 0, byteCount)
                    source.skip(byteCount)
                }
                override fun flush() = Unit
                override fun close() = Unit
                override fun timeout() = Timeout.NONE
            }.buffer().use { body.writeTo(it) }
            log("$prefix request-body=${capture.finish(body.contentType(), true)}")
        } finally {
            capture.clear()
        }
    }

    private class DebugLoggingResponseBody(
        private val prefix: String,
        private val delegate: ResponseBody,
        private val startedAt: Long,
        allowBody: Boolean,
        private val log: (String) -> Unit,
    ) : ResponseBody() {
        private val completed = AtomicBoolean(false)
        private val textBody = allowBody && DebugHttpLogSanitizer.isTextBody(delegate.contentType())
        private val captured = BoundedHttpBodyCapture(enabled = textBody)
        private val loggingSource: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val offset = sink.size
                    return try {
                        val read = super.read(sink, byteCount)
                        if (read > 0L) safeHttpLog { captured.copyFrom(sink, offset, read) }
                        if (read == -1L) finish("complete")
                        read
                    } catch (error: Throwable) {
                        finish("read_failed:${error.javaClass.simpleName}")
                        throw error
                    }
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        finish("closed")
                    }
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = loggingSource

        private fun finish(outcome: String) {
            if (!completed.compareAndSet(false, true)) return
            try {
                safeHttpLog {
                    val body = if (textBody) captured.finish(contentType(), outcome == "complete")
                        else "<binary length=${contentLength()}>"
                    log("$prefix response-body=$body outcome=$outcome totalMs=${elapsedMs(startedAt)}")
                }
            } finally {
                captured.clear()
            }
        }
    }

    private companion object {
        val nextRequestId = AtomicLong(0L)

        fun elapsedMs(startedAt: Long): Long =
            ((System.nanoTime() - startedAt).coerceAtLeast(0L)) / 1_000_000L
    }
}

internal object DebugHttpLogSanitizer {
    private val sensitiveNames = setOf(
        "authorization",
        "proxyauthorization",
        "cookie",
        "setcookie",
        "apikey",
        "xapikey",
        "appsecret",
        "apisecret",
        "secret",
        "secretkey",
        "authstr",
        "token",
        "accesstoken",
        "refreshtoken",
        "idtoken",
        "password",
        "signature",
    )
    private val jsonSecretStart = Regex(
        "(?i)\\\"(?:authorization|proxy[-_]?authorization|cookie|set[-_]?cookie|" +
            "api[-_]?key|x[-_]?api[-_]?key|app[-_]?secret|api[-_]?secret|secret(?:[-_]?key)?|" +
            "auth[-_]?str|(?:access|refresh|id)?[-_]?token|password|signature)\\\"\\s*:\\s*\\\"",
    )
    private val formSecret = Regex(
        pattern = "(?i)((?:^|[?&])(?:authorization|api[-_]?key|app[-_]?secret|api[-_]?secret|" +
            "secret(?:[-_]?key)?|auth[-_]?str|(?:access|refresh|id)?[-_]?token|password|signature)=)" +
            "[^&\\s]*",
    )

    fun header(name: String, value: String): String =
        if (isSensitive(name)) REDACTED else value

    fun url(url: HttpUrl): String {
        val builder = url.newBuilder()
        url.queryParameterNames.forEach { name ->
            if (isSensitive(name)) builder.setQueryParameter(name, REDACTED)
        }
        return builder.build().toString()
    }

    fun payload(raw: String): String {
        // Scan the value iteratively: a long secret must not overflow the regex engine's stack.
        val redacted = buildString {
            var cursor = 0
            while (cursor < raw.length) {
                val match = jsonSecretStart.find(raw, cursor)
                if (match == null) { append(raw, cursor, raw.length); break }
                val valueStart = match.range.last + 1
                append(raw, cursor, valueStart)
                append(REDACTED)
                var end = valueStart
                while (end < raw.length && raw[end] != '"') {
                    end += if (raw[end] == '\\') 2 else 1
                }
                cursor = end.coerceAtMost(raw.length)
                if (cursor < raw.length) { append('"'); cursor++ }
            }
        }
        return redacted.replace(formSecret, "$1$REDACTED")
    }

    fun isTextBody(contentType: MediaType?): Boolean {
        if (contentType == null) return false
        if (contentType.type.equals("text", ignoreCase = true)) return true
        val subtype = contentType.subtype.lowercase()
        return subtype.contains("json") ||
            subtype.contains("xml") ||
            subtype.contains("javascript") ||
            subtype.contains("form") ||
            subtype.contains("event-stream")
    }

    private fun isSensitive(name: String): Boolean {
        val normalized = name.lowercase().filter(Char::isLetterOrDigit)
        return normalized in sensitiveNames ||
            normalized.endsWith("apikey") ||
            normalized.endsWith("token") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("password")
    }

    private const val REDACTED = "***"
}

private object DebugHttpWireLogger {
    fun log(message: String) {
        if (message.isEmpty()) {
            Timber.tag(TAG).d("")
            return
        }
        var offset = 0
        while (offset < message.length) {
            val suffix = if (message.length > MAX_LOG_CHUNK) " part=${offset / MAX_LOG_CHUNK + 1}" else ""
            Timber.tag(TAG).d("$suffix ${message.substring(offset, minOf(offset + MAX_LOG_CHUNK, message.length))}")
            offset += MAX_LOG_CHUNK
        }
    }

    private const val TAG = "HttpWire"
    private const val MAX_LOG_CHUNK = 3_000
}

/** Only complete bounded bodies are logged, so a truncated secret cannot escape redaction. */
internal class BoundedHttpBodyCapture(private val enabled: Boolean = true) {
    private val buffer = Buffer()
    private var omitted = false
    internal val retainedBytes: Long get() = buffer.size

    fun copyFrom(source: Buffer, offset: Long, count: Long) {
        if (!enabled || omitted) return
        try {
            if (count > MAX_BYTES - buffer.size) {
                omitted = true
                buffer.clear()
            } else source.copyTo(buffer, offset, count)
        } catch (error: Throwable) {
            // Never later emit a partial body if capturing failed (including memory pressure).
            omitted = true
            buffer.clear()
            throw error
        }
    }

    fun finish(type: MediaType?, complete: Boolean): String = when {
        !enabled -> "<binary>"
        !complete -> "<incomplete body omitted>"
        omitted -> "<body omitted: exceeds $MAX_BYTES bytes>"
        else -> DebugHttpLogSanitizer.payload(buffer.readString(type?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
    }

    fun clear() = buffer.clear()
    companion object { const val MAX_BYTES = 64L * 1024 }
}

private inline fun safeHttpLog(action: () -> Unit) {
    try { action() } catch (_: Exception) {
        // Diagnostics must not fail the request they observe.
    } catch (_: OutOfMemoryError) {
        // In particular, logging must not turn an already-written file into a failed download.
    }
}
