package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class TranslatorConnectionTesterTest {
    private val settings = Settings(
        translatorEngine = TranslatorEngine.OPENAI,
        baseUrl = "https://api.deepseek.com/v1/", apiKey = "test-key", apiTimeoutSeconds = 19,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val body = """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.3400","granted_balance":"1","topped_up_balance":"11.3400"}]}"""

    @Test fun endpointTable_onlyOfficialDeepSeekRootOrV1_canReceiveTheKey() {
        val accepted = listOf("https://api.deepseek.com", "https://api.deepseek.com/", "https://api.deepseek.com/v1", "https://API.DEEPSEEK.COM:443/v1/")
        val rejected = listOf(
            "http://api.deepseek.com/v1/", "https://api.deepseek.com:8443/v1/",
            "https://api.deepseek.com.evil.example/v1/", "https://evil.example/api.deepseek.com/v1/",
            "https://api.deepseek.com@evil.example/v1/", "https://user@api.deepseek.com/v1/",
            "https://api.deepseek.com/v2/", "https://api.deepseek.com/other/v1/",
            "https://api.deepseek.com/v1/?token=secret", "https://api.deepseek.com/v1/#fragment",
            "https://api.deepseek.com/%76%31/", "https://api.deepseek.com./v1/", "not a url", "",
        )
        accepted.forEach { assertEquals(it, "https://api.deepseek.com/user/balance", deepSeekBalanceUrl(settings.copy(baseUrl = it))) }
        rejected.forEach { assertNull(it, deepSeekBalanceUrl(settings.copy(baseUrl = it))) }
        TranslatorEngine.entries.filter { it != TranslatorEngine.OPENAI }.forEach {
            assertNull(it.name, deepSeekBalanceUrl(settings.copy(translatorEngine = it)))
        }
        listOf("", "  ").forEach { assertNull(deepSeekBalanceUrl(settings.copy(apiKey = it))) }
    }

    @Test fun parsingTable_preservesCurrencyZeroNegativeAndExactDecimals() {
        listOf("CNY" to "12.3400", "USD" to "0", "CNY" to "-0.123456", "USD" to "123456789012345678901234.99").forEach { (currency, amount) ->
            val result = parseDeepSeekBalance("""{"is_available":false,"balance_infos":[{"currency":"$currency","total_balance":"$amount"}]}""", json)
            assertFalse(result.canUseApi)
            assertEquals(listOf(BalanceAmount(currency, BigDecimal(amount))), result.amounts)
        }
        assertEquals(BigDecimal("12.3400"), parseDeepSeekBalance(body, json).amounts.single().total)
    }

    @Test fun malformedTable_failsInsteadOfShowingInventedZeroBalance() {
        listOf("", "null", "[]", "{}", """{"is_available":true,"balance_infos":[]}""",
            body.replace("12.3400", "NaN"), body.replace("12.3400", "1e20"),
            body.replace("CNY", "bad"), body.replace("total_balance", "missing_field"),
            body.replace("true", "null"), body.replace("12.3400", "0.1234567890123"),
        ).forEach { assertTrue(it.take(25), runCatching { parseDeepSeekBalance(it, json) }.isFailure) }
    }

    @Test fun formattingTable_usesActualCurrencyAndDoesNotRoundAwaySmallBalances() {
        listOf(Locale.CHINA, Locale.US).forEach { locale ->
            val result = formatConnectionBalance(ConnectionBalance.Available(listOf(
                BalanceAmount("CNY", BigDecimal("12.34")), BalanceAmount("USD", BigDecimal("0.000001")),
            ), true), locale)
            assertTrue(result, result.contains("12.34"))
            assertTrue(result, result.contains("0.000001"))
            assertTrue(result, result.contains(" / "))
            assertTrue(result, result.contains("¥") || result.contains("￥") || result.contains("CNY") || result.contains("CN¥"))
            assertTrue(result, result.contains("$") || result.contains("USD"))
        }
    }

    @Test fun pipelineTable_balanceFailureNeverChangesConnectionOrModels() = runBlocking {
        for (success in listOf(true, false)) {
            for (balanceFails in listOf(true, false)) {
                val connection = TestResult(success, "connection", listOf("model-a", "model-b"))
                val tested = runConnectionTest(settings, { connection }, {
                    if (balanceFails) throw IOException("test-key must not appear")
                    parseDeepSeekBalance(body, json)
                })
                assertEquals(connection.success, tested.success)
                assertEquals(connection.message, tested.message)
                assertEquals(connection.models, tested.models)
                assertEquals(balanceFails, tested.balance is ConnectionBalance.Failed)
                assertFalse(tested.toString().contains("test-key"))
            }
        }
    }

    @Test fun otherProviderAndMissingKey_neverRunBalanceRequest() = runBlocking {
        listOf(settings.copy(baseUrl = "https://gateway.example/v1/"), settings.copy(apiKey = ""), settings.copy(translatorEngine = TranslatorEngine.ANTHROPIC)).forEach {
            val result = runConnectionTest(it, { TestResult(true, "ok") }, { error("Must not query") })
            assertTrue(result.success)
            assertNull(result.balance)
        }
    }

    @Test fun probesRunConcurrently_andParentCancellationReachesBoth() = runBlocking {
        withTimeout(3000) {
            val engineStarted = CompletableDeferred<Unit>()
            val balanceStarted = CompletableDeferred<Unit>()
            val engineStopped = CompletableDeferred<Unit>()
            val balanceStopped = CompletableDeferred<Unit>()
            val job = launch {
                runConnectionTest(settings, {
                    engineStarted.complete(Unit)
                    balanceStarted.await()
                    try { awaitCancellation() } finally { engineStopped.complete(Unit) }
                }, {
                    balanceStarted.complete(Unit)
                    engineStarted.await()
                    try { awaitCancellation() } finally { balanceStopped.complete(Unit) }
                })
            }
            engineStarted.await()
            balanceStarted.await()
            // Both probes are suspended before the parent is cancelled.
            kotlinx.coroutines.yield()
            job.cancelAndJoin()
            engineStopped.await()
            balanceStopped.await()
        }
    }

    @Test fun connectionException_doesNotExposeCredentials() = runBlocking {
        val result = runConnectionTest(settings.copy(apiKey = ""), { throw IllegalArgumentException("test-key") }, { error("not queried") })
        assertFalse(result.success)
        assertEquals("IllegalArgumentException", result.message)
    }

    @Test fun httpSmokeTable_headersTimeoutStatusMalformedBodyAndBoundedReading() = runBlocking {
        data class Case(val status: Int, val body: String, val expected: String?)
        listOf(Case(200, body, null), Case(401, "private details", "HTTP_401"),
            Case(429, "{}", "HTTP_429"), Case(500, "{}", "HTTP_500"),
            Case(302, "", "HTTP_302"), Case(200, "{}", "IllegalStateException"),
            Case(200, "x".repeat(65537), "body_too_large"),
        ).forEach { case ->
            var observedCall: Call? = null
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callStart(call: Call) { observedCall = call }
            }).addInterceptor { chain ->
                val request = chain.request()
                assertEquals("GET", request.method)
                assertEquals("https://api.deepseek.com/user/balance", request.url.toString())
                assertEquals("Bearer test-key", request.header("Authorization"))
                assertEquals("application/json", request.header("Accept"))
                assertEquals(19000, chain.readTimeoutMillis())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(case.status)
                    .message("test").header("Location", "https://untrusted.example/")
                    .body(case.body.toResponseBody()).build()
            }.build()
            try {
                val result = DeepSeekBalanceClient(client, json).query(settings)
                if (case.expected == null) assertTrue(result is ConnectionBalance.Available)
                else assertEquals(case.expected, (result as ConnectionBalance.Failed).reason)
                assertEquals(TimeUnit.SECONDS.toNanos(19), observedCall!!.timeout().timeoutNanos())
            } finally { client.dispatcher.executorService.shutdownNow(); client.connectionPool.evictAll() }
        }
    }

    @Test fun httpCancellation_cancelsActiveCall() = runBlocking {
        val started = CompletableDeferred<Call>()
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            started.complete(chain.call())
            release.await(3, TimeUnit.SECONDS)
            throw IOException("cancelled")
        }.build()
        try {
            val job = async { DeepSeekBalanceClient(client, json).query(settings) }
            val call = withTimeout(3000) { started.await() }
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
