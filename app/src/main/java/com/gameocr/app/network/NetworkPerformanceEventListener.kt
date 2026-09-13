package com.gameocr.app.network

import com.gameocr.app.util.RuntimePerformanceDiagnostics
import com.gameocr.app.util.ScreenWakePerformanceContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import timber.log.Timber

internal data class NetworkPhaseTiming(
    val host: String,
    val outcome: String,
    val headersMs: Long,
    val dnsMs: Long,
    val connectMs: Long,
    val tlsMs: Long,
    val serverWaitMs: Long,
    val reusedConnection: Boolean,
    val connectAttempts: Int,
    val firstRequestAfterWake: Boolean = false,
    val wakeAgeMs: Long? = null,
    val screenOffDurationMs: Long? = null,
    val idleConnectionsCleared: Int? = null,
) {
    fun logMessage(): String = buildString {
        append("[network] host=$host outcome=$outcome headersMs=$headersMs dnsMs=$dnsMs ")
        append("connectMs=$connectMs tlsMs=$tlsMs serverWaitMs=$serverWaitMs ")
        append("reused=$reusedConnection attempts=$connectAttempts")
        if (firstRequestAfterWake) {
            append(" firstAfterWake=true")
            wakeAgeMs?.let { append(" wakeAgeMs=$it") }
            screenOffDurationMs?.let { append(" screenOffMs=$it") }
            idleConnectionsCleared?.let { append(" evictedIdle=$it") }
        }
    }
}

/** Per-call DNS/TCP/TLS/header timing. The URL path, query, headers, and body are never logged. */
internal class NetworkPerformanceEventListener(
    private val host: String,
    private val performanceDiagnostics: RuntimePerformanceDiagnostics,
    private val wakeContext: ScreenWakePerformanceContext? = null,
    private val nowNanos: () -> Long = System::nanoTime,
) : EventListener() {
    private val callStartedAt = nowNanos()
    private var dnsStartedAt: Long? = null
    private var connectStartedAt: Long? = null
    private var tlsStartedAt: Long? = null
    private var requestFinishedAt: Long? = null
    private var dnsNanos = 0L
    private var connectNanos = 0L
    private var tlsNanos = 0L
    private var connectAttempts = 0
    private var summaryLogged = false

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartedAt = nowNanos()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        dnsStartedAt?.let { dnsNanos += elapsedNanos(it, nowNanos()) }
        dnsStartedAt = null
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectAttempts++
        connectStartedAt = nowNanos()
    }

    override fun secureConnectStart(call: Call) {
        tlsStartedAt = nowNanos()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        tlsStartedAt?.let { tlsNanos += elapsedNanos(it, nowNanos()) }
        tlsStartedAt = null
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        finishConnectAttempt()
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        finishConnectAttempt()
    }

    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
        requestFinishedAt = nowNanos()
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        requestFinishedAt = nowNanos()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        logSummary(outcome = "http_${response.code}", finishedAt = nowNanos())
    }

    override fun callFailed(call: Call, ioe: IOException) {
        logSummary(
            outcome = "failed_${ioe.javaClass.simpleName}",
            finishedAt = nowNanos(),
        )
    }

    private fun finishConnectAttempt() {
        val finishedAt = nowNanos()
        tlsStartedAt?.let { tlsNanos += elapsedNanos(it, finishedAt) }
        tlsStartedAt = null
        connectStartedAt?.let { connectNanos += elapsedNanos(it, finishedAt) }
        connectStartedAt = null
    }

    private fun logSummary(outcome: String, finishedAt: Long) {
        if (summaryLogged) return
        summaryLogged = true
        val timing = NetworkPhaseTiming(
            host = host,
            outcome = outcome,
            headersMs = toMillis(elapsedNanos(callStartedAt, finishedAt)),
            dnsMs = toMillis(dnsNanos),
            connectMs = toMillis(connectNanos),
            tlsMs = toMillis(tlsNanos),
            serverWaitMs = requestFinishedAt?.let { toMillis(elapsedNanos(it, finishedAt)) } ?: 0L,
            reusedConnection = connectAttempts == 0,
            connectAttempts = connectAttempts,
            firstRequestAfterWake = wakeContext != null,
            wakeAgeMs = wakeContext?.wakeAgeMs,
            screenOffDurationMs = wakeContext?.screenOffDurationMs,
            idleConnectionsCleared = wakeContext?.idleConnectionsCleared,
        )
        val message = timing.logMessage()
        Timber.tag(NETWORK_PERF_TAG).i(message)
    }

    private fun elapsedNanos(start: Long, end: Long): Long = (end - start).coerceAtLeast(0L)

    private fun toMillis(nanos: Long): Long = nanos / 1_000_000L

    class Factory(
        private val performanceDiagnostics: RuntimePerformanceDiagnostics,
    ) : EventListener.Factory {
        override fun create(call: Call): EventListener = NetworkPerformanceEventListener(
            host = call.request().url.host,
            performanceDiagnostics = performanceDiagnostics,
            wakeContext = performanceDiagnostics.claimFirstNetworkRequestAfterWake(),
        )
    }
}
