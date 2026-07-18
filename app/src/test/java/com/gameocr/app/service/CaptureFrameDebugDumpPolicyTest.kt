package com.gameocr.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFrameDebugDumpPolicyTest {

    @Test
    fun shouldDumpFrame_tableDriven() {
        data class Case(
            val name: String,
            val developerOptionsEnabled: Boolean,
            val screenshotSavingEnabled: Boolean,
            val frameIndex: Int,
            val expected: Boolean
        )

        val cases = listOf(
            Case("developer options off", false, true, 1, false),
            Case("screenshot setting off", true, false, 1, false),
            Case("both settings off", false, false, 1, false),
            Case("first enabled frame dumps", true, true, 1, true),
            Case(
                "enabled max frame dumps",
                true,
                true,
                CaptureFrameDebugDumpPolicy.MAX_FRAMES_PER_PROCESS,
                true,
            ),
            Case(
                "enabled over max skips",
                true,
                true,
                CaptureFrameDebugDumpPolicy.MAX_FRAMES_PER_PROCESS + 1,
                false,
            ),
            Case("enabled zero index skips", true, true, 0, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                CaptureFrameDebugDumpPolicy.shouldDumpFrame(
                    case.developerOptionsEnabled,
                    case.screenshotSavingEnabled,
                    case.frameIndex,
                )
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
