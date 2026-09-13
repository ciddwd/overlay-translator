package com.gameocr.app.translate

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class MlKitModelDownloadSessionTest {
    @Test
    fun delayedConfirmation_tableDriven_cannotCancelReplacementOrOverwriteTerminalState() = runBlocking {
        listOf("same pair retry", "different pair", "ready", "failure", "timeout").forEach { case ->
            val finish = CompletableDeferred<Unit>()
            val session = MlKitModelDownloadSession(this) {
                finish.await()
                when (case) {
                    "failure" -> throw IOException("offline")
                    "timeout" -> withTimeout(1) { awaitCancellation() }
                }
            }
            session.start("ja" to "en", setOf("ja"))
            val requestedId = session.state.value.requestId
            if (case.endsWith("retry") || case == "different pair") {
                session.cancel(requestedId)
                session.start(if (case == "different pair") "ko" to "en" else "ja" to "en", setOf("ja"))
                assertNotEquals(requestedId, session.state.value.requestId)
            } else {
                finish.complete(Unit)
                withTimeout(2_000) { session.state.first { it.phase != MlKitDownloadPhase.DOWNLOADING } }
            }
            val before = session.state.value
            session.cancel(requestedId)
            assertEquals(case, before, session.state.value)
            session.reset()
        }
    }

    @Test
    fun outcomes_tableDriven_timeoutIsDistinctFromFailureAndCancellation() = runBlocking {
        listOf(MlKitDownloadPhase.READY, MlKitDownloadPhase.FAILED, MlKitDownloadPhase.TIMED_OUT, MlKitDownloadPhase.CANCELLED).forEach { expected ->
            val session = MlKitModelDownloadSession(this) {
                when (expected) {
                    MlKitDownloadPhase.FAILED -> throw IOException("offline")
                    MlKitDownloadPhase.TIMED_OUT -> withTimeout(1) { awaitCancellation() }
                    MlKitDownloadPhase.CANCELLED -> throw CancellationException("SDK cancelled")
                    else -> Unit
                }
            }
            session.start("ko" to "zh-CN", setOf("ko", "zh"))
            assertEquals(MlKitDownloadPhase.DOWNLOADING, session.state.value.phase)
            val state = withTimeout(2_000) { session.state.first { it.phase == expected } }
            assertEquals(expected == MlKitDownloadPhase.TIMED_OUT, state.browserUrl != null)
            assertEquals(if (expected == MlKitDownloadPhase.FAILED) "offline" else null, state.error)
            session.reset()
        }
    }

    @Test
    fun cancelAndRetry_tableDriven_ignoreLateSuccessAndLateError() = runBlocking {
        listOf(false, true).forEach { lateFailure ->
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val oldFinished = CompletableDeferred<Unit>()
            var attempts = 0
            val session = MlKitModelDownloadSession(this) {
                if (attempts++ == 0) {
                    withContext(NonCancellable) {
                        started.complete(Unit)
                        release.await()
                        oldFinished.complete(Unit)
                        if (lateFailure) throw IOException("late failure")
                    }
                }
            }
            session.start("ko" to "zh-CN", setOf("ko"))
            started.await()
            session.start("ko" to "zh-CN", setOf("ko"))
            assertEquals("duplicate click ignored", 1, attempts)
            session.cancel()
            assertEquals(MlKitDownloadPhase.CANCELLED, session.state.value.phase)
            assertNull(session.state.value.browserUrl)
            session.start("ja" to "en", setOf("ja"))
            val ready = withTimeout(2_000) { session.state.first { it.phase == MlKitDownloadPhase.READY } }
            release.complete(Unit)
            oldFinished.await()
            yield()
            assertEquals(ready, session.state.value)
            session.reset()
            assertEquals(MlKitModelDownloadState(), session.state.value)
        }
    }

    @Test
    fun timeoutRecovery_tableDriven_retryAndLanguageChangeClearAdviceImmediately() = runBlocking {
        listOf("ko" to "zh-CN", "ja" to "en").forEach { retryPair ->
            var calls = 0
            val session = MlKitModelDownloadSession(this) {
                if (calls++ == 0) withTimeout(1) { awaitCancellation() }
            }
            session.start("ko" to "zh-CN", setOf("ko"))
            withTimeout(2_000) { session.state.first { it.phase == MlKitDownloadPhase.TIMED_OUT } }
            session.start(retryPair, setOf(retryPair.first))
            assertEquals(MlKitDownloadPhase.DOWNLOADING, session.state.value.phase)
            assertNull(session.state.value.browserUrl)
            assertNull(session.state.value.error)
            withTimeout(2_000) { session.state.first { it.phase == MlKitDownloadPhase.READY } }
        }
    }

    @Test
    fun resetWhileRunning_invalidatesOldCompletion() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val session = MlKitModelDownloadSession(this) {
            withContext(NonCancellable) { started.complete(Unit); released.await() }
        }
        session.start("ko" to "en", setOf("ko"))
        started.await()
        session.reset()
        released.complete(Unit)
        yield()
        assertEquals(MlKitModelDownloadState(), session.state.value)
    }

    @Test
    fun links_tableDriven_onlyVerifiedRequiredMissingModels() {
        data class Case(val source: String, val target: String, val missing: Set<String>, val file: String?)
        listOf(
            Case("sq", "zh-CN", setOf("sq", "zh"), "en_sq.zip"),
            Case("et", "zh-CN", setOf("et", "zh"), "en_et.zip"),
            Case("ko-KR", "zh-CN", setOf("ko", "zh"), "en_ko.zip"),
            Case("ja", "zh-CN", setOf("ja", "zh"), "en_ja.zip"),
            Case("ja", "zh-TW", setOf("zh"), "en_zh.zip"),
            Case("en-US", "ko", setOf("ko"), "en_ko.zip"),
            Case("zh_Hant", "en", setOf("zh-TW"), "en_zh.zip"),
            Case("nb", "en", setOf("no"), "en_no.zip"),
            Case("ja", "be", setOf("be"), "be_en.zip"),
            Case("ja", "be", setOf("ja", "be"), "en_ja.zip"),
            Case("he", "en", setOf("he"), "en_iw.zip"),
            Case("ko", "zh-CN", emptySet(), null),
            Case("ko", "en", setOf("ja"), null),
            Case("auto", "ko", setOf("ko"), null),
            Case("xx", "ko", setOf("ko"), null),
            Case("ko", "ko-KR", setOf("ko"), null),
            Case("ar", "en", setOf("ar"), "ar_en.zip"),
            Case("en", "en", emptySet(), null),
        ).forEach { case ->
            val expected = case.file?.let { "https://redirector.gvt1.com/edgedl/translate/offline/v5/high/r29/$it" }
            assertEquals(case.toString(), expected, MlKitModelDownloadLinks.forPair(case.source to case.target, case.missing))
        }
    }

    @Test
    fun visibility_tableDriven_onlyTimeoutExposesBrowser() {
        MlKitDownloadPhase.entries.forEach { phase ->
            val state = MlKitModelDownloadState("ja" to "en", phase, setOf("ja"))
            assertEquals(phase.name, phase == MlKitDownloadPhase.TIMED_OUT, state.browserUrl != null)
        }
    }

    @Test
    fun belarusianTimeout_showsItsLinkWhenJapaneseIsAlreadyInstalled() = runBlocking {
        val session = MlKitModelDownloadSession(this) { withTimeout(1) { awaitCancellation() } }
        session.start("ja" to "be", setOf("be"))
        val state = withTimeout(2_000) { session.state.first { it.phase == MlKitDownloadPhase.TIMED_OUT } }
        assertEquals("https://redirector.gvt1.com/edgedl/translate/offline/v5/high/r29/be_en.zip", state.browserUrl)
        session.start("ja" to "be", setOf("be"))
        assertNull("retry clears old timeout link", session.state.value.browserUrl)
        session.cancel()
        assertNull("manual cancellation does not masquerade as timeout", session.state.value.browserUrl)
    }
}
