package com.gameocr.app.ui

import com.gameocr.app.download.DownloadSpeedMeter
import com.gameocr.app.download.ModelDownloadProgressText
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloadProgressTest {
    @Test
    fun barFraction_animatesOnlyWhileRunningWithUnknownSize_tableDriven() {
        data class Case(val name: String, val downloaded: Long, val total: Long, val active: Boolean, val expected: Float?)
        listOf(
            Case("unknown total while running is indeterminate", 12L, -1L, true, null),
            Case("zero total while running is indeterminate", 0L, 0L, true, null),
            Case("paused with unknown total never animates", 12L, -1L, false, 0f),
            Case("download just started", 0L, 100L, true, 0f),
            Case("download is halfway", 50L, 100L, true, 0.5f),
            Case("paused keeps its position", 50L, 100L, false, 0.5f),
            Case("download is complete", 100L, 100L, true, 1f),
            Case("negative bytes clamp to zero", -5L, 100L, true, 0f),
            Case("bytes above total clamp to one", 150L, 100L, true, 1f),
        ).forEach { case ->
            assertEquals(case.name, case.expected,
                ModelDownloadProgressText.barFraction(case.downloaded, case.total, case.active))
        }
    }

    @Test
    fun percentAndMegabytes_roundForDisplay_tableDriven() {
        val mb = 1024L * 1024L
        // Rounded down so a download never reads 100% before it finishes.
        assertEquals(41, ModelDownloadProgressText.percent(550 * mb, 1310 * mb))
        assertEquals(null, ModelDownloadProgressText.percent(10, -1))
        assertEquals(100, ModelDownloadProgressText.percent(2_000, 1_000))
        assertEquals(550L, ModelDownloadProgressText.megabytes(550 * mb))
        assertEquals(1L, ModelDownloadProgressText.megabytes(mb / 2))
        assertEquals(0L, ModelDownloadProgressText.megabytes(-1))
    }

    @Test
    fun speedMeter_smoothsAndRestartsOnNewFile() {
        val meter = DownloadSpeedMeter(windowMs = 1_000)
        assertEquals("first sample has no rate", 0L, meter.update(0, 0))
        assertEquals("shorter than the window keeps the old rate", 0L, meter.update(500, 1_000))
        assertEquals("first full window reports the raw rate", 4_000L, meter.update(1_000, 4_000))
        assertEquals("later windows are smoothed", (4_000L * 7 + 2_000L * 3) / 10, meter.update(2_000, 6_000))
        assertEquals("next file restarts the measurement", 0L, meter.update(2_500, 100))
    }
}
