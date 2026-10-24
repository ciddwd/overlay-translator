package com.gameocr.app.ui

import com.gameocr.app.data.SettingsSecretStorageException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsWriteOperationTest {
    @Test fun rejectedWriteShowsFailureAndSkipsSuccessActions() = runBlocking {
        val events = mutableListOf<String>()
        val saved = runSettingsWrite(onFailure = { events += "snackbar" }) {
            events += "write"
            throw SettingsSecretStorageException()
            @Suppress("UNREACHABLE_CODE")
            events += "close or saved"
        }
        assertFalse(saved)
        assertEquals(listOf("write", "snackbar"), events)
    }

    @Test fun successfulWritesKeepTheExistingSuccessActions() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(runSettingsWrite(onFailure = { events += "snackbar" }) { events += "saved" })
        assertEquals(listOf("saved"), events)
    }

    @Test fun unrelatedErrorsAndCancellationAreNotSwallowed() = runBlocking {
        for (failure in listOf(IllegalStateException("unrelated"), CancellationException("cancelled"))) {
            var notified = false
            try {
                runSettingsWrite(onFailure = { notified = true }) { throw failure }
                fail("must propagate")
            } catch (actual: Exception) { assertSame(failure, actual) }
            assertFalse(notified)
        }
    }
}
