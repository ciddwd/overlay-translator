package com.gameocr.app.ocr

import com.gameocr.app.translate.MlKitDownloadPhase
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MlKitOcrDownloadSessionTest {
    @Test fun outcomes_tableDriven_neverExposeUnverifiedModelUrl() = runBlocking {
        for (expected in listOf(MlKitDownloadPhase.READY, MlKitDownloadPhase.FAILED,
            MlKitDownloadPhase.TIMED_OUT, MlKitDownloadPhase.CANCELLED)) {
            var cleaned = false
            val session = MlKitOcrDownloadSession(this) {
                try {
                    when (expected) {
                        MlKitDownloadPhase.FAILED -> throw IOException("offline")
                        MlKitDownloadPhase.TIMED_OUT -> awaitCancellation()
                        MlKitDownloadPhase.CANCELLED -> throw CancellationException()
                        else -> Unit
                    }
                } finally { cleaned = true }
            }
            session.start(1)
            val result = withTimeout(3_000) { session.state.first { it.phase == expected } }
            assertTrue(cleaned)
            assertNull(result.browserUrl)
            assertEquals(if (expected == MlKitDownloadPhase.FAILED) "offline" else null, result.error)
            session.clearFinished()
            assertEquals(MlKitDownloadPhase.IDLE, session.state.value.phase)
        }
    }

    @Test fun cancellation_tableDriven_lateCompletionCannotOverwriteRetry() = runBlocking {
        for (lateFailure in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            var count = 0
            val session = MlKitOcrDownloadSession(this) {
                if (count++ == 0) withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                    finished.complete(Unit)
                    if (lateFailure) throw IOException("late failure")
                }
            }
            session.start(30)
            started.await()
            val original = session.state.value.requestId
            session.start(30)
            assertEquals(1, count)
            session.clearFinished()
            assertEquals(MlKitDownloadPhase.DOWNLOADING, session.state.value.phase)
            session.cancel(original)
            assertEquals(MlKitDownloadPhase.CANCELLED, session.state.value.phase)
            session.start(30)
            val ready = withTimeout(2_000) { session.state.first { it.phase == MlKitDownloadPhase.READY } }
            session.cancel(original)
            release.complete(Unit)
            finished.await()
            assertEquals(ready, session.state.value)
            session.reset()
        }
    }

    @Test fun requestAcceptedIsNotModelReadyAndNetworkTimeoutIsUsed() = runBlocking {
        val installed = CompletableDeferred<Unit>()
        val session = MlKitOcrDownloadSession(this) { installed.await() }
        session.start(5)
        kotlinx.coroutines.delay(1_100)
        assertEquals(MlKitDownloadPhase.DOWNLOADING, session.state.value.phase)
        installed.complete(Unit)
        withTimeout(2_000) { session.state.first { it.phase == MlKitDownloadPhase.READY } }
        session.reset()
    }
}
