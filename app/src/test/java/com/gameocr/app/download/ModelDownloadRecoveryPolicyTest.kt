package com.gameocr.app.download

import com.gameocr.app.util.HttpResumePolicy
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadRecoveryPolicyTest {
    @Test fun pausedAndTerminalProgressNeverAnimates_table() {
        for (active in listOf(true, false)) for (total in listOf(-1L, 0L, 100L)) {
            val progress = com.gameocr.app.ui.modelDownloadProgressFraction(25, total, active)
            assertEquals("active=$active total=$total", active && total <= 0, progress == null)
        }
    }

    @Test fun retryClassification_table() {
        val cases = listOf(
            IOException("network closed") to true, SocketTimeoutException() to true,
            RuntimeException("configuration") to false, OutOfMemoryError() to false,
            RuntimeException(OutOfMemoryError()) to false, CancellationException() to false,
            ModelDownloadFileException("disk full") to false,
            ModelDownloadResumeException("wrong offset") to false,
        ) + listOf(400, 401, 403, 404, 408, 416, 429, 500, 501, 502, 503, 504).map {
            ModelDownloadHttpException(it) to (it in setOf(408, 429, 500, 502, 503, 504))
        }
        cases.forEach { (error, transient) ->
            for (attempt in 0..4) assertEquals("$error attempt=$attempt", transient && attempt < 2,
                ModelDownloadWorkPolicy.shouldRetry(attempt, error))
        }
        assertFalse(ModelDownloadWorkPolicy.mayTryAnotherSource(IOException(OutOfMemoryError())))
        assertFalse(ModelDownloadWorkPolicy.mayTryAnotherSource(IOException(CancellationException())))
    }

    @Test fun strictRange_table() {
        data class Case(val start: Long, val code: Int, val length: Long, val range: String?, val valid: Boolean)
        listOf(
            Case(4, 206, 6, "bytes 4-9/10", true),
            Case(4, 200, 10, null, true),
            Case(4, 206, 6, "bytes 3-8/10", false),
            Case(4, 206, 6, "bytes 4-10/10", false),
            Case(4, 206, 5, "bytes 4-9/10", false),
            Case(4, 206, 6, null, false),
            Case(4, 416, 0, "bytes */4", false),
            Case(4, 206, 6, "bytes 4-9/*", true),
            Case(4, 206, -1, "bytes 4-9/10", true),
            Case(0, 206, -1, "bytes 0-9223372036854775807/*", false),
        ).forEach { c -> assertEquals(c.toString(), c.valid,
            HttpResumePolicy.checkedResponsePlan(c.start, c.code, c.length, c.range) != null) }
        assertEquals(10L, HttpResumePolicy.unsatisfiedTotal("bytes */10"))
        listOf(null, "bytes 0-9/10", "bytes */-1", "bytes */x").forEach {
            assertNull(HttpResumePolicy.unsatisfiedTotal(it))
        }
    }
}
