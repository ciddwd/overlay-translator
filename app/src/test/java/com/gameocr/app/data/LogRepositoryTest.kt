package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogRepositoryTest {

    @Test
    fun verbosePolicy_tableDriven_enablesOnlyDebugOrExplicitDeveloperDiagnostics() {
        data class Case(
            val name: String,
            val debugBuild: Boolean,
            val developerOptions: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("normal release", debugBuild = false, developerOptions = false, expected = false),
            Case("release developer diagnostics", debugBuild = false, developerOptions = true, expected = true),
            Case("debug default", debugBuild = true, developerOptions = false, expected = true),
            Case("debug developer diagnostics", debugBuild = true, developerOptions = true, expected = true),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                RuntimeLogPolicy.verboseEnabled(case.debugBuild, case.developerOptions),
            )
        }
    }

    @Test
    fun disabledVerboseLogging_dropsInfoAndPairs_butKeepsWarningsAndErrors() {
        val repo = LogRepository().apply { verboseEnabled = false }

        repo.info(LogRepository.Category.OCR, "normal result")
        repo.pair(LogRepository.Category.TRANSLATE, "source", "translation")
        repo.warn(LogRepository.Category.OCR, "warning")
        repo.error(LogRepository.Category.TRANSLATE, "failure")

        assertEquals(listOf(LogRepository.Level.WARN, LogRepository.Level.ERROR), repo.entries.value.map { it.level })
    }

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

    @Test
    fun entries_preserveOptionalImagePath_tableDriven() {
        data class Case(
            val name: String,
            val write: (LogRepository) -> Unit,
            val expectedImagePath: String?,
        )

        val cases = listOf(
            Case(
                name = "info capture image",
                write = { repo ->
                    repo.info(
                        LogRepository.Category.CAPTURE,
                        "frame dumped",
                        imagePath = "/tmp/capture.png"
                    )
                },
                expectedImagePath = "/tmp/capture.png",
            ),
            Case(
                name = "warn capture image",
                write = { repo ->
                    repo.warn(
                        LogRepository.Category.CAPTURE,
                        "frame suspicious",
                        imagePath = "/tmp/suspicious.png"
                    )
                },
                expectedImagePath = "/tmp/suspicious.png",
            ),
            Case(
                name = "plain text log",
                write = { repo -> repo.info(LogRepository.Category.OCR, "done") },
                expectedImagePath = null,
            ),
        )

        cases.forEach { case ->
            val repo = LogRepository()
            case.write(repo)

            assertEquals(case.name, case.expectedImagePath, repo.entries.value.single().imagePath)
        }
    }

    @Test
    fun entries_preserveExplicitHistoricalTimestamp() {
        val repo = LogRepository()

        repo.error(
            category = LogRepository.Category.CRASH,
            message = "historical crash",
            timestamp = 1_725_000_000_000L,
        )

        val entry = repo.entries.value.single()
        assertEquals(LogRepository.Category.CRASH, entry.category)
        assertEquals(1_725_000_000_000L, entry.timestamp)
    }
}
