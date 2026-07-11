package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashRecorderPendingFileTest {

    @Test
    fun crashTimestamp_prefersFileNameThenFallsBackToLastModified() {
        data class Case(
            val name: String,
            val fileName: String,
            val lastModified: Long,
            val expected: Long,
        )

        val cases = listOf(
            Case("timestamp file name", "1725000000000.crash", 100L, 1_725_000_000_000L),
            Case("invalid file name", "legacy.crash", 200L, 200L),
            Case("non-positive file name", "0.crash", 300L, 300L),
            Case("missing timestamps", "legacy.crash", 0L, 400L),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                CrashRecorder.crashTimestamp(
                    case.fileName,
                    case.lastModified,
                    fallbackNow = 400L,
                ),
            )
        }
    }
}
