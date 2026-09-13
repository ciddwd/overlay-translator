package com.gameocr.app.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.llm.LlmModelInstaller
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.ocr.MangaOcrModelInstaller
import com.gameocr.app.ocr.OrientationModelInstaller
import com.gameocr.app.ocr.PaddleModelInstaller
import com.gameocr.app.network.HttpBodyLogPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

internal enum class ModelDownloadNetworkTarget {
    LOCAL_LLM,
    PADDLE_OCR,
    MANGA_OCR,
    ORIENTATION,
}

internal enum class ModelDownloadNetworkFailureKind {
    NO_NETWORK,
    DNS,
    TLS,
    TIMEOUT,
    CONNECTION,
    HTTP,
    INVALID_SOURCE,
    UNKNOWN,
}

internal data class ModelDownloadNetworkProbeResult(
    val target: ModelDownloadNetworkTarget,
    val successful: Boolean,
    val host: String,
    val httpCode: Int? = null,
    val elapsedMs: Long = 0L,
    val failureKind: ModelDownloadNetworkFailureKind? = null,
    val detail: String = "",
    val cause: Throwable? = null,
)

internal object ModelDownloadNetworkPolicy {
    fun acceptsHttpCode(code: Int): Boolean = code in 200..299

    fun classifyFailure(error: Throwable): ModelDownloadNetworkFailureKind = when (error) {
        is UnknownHostException -> ModelDownloadNetworkFailureKind.DNS
        is SSLException -> ModelDownloadNetworkFailureKind.TLS
        is SocketTimeoutException -> ModelDownloadNetworkFailureKind.TIMEOUT
        is ConnectException -> ModelDownloadNetworkFailureKind.CONNECTION
        is IllegalArgumentException -> ModelDownloadNetworkFailureKind.INVALID_SOURCE
        else -> ModelDownloadNetworkFailureKind.UNKNOWN
    }
}

/**
 * Probes the exact primary URL selected by each model installer without downloading the model.
 * A one-byte range request exercises DNS, TLS, redirects and the final HTTP response while keeping
 * both traffic and latency bounded by the user's network timeout setting.
 */
@Singleton
class ModelDownloadNetworkTester @Inject constructor(
    @ApplicationContext private val context: Context,
    private val baseClient: OkHttpClient,
    private val settingsRepository: SettingsRepository,
    private val llmInstaller: LlmModelInstaller,
    private val paddleInstaller: PaddleModelInstaller,
    private val mangaOcrInstaller: MangaOcrModelInstaller,
    private val orientationModelInstaller: OrientationModelInstaller,
) {
    internal suspend fun requireReachable(spec: ModelDownloadSpec): ModelDownloadNetworkProbeResult {
        val target = targetFor(spec)
        val result = probe(target, sourceUrl(spec))
        if (!result.successful) throw result.cause
            ?: result.httpCode?.let { ModelDownloadHttpException(it) }
            ?: java.io.IOException(result.detail)
        return result
    }

    internal suspend fun probeConfiguredSources(): List<ModelDownloadNetworkProbeResult> {
        val settings = settingsRepository.get()
        val requests = listOf(
            ModelDownloadNetworkTarget.LOCAL_LLM to runCatching {
                llmInstaller.downloadProbeUrl(LlmModelKind.SAKURA_1_5B_Q4)
            },
            ModelDownloadNetworkTarget.PADDLE_OCR to runCatching {
                paddleInstaller.downloadProbeUrl(settings.paddleModelVersion)
            },
            ModelDownloadNetworkTarget.MANGA_OCR to runCatching {
                mangaOcrInstaller.downloadProbeUrl()
            },
        )
        return requests.map { (target, resolvedUrl) ->
            resolvedUrl.fold(
                onSuccess = { url -> probe(target, url) },
                onFailure = { error -> failedResolution(target, error) },
            )
        }
    }

    private suspend fun sourceUrl(spec: ModelDownloadSpec): String = when (spec.type) {
        ModelDownloadType.LLM -> llmInstaller.downloadProbeUrl(LlmModelKind.valueOf(spec.variant))
        ModelDownloadType.PADDLE -> paddleInstaller.downloadProbeUrl(
            com.gameocr.app.data.PaddleModelVersion.valueOf(spec.variant),
        )
        ModelDownloadType.MANGA_OCR -> mangaOcrInstaller.downloadProbeUrl()
        ModelDownloadType.ORIENTATION -> orientationModelInstaller.downloadProbeUrl()
    }

    private fun targetFor(spec: ModelDownloadSpec): ModelDownloadNetworkTarget = when (spec.type) {
        ModelDownloadType.LLM -> ModelDownloadNetworkTarget.LOCAL_LLM
        ModelDownloadType.PADDLE -> ModelDownloadNetworkTarget.PADDLE_OCR
        ModelDownloadType.MANGA_OCR -> ModelDownloadNetworkTarget.MANGA_OCR
        ModelDownloadType.ORIENTATION -> ModelDownloadNetworkTarget.ORIENTATION
    }

    private suspend fun probe(
        target: ModelDownloadNetworkTarget,
        url: String,
    ): ModelDownloadNetworkProbeResult = withContext(Dispatchers.IO) {
        if (!hasInternetCapability()) {
            return@withContext ModelDownloadNetworkProbeResult(
                target = target,
                successful = false,
                host = hostOf(url),
                failureKind = ModelDownloadNetworkFailureKind.NO_NETWORK,
                detail = "No active network",
            )
        }
        val timeoutSeconds = settingsRepository.get().apiTimeoutSeconds.coerceIn(5, 120).toLong()
        val client = baseClient.newBuilder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(url)
            .tag(HttpBodyLogPolicy::class.java, HttpBodyLogPolicy.METADATA_ONLY)
            .header("Range", "bytes=0-0")
            .get()
            .build()
        val startedAt = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                val code = response.code
                if (!ModelDownloadNetworkPolicy.acceptsHttpCode(code)) {
                    return@withContext ModelDownloadNetworkProbeResult(
                        target = target,
                        successful = false,
                        host = response.request.url.host,
                        httpCode = code,
                        elapsedMs = elapsedMs,
                        failureKind = ModelDownloadNetworkFailureKind.HTTP,
                        detail = "HTTP $code",
                    )
                }
                response.body?.byteStream()?.use { it.read() }
                ModelDownloadNetworkProbeResult(
                    target = target,
                    successful = true,
                    host = response.request.url.host,
                    httpCode = code,
                    elapsedMs = elapsedMs,
                )
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            ModelDownloadNetworkProbeResult(
                target = target,
                successful = false,
                host = hostOf(url),
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L,
                failureKind = ModelDownloadNetworkPolicy.classifyFailure(error),
                detail = error.message ?: error.javaClass.simpleName,
                cause = error,
            )
        }
    }

    private fun failedResolution(
        target: ModelDownloadNetworkTarget,
        error: Throwable,
    ): ModelDownloadNetworkProbeResult = ModelDownloadNetworkProbeResult(
        target = target,
        successful = false,
        host = "",
        failureKind = ModelDownloadNetworkPolicy.classifyFailure(error),
        detail = error.message ?: error.javaClass.simpleName,
    )

    private fun hasInternetCapability(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hostOf(url: String): String = runCatching {
        url.toHttpUrl().host
    }.getOrDefault("")

}
