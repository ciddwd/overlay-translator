package com.gameocr.app.trigger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class InputReplacementVerificationTest {
    @Test fun boundedReadOnlyRetries() = runBlocking {
        for (successAt in listOf(1, 4, 11, 12)) {
            var reads = 0
            var waited = 0L
            val result = awaitInputReplacement(pause = { waited += it }) {
                ++reads == successAt
            }
            assertEquals(successAt <= 11, result)
            assertEquals(minOf(successAt, 11), reads)
            assertEquals((reads - 1) * 120L, waited)
        }
    }

    @Test fun cancellationStopsVerification() = runBlocking {
        var reads = 0
        try {
            awaitInputReplacement(pause = { throw CancellationException() }) {
                reads++
                false
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, reads)
        }
    }
}
