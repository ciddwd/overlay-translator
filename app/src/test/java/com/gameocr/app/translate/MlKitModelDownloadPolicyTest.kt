package com.gameocr.app.translate

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitModelDownloadPolicyTest {
    @Test
    fun deadline_tableDriven_usesOneConfiguredLimitWithSettingsBounds() {
        listOf(
            Int.MIN_VALUE to 5_000L,
            0 to 5_000L,
            5 to 5_000L,
            30 to 30_000L,
            60 to 60_000L,
            120 to 120_000L,
            Int.MAX_VALUE to 120_000L,
        ).forEach { (seconds, expected) ->
            assertEquals("timeout=$seconds", expected, MlKitModelDownloadPolicy.timeoutMillis(seconds))
        }
    }

    @Test
    fun completion_tableDriven_successFailureAndSdkCancellationCloseClient() = runBlocking {
        val sdkCancellation = CancellationException("SDK cancelled")
        listOf(null, IOException("offline"), sdkCancellation).forEach { failure ->
            val client = FakeClient { failure?.let { throw it } }
            val result = runCatching {
                translator { client }.ensureLanguagePairModelsDownloaded("ko", "zh-CN", 30)
            }
            assertTrue("client closed for $failure", client.closed)
            assertEquals(1, client.downloadCalls)
            assertEquals(0, client.translateCalls)
            when (failure) {
                null -> assertTrue(result.isSuccess)
                sdkCancellation -> {
                    assertTrue(result.exceptionOrNull() is CancellationException)
                    assertEquals(sdkCancellation.message, result.exceptionOrNull()?.message)
                }
                else -> {
                    assertTrue(result.exceptionOrNull() is TranslationException)
                    // Coroutine stack recovery can insert a copy of the wrapping exception.
                    assertTrue(generateSequence(result.exceptionOrNull()) { it.cause }.any { it === failure })
                }
            }
        }
    }

    @Test(timeout = 15_000)
    fun hungDownload_timesOutAndClosesClient_withoutCancellingExternalSdkTask() = runBlocking {
        val sdkTask = CompletableDeferred<Unit>()
        val client = FakeClient { sdkTask.await() }
        val result = runCatching {
            translator { client }.ensureLanguagePairModelsDownloaded("ja", "zh-CN", 5)
        }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        assertTrue(client.closed)
        assertEquals(1, client.downloadCalls)
        assertEquals(0, client.translateCalls)
        assertFalse("cancelling our wait does not cancel the SDK task", sdkTask.isCancelled)
        sdkTask.complete(Unit)
        assertTrue(sdkTask.isCompleted)
        assertTrue("late SDK completion cannot turn timeout into success", result.isFailure)
    }

    @Test
    fun manualCancellation_tableDriven_allowsRetryAndIgnoresLateCompletion() = runBlocking {
        listOf(5, 30, 120).forEach { timeoutSeconds ->
            val started = CompletableDeferred<Unit>()
            val sdkTask = CompletableDeferred<Unit>()
            val first = FakeClient { started.complete(Unit); sdkTask.await() }
            val second = FakeClient { }
            var clients = 0
            val translator = translator { if (clients++ == 0) first else second }
            var reportedSuccess = false
            val job = launch {
                translator.ensureLanguagePairModelsDownloaded("ko", "zh-CN", timeoutSeconds)
                reportedSuccess = true
            }
            started.await()
            job.cancelAndJoin()
            assertTrue("closed for $timeoutSeconds", first.closed)
            assertTrue(job.isCancelled)
            assertFalse(reportedSuccess)
            translator.ensureLanguagePairModelsDownloaded("ko", "zh-CN", timeoutSeconds)
            assertTrue(second.closed)
            assertEquals(2, clients)
            sdkTask.complete(Unit)
            assertFalse("late completion for $timeoutSeconds", reportedSuccess)
        }
    }

    @Test
    fun sameLanguage_tableDriven_doesNotDownloadOrWait() = runBlocking {
        listOf("en" to "en-US", "ko" to "ko-KR", "zh-CN" to "zh-TW").forEach { pair ->
            translator { error("No client required for $pair") }
                .ensureLanguagePairModelsDownloaded(pair.first, pair.second, 5)
        }
    }

    private fun translator(create: () -> MlKitTranslationClient) = MlKitOnDeviceTranslator(
        clientFactory = MlKitTranslationClientFactory { _, _ -> create() },
        downloadedLanguageProvider = MlKitDownloadedLanguageProvider { emptySet() },
        modelDeleter = MlKitLanguageModelDeleter { error("Unexpected model deletion") },
        cache = TranslationCache(capacity = 16),
    )

    private class FakeClient(private val download: suspend () -> Unit) : MlKitTranslationClient {
        var closed = false
        var downloadCalls = 0
        var translateCalls = 0

        override suspend fun downloadModelIfNeeded() {
            downloadCalls++
            download()
        }

        override suspend fun translate(text: String): String {
            translateCalls++
            return text
        }

        override fun close() { closed = true }
    }
}
