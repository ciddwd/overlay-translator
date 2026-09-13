package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.data.withApiTimeout
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber

data class BalanceAmount(val currency: String, val total: BigDecimal)

sealed interface ConnectionBalance {
    data class Available(val amounts: List<BalanceAmount>, val canUseApi: Boolean) : ConnectionBalance
    data class Failed(val reason: String) : ConnectionBalance
}

internal fun deepSeekBalanceUrl(settings: Settings): String? {
    if (settings.translatorEngine != TranslatorEngine.OPENAI || settings.apiKey.isBlank()) return null
    val url = runCatching { URI(settings.baseUrl.trim()) }.getOrNull() ?: return null
    return "https://api.deepseek.com/user/balance".takeIf {
        url.scheme.equals("https", true) && url.host.equals("api.deepseek.com", true) &&
            url.port in setOf(-1, 443) && url.rawUserInfo == null &&
            url.rawQuery == null && url.rawFragment == null &&
            url.rawPath.orEmpty().trimEnd('/') in setOf("", "/v1")
    }
}

internal fun parseDeepSeekBalance(raw: String, json: Json): ConnectionBalance.Available {
    val root = json.parseToJsonElement(raw) as? JsonObject ?: error("Invalid balance object")
    val available = (root["is_available"] as? JsonPrimitive)?.booleanOrNull
        ?: error("Missing balance availability")
    val infos = root["balance_infos"] as? JsonArray ?: error("Missing balance entries")
    require(infos.size in 1..8)
    val amounts = infos.map { item ->
        val fields = item as? JsonObject ?: error("Invalid balance entry")
        val currency = (fields["currency"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(currency.matches(Regex("[A-Z]{3}")))
        Currency.getInstance(currency)
        val value = (fields["total_balance"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        require(value.matches(Regex("-?[0-9]{1,24}(\\.[0-9]{1,12})?")))
        BalanceAmount(currency, BigDecimal(value))
    }
    return ConnectionBalance.Available(amounts, available)
}

internal fun formatConnectionBalance(balance: ConnectionBalance.Available, locale: Locale): String =
    balance.amounts.joinToString(" / ") { amount ->
        NumberFormat.getCurrencyInstance(locale).apply {
            currency = Currency.getInstance(amount.currency)
            minimumFractionDigits = maxOf(2, amount.total.scale())
            maximumFractionDigits = minimumFractionDigits
        }.format(amount.total)
    }

/** One test pipeline for both settings and onboarding. Balance is optional metadata, not a probe. */
@Singleton
class TranslatorConnectionTester @Inject constructor(
    private val translator: RoutingTranslator,
    private val balances: DeepSeekBalanceClient,
) {
    suspend fun test(settings: Settings): TestResult = runConnectionTest(
        settings, translator::testConnection, balances::query,
    )
}

internal suspend fun runConnectionTest(
    settings: Settings,
    testEngine: suspend (Settings) -> TestResult,
    queryBalance: suspend (Settings) -> ConnectionBalance,
): TestResult = coroutineScope {
    val balance = deepSeekBalanceUrl(settings)?.let {
        async {
            try {
                queryBalance(settings)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ConnectionBalance.Failed(error.javaClass.simpleName)
            }
        }
    }
    val result = try {
        testEngine(settings)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // Header/URL validation exceptions may contain the supplied credential or URL.
        TestResult(false, error.javaClass.simpleName)
    }
    result.copy(balance = balance?.await())
}

@Singleton
class DeepSeekBalanceClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun query(settings: Settings): ConnectionBalance {
        val url = deepSeekBalanceUrl(settings) ?: return ConnectionBalance.Failed("unsupported_endpoint")
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${settings.apiKey.trim()}")
            .header("Accept", "application/json").get().build()
        // Do not forward credentials to a redirect target, including provider-controlled redirects.
        val call = client.withApiTimeout(settings.apiTimeoutSeconds).newBuilder()
            .followRedirects(false).followSslRedirects(false).build().newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(ConnectionBalance.Failed(e.javaClass.simpleName))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { resp ->
                        try {
                            if (!resp.isSuccessful) return@use ConnectionBalance.Failed("HTTP_${resp.code}")
                            val source = resp.body?.source() ?: return@use ConnectionBalance.Failed("empty_body")
                            if (source.request(64 * 1024L + 1L)) return@use ConnectionBalance.Failed("body_too_large")
                            parseDeepSeekBalance(source.readUtf8(), json)
                        } catch (error: Exception) {
                            ConnectionBalance.Failed(error.javaClass.simpleName)
                        }
                    }
                    if (result is ConnectionBalance.Failed) {
                        Timber.w("DeepSeek balance query failed: %s", result.reason)
                    }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }
}
