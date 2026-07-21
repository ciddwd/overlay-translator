package com.gameocr.app.overlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingWindowDismissalTest {

    @Test
    fun userDismiss_tableDriven_stopsPlaybackBeforeClearAndAlwaysClears() {
        data class Case(
            val name: String,
            val stopEvent: String?,
            val stopFails: Boolean,
            val expectedEvents: List<String>,
            val expectedSuccess: Boolean,
        )

        val cases = listOf(
            Case(
                name = "active playback stops before overlay clear",
                stopEvent = "stop-active",
                stopFails = false,
                expectedEvents = listOf("stop-active", "clear"),
                expectedSuccess = true,
            ),
            Case(
                name = "idle stop remains safe and idempotent",
                stopEvent = "stop-idle",
                stopFails = false,
                expectedEvents = listOf("stop-idle", "clear"),
                expectedSuccess = true,
            ),
            Case(
                name = "overlay still clears when playback stop fails",
                stopEvent = "stop-failed",
                stopFails = true,
                expectedEvents = listOf("stop-failed", "clear"),
                expectedSuccess = false,
            ),
        )

        cases.forEach { case ->
            val events = mutableListOf<String>()
            val result = performPlaybackOverlayDismiss(
                stopPlayback = {
                    case.stopEvent?.let(events::add)
                    if (case.stopFails) error("stop failed")
                },
                clearOverlay = { events += "clear" },
            )

            assertEquals(case.name, case.expectedEvents, events)
            assertEquals(case.name, case.expectedSuccess, result.isSuccess)
        }
    }

    @Test
    fun integrationSmoke_routesBothFloatingRenderPathsButNotProgrammaticClear() {
        val managerSource = sourceFile(
            "app/src/main/java/com/gameocr/app/overlay/OverlayManager.kt",
            "src/main/java/com/gameocr/app/overlay/OverlayManager.kt",
        ).readText()
        val serviceSource = sourceFile(
            "app/src/main/java/com/gameocr/app/service/CaptureService.kt",
            "src/main/java/com/gameocr/app/service/CaptureService.kt",
        ).readText()
        val copyOverlaySource = sourceFile(
            "app/src/main/java/com/gameocr/app/overlay/TranslationBlockCopyOverlay.kt",
            "src/main/java/com/gameocr/app/overlay/TranslationBlockCopyOverlay.kt",
        ).readText()
        val cardSource = sourceFile(
            "app/src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt",
            "src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt",
        ).readText()
        val processTextSource = sourceFile(
            "app/src/main/java/com/gameocr/app/translate/ProcessTextTranslateActivity.kt",
            "src/main/java/com/gameocr/app/translate/ProcessTextTranslateActivity.kt",
        ).readText()

        data class MarkerCase(val name: String, val source: String, val marker: String)

        listOf(
            MarkerCase(
                "capture service stops the shared TTS engine",
                serviceSource,
                "onFloatingWindowDismissed = { ttsEngine.stop() }",
            ),
            MarkerCase(
                "user dismiss handles playback before clearing",
                managerSource,
                "stopPlayback = onFloatingWindowDismissed",
            ),
            MarkerCase(
                "user dismiss clears the visible result",
                managerSource,
                "clearOverlay = ::clear",
            ),
            MarkerCase(
                "translation block panel stops playback when dismissed",
                copyOverlaySource,
                "stopPlayback = onDismissed",
            ),
            MarkerCase(
                "word translation card stops playback when dismissed",
                cardSource,
                "stopPlayback = onDismissed",
            ),
            MarkerCase(
                "capture service wires panel dismissal to the shared TTS engine",
                serviceSource,
                "onDismissed = { ttsEngine.stop() }",
            ),
            MarkerCase(
                "process-text card wires dismissal to its speech engine",
                processTextSource,
                "onDismissed = { speechEngine.stop() }",
            ),
        ).forEach { case ->
            assertTrue(case.name, case.source.contains(case.marker))
        }

        assertEquals(
            "batch and streaming floating windows share the same dismiss handler",
            2,
            Regex("floatingWindow\\.show\\(content, onDismiss = ::handleFloatingWindowUserDismiss\\)")
                .findAll(managerSource)
                .count(),
        )

        val clearStart = managerSource.indexOf("fun clear()")
        assertTrue("clear function must exist", clearStart >= 0)
        val clearBody = managerSource.substring(clearStart)
        assertFalse(
            "programmatic clear must not trigger the user-dismiss TTS callback",
            clearBody.contains("onFloatingWindowDismissed"),
        )
    }

    private fun sourceFile(vararg candidates: String): File =
        candidates.map(::File).firstOrNull(File::isFile)
            ?: error("Source file not found: ${candidates.joinToString()}")
}
