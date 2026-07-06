package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogPreviewTest {

    @Test
    fun previewLogText_tableDriven() {
        data class Case(
            val name: String,
            val text: String,
            val maxChars: Int,
            val maxLines: Int,
            val expectedText: String,
            val expectedTruncated: Boolean,
            val expectedOmittedChars: Int,
        )

        val cases = listOf(
            Case(
                name = "short text unchanged",
                text = "short log",
                maxChars = 20,
                maxLines = 3,
                expectedText = "short log",
                expectedTruncated = false,
                expectedOmittedChars = 0,
            ),
            Case(
                name = "char limit",
                text = "abcdef",
                maxChars = 3,
                maxLines = 10,
                expectedText = "abc",
                expectedTruncated = true,
                expectedOmittedChars = 3,
            ),
            Case(
                name = "line limit",
                text = "l1\nl2\nl3\nl4",
                maxChars = 100,
                maxLines = 2,
                expectedText = "l1\nl2",
                expectedTruncated = true,
                expectedOmittedChars = 6,
            ),
            Case(
                name = "trims trailing newline",
                text = "l1\nl2\n",
                maxChars = 5,
                maxLines = 10,
                expectedText = "l1\nl2",
                expectedTruncated = true,
                expectedOmittedChars = 1,
            ),
        )

        cases.forEach { case ->
            val actual = previewLogText(case.text, case.maxChars, case.maxLines)
            assertEquals(case.name, case.expectedText, actual.text)
            assertEquals(case.name, case.expectedTruncated, actual.truncated)
            assertEquals(case.name, case.expectedOmittedChars, actual.omittedChars)
        }
    }

    @Test
    fun shouldShareLogAsFile_usesFileForLargeLogsOnly() {
        data class Case(
            val name: String,
            val text: String,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("small text", "a".repeat(128), false),
            Case("large crash export", "a".repeat(300 * 1024), true),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, shouldShareLogAsFile(case.text))
        }
    }

    @Test
    fun nextLogPageLimit_tableDriven() {
        data class Case(
            val name: String,
            val currentLimit: Int,
            val totalCount: Int,
            val pageSize: Int,
            val expected: Int,
        )

        val cases = listOf(
            Case("first page advances", currentLimit = 50, totalCount = 200, pageSize = 50, expected = 100),
            Case("caps at total", currentLimit = 150, totalCount = 180, pageSize = 50, expected = 180),
            Case("already over total", currentLimit = 250, totalCount = 180, pageSize = 50, expected = 180),
            Case("negative current starts from zero", currentLimit = -10, totalCount = 180, pageSize = 50, expected = 50),
            Case("invalid page size loads all", currentLimit = 50, totalCount = 180, pageSize = 0, expected = 180),
            Case("negative total is empty", currentLimit = 50, totalCount = -1, pageSize = 50, expected = 0),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                nextLogPageLimit(case.currentLimit, case.totalCount, case.pageSize)
            )
        }
    }

    @Test
    fun shouldLoadMoreLogs_tableDriven() {
        data class Case(
            val name: String,
            val lastVisibleItemIndex: Int?,
            val displayedCount: Int,
            val totalCount: Int,
            val prefetchThreshold: Int,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(
                name = "near page end loads more",
                lastVisibleItemIndex = 45,
                displayedCount = 50,
                totalCount = 120,
                prefetchThreshold = 6,
                expected = true,
            ),
            Case(
                name = "far from page end waits",
                lastVisibleItemIndex = 20,
                displayedCount = 50,
                totalCount = 120,
                prefetchThreshold = 6,
                expected = false,
            ),
            Case(
                name = "footer visible loads more",
                lastVisibleItemIndex = 50,
                displayedCount = 50,
                totalCount = 120,
                prefetchThreshold = 6,
                expected = true,
            ),
            Case(
                name = "all logs displayed",
                lastVisibleItemIndex = 49,
                displayedCount = 50,
                totalCount = 50,
                prefetchThreshold = 6,
                expected = false,
            ),
            Case(
                name = "no visible items",
                lastVisibleItemIndex = null,
                displayedCount = 50,
                totalCount = 120,
                prefetchThreshold = 6,
                expected = false,
            ),
            Case(
                name = "negative threshold means exact end",
                lastVisibleItemIndex = 49,
                displayedCount = 50,
                totalCount = 120,
                prefetchThreshold = -1,
                expected = false,
            ),
            Case(
                name = "exact end with negative threshold",
                lastVisibleItemIndex = 50,
                displayedCount = 50,
                totalCount = 120,
                prefetchThreshold = -1,
                expected = true,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldLoadMoreLogs(
                    lastVisibleItemIndex = case.lastVisibleItemIndex,
                    displayedCount = case.displayedCount,
                    totalCount = case.totalCount,
                    prefetchThreshold = case.prefetchThreshold
                )
            )
        }
    }

    @Test
    fun calculateImagePreviewSampleSize_tableDriven() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val maxDimension: Int,
            val expected: Int,
        )

        val cases = listOf(
            Case("small image unchanged", width = 320, height = 240, maxDimension = 720, expected = 1),
            Case("miui gallery portrait screenshot", width = 1440, height = 3200, maxDimension = 720, expected = 4),
            Case("wide screenshot", width = 2560, height = 1080, maxDimension = 720, expected = 2),
            Case("invalid dimensions unchanged", width = 0, height = 3200, maxDimension = 720, expected = 1),
            Case("invalid max unchanged", width = 1440, height = 3200, maxDimension = 0, expected = 1),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                calculateImagePreviewSampleSize(case.width, case.height, case.maxDimension)
            )
        }
    }

    @Test
    fun zipImageEntryName_tableDriven() {
        data class Case(
            val name: String,
            val logId: Long,
            val imagePath: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "android png path",
                logId = 42L,
                imagePath = "/storage/emulated/0/Android/data/app/files/capture-1.png",
                expected = "images/42-capture-1.png",
            ),
            Case(
                name = "windows style path",
                logId = 7L,
                imagePath = "C:\\tmp\\capture frame.png",
                expected = "images/7-capture-frame.png",
            ),
            Case(
                name = "unsafe file name",
                logId = 3L,
                imagePath = "/tmp/capture:miui?gallery.png",
                expected = "images/3-capture-miui-gallery.png",
            ),
            Case(
                name = "blank file name fallback",
                logId = 1L,
                imagePath = "/",
                expected = "images/1-image.png",
            ),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, zipImageEntryName(case.logId, case.imagePath))
        }
    }

    @Test
    fun uniqueZipEntryName_tableDriven() {
        data class Case(
            val name: String,
            val preferred: String,
            val initialUsed: Set<String>,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "first use unchanged",
                preferred = "images/1-capture.png",
                initialUsed = emptySet(),
                expected = "images/1-capture.png",
            ),
            Case(
                name = "duplicate with extension",
                preferred = "images/1-capture.png",
                initialUsed = setOf("images/1-capture.png"),
                expected = "images/1-capture-2.png",
            ),
            Case(
                name = "duplicate without extension",
                preferred = "images/1-capture",
                initialUsed = setOf("images/1-capture", "images/1-capture-2"),
                expected = "images/1-capture-3",
            ),
        )

        cases.forEach { case ->
            val used = case.initialUsed.toMutableSet()

            assertEquals(case.name, case.expected, uniqueZipEntryName(case.preferred, used))
            assertTrue(case.name, used.contains(case.expected))
        }
    }

    @Test
    fun defaultPreviewLimits_areEnoughForCrashSummary() {
        val text = (1..120).joinToString("\n") { line -> "line-$line " + "x".repeat(30) }

        val preview = previewLogText(text)

        assertTrue(preview.truncated)
        assertTrue(preview.text.length <= 2 * 1024)
        assertTrue(preview.text.lines().size <= 80)
        assertFalse(preview.text.contains("line-120"))
    }
}
