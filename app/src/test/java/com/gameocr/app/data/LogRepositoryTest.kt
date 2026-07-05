package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogRepositoryTest {

    @Test
    fun entries_preserveOptionalElapsedTime() {
        val repo = LogRepository()

        repo.info(LogRepository.Category.OCR, "done", elapsedMs = 842L)
        repo.warn(LogRepository.Category.OCR, "low confidence", elapsedMs = 1_250L)
        repo.error(LogRepository.Category.OCR, "failed", RuntimeException("boom"), elapsedMs = 77L)
        repo.pair(LogRepository.Category.TRANSLATE, "src", "dst", elapsedMs = 2_000L)
        repo.info(LogRepository.Category.CAPTURE, "plain")

        val entries = repo.entries.value
        assertEquals(842L, entries[0].elapsedMs)
        assertEquals(1_250L, entries[1].elapsedMs)
        assertEquals(77L, entries[2].elapsedMs)
        assertEquals(2_000L, entries[3].elapsedMs)
        assertNull(entries[4].elapsedMs)
    }
}
