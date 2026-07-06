package com.gameocr.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFrameDebugDumpPolicyTest {

    @Test
    fun shouldDumpFrame_tableDriven() {
        data class Case(
            val name: String,
            val debugBuild: Boolean,
            val frameIndex: Int,
            val expected: Boolean
        )

        val cases = listOf(
            Case("release build never dumps", false, 1, false),
            Case("debug first frame dumps", true, 1, true),
            Case("debug max frame dumps", true, CaptureFrameDebugDumpPolicy.MAX_FRAMES_PER_PROCESS, true),
            Case("debug over max skips", true, CaptureFrameDebugDumpPolicy.MAX_FRAMES_PER_PROCESS + 1, false),
            Case("debug zero index skips", true, 0, false)
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                CaptureFrameDebugDumpPolicy.shouldDumpFrame(case.debugBuild, case.frameIndex)
            )
        }
    }

    @Test
    fun fileName_tableDriven() {
        data class Case(
            val name: String,
            val label: String,
            val expected: String
        )

        val cases = listOf(
            Case("plain label", "full", "capture-7-full-1440x3200.png"),
            Case("spaces collapse to dashes", "word select full", "capture-7-word-select-full-1440x3200.png"),
            Case("punctuation is sanitized", "word/select#full", "capture-7-word-select-full-1440x3200.png"),
            Case("blank label falls back", " -- ", "capture-7-frame-1440x3200.png")
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                CaptureFrameDebugDumpPolicy.fileName(
                    diagId = 7,
                    label = case.label,
                    width = 1440,
                    height = 3200
                )
            )
        }
    }
}
