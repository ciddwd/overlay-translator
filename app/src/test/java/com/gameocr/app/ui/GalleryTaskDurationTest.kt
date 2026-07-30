package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTaskDurationTest {

    @Test
    fun `task duration uses persisted bounds and safe fallbacks`() {
        data class Case(
            val name: String,
            val startedAtMs: Long?,
            val finishedAtMs: Long?,
            val nowMs: Long,
            val expectedSeconds: Long?,
        )

        listOf(
            Case("queued task has no duration", null, null, 9_000, null),
            Case("running task uses current time", 1_000, null, 6_999, 5),
            Case("finished task ignores current time", 1_000, 8_500, 20_000, 7),
            Case("sub-second duration is zero", 1_000, 1_999, 20_000, 0),
            Case("clock inversion is clamped", 8_000, 7_000, 20_000, 0),
            Case("long duration remains intact", 0, 3_661_000, 9_000_000, 3_661),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedSeconds,
                galleryTaskElapsedSeconds(
                    startedAtMs = case.startedAtMs,
                    finishedAtMs = case.finishedAtMs,
                    nowMs = case.nowMs,
                ),
            )
        }
    }
}
