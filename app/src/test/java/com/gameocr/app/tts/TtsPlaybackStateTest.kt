package com.gameocr.app.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsPlaybackStateTest {

    @Test
    fun playbackCommand_tableDriven_togglesOnlyTheActiveSection() {
        data class Case(
            val name: String,
            val state: TtsPlaybackState,
            val requestedId: String,
            val expected: TtsPlaybackCommand,
        )

        val activeId = "card:source"
        val cases = listOf(
            Case("idle starts", state(), activeId, TtsPlaybackCommand.START),
            Case("same loading pauses", state(TtsPlaybackPhase.LOADING, activeId), activeId, TtsPlaybackCommand.PAUSE),
            Case("same playing pauses", state(TtsPlaybackPhase.PLAYING, activeId), activeId, TtsPlaybackCommand.PAUSE),
            Case("same paused resumes", state(TtsPlaybackPhase.PAUSED, activeId), activeId, TtsPlaybackCommand.RESUME),
            Case("different loading starts new", state(TtsPlaybackPhase.LOADING, activeId), "card:translation", TtsPlaybackCommand.START),
            Case("different playing starts new", state(TtsPlaybackPhase.PLAYING, activeId), "card:translation", TtsPlaybackCommand.START),
            Case("different paused starts new", state(TtsPlaybackPhase.PAUSED, activeId), "card:dictionary", TtsPlaybackCommand.START),
            Case("stale idle id starts", state(TtsPlaybackPhase.IDLE, activeId), activeId, TtsPlaybackCommand.START),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                ttsPlaybackCommand(case.state, case.requestedId),
            )
        }
    }

    @Test
    fun playbackButtonMode_tableDriven_tracksOnlyItsOwnPlayback() {
        data class Case(
            val name: String,
            val state: TtsPlaybackState,
            val buttonId: String,
            val expected: TtsPlaybackButtonMode,
        )

        val activeId = "card:source"
        val cases = listOf(
            Case("idle speaker", state(), activeId, TtsPlaybackButtonMode.SPEAK),
            Case("loading pause", state(TtsPlaybackPhase.LOADING, activeId), activeId, TtsPlaybackButtonMode.PAUSE),
            Case("playing pause", state(TtsPlaybackPhase.PLAYING, activeId), activeId, TtsPlaybackButtonMode.PAUSE),
            Case("paused resume", state(TtsPlaybackPhase.PAUSED, activeId), activeId, TtsPlaybackButtonMode.RESUME),
            Case("other button during loading", state(TtsPlaybackPhase.LOADING, activeId), "card:translation", TtsPlaybackButtonMode.SPEAK),
            Case("other button during playback", state(TtsPlaybackPhase.PLAYING, activeId), "card:translation", TtsPlaybackButtonMode.SPEAK),
            Case("other button while paused", state(TtsPlaybackPhase.PAUSED, activeId), "card:dictionary", TtsPlaybackButtonMode.SPEAK),
            Case("stale idle id speaker", state(TtsPlaybackPhase.IDLE, activeId), activeId, TtsPlaybackButtonMode.SPEAK),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                ttsPlaybackButtonMode(case.state, case.buttonId),
            )
        }
    }

    @Test
    fun systemResumeOffset_tableDriven_clampsProgressToTheUtterance() {
        data class Case(val name: String, val offset: Int, val expected: Int)

        val text = "Read this text"
        val cases = listOf(
            Case("negative callback", -4, 0),
            Case("not started", 0, 0),
            Case("middle range", 5, 5),
            Case("last character", text.lastIndex, text.lastIndex),
            Case("completed", text.length, text.length),
            Case("invalid overshoot", text.length + 10, text.length),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, systemTtsResumeOffset(text, case.offset))
        }
    }

    @Test
    fun systemTerminalAction_tableDriven_retriesOnlyAnUnstartedFirstAttempt() {
        data class Case(
            val name: String,
            val started: Boolean,
            val attempt: Int,
            val maxAttempts: Int,
            val expected: SystemTtsTerminalAction,
        )

        val cases = listOf(
            Case("initial silent completion retries", false, 1, 2, SystemTtsTerminalAction.RETRY),
            Case("retry silent completion finishes", false, 2, 2, SystemTtsTerminalAction.FINISH),
            Case("started initial completion finishes", true, 1, 2, SystemTtsTerminalAction.FINISH),
            Case("started retry completion finishes", true, 2, 2, SystemTtsTerminalAction.FINISH),
            Case("custom first of three retries", false, 1, 3, SystemTtsTerminalAction.RETRY),
            Case("custom second of three retries", false, 2, 3, SystemTtsTerminalAction.RETRY),
            Case("custom final attempt finishes", false, 3, 3, SystemTtsTerminalAction.FINISH),
            Case("attempt beyond limit finishes", false, 4, 3, SystemTtsTerminalAction.FINISH),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                systemTtsTerminalAction(case.started, case.attempt, case.maxAttempts),
            )
        }
    }

    @Test
    fun coordinator_tableDriven_ignoresLateCallbacksFromReplacedPlayback() {
        data class Case(
            val name: String,
            val staleUpdate: (TtsPlaybackCoordinator, Long) -> Boolean,
        )

        val cases = listOf(
            Case("late playing callback") { coordinator, token ->
                coordinator.transition(token, TtsPlaybackPhase.PLAYING)
            },
            Case("late paused callback") { coordinator, token ->
                coordinator.transition(token, TtsPlaybackPhase.PAUSED)
            },
            Case("late completion callback") { coordinator, token ->
                coordinator.finish(token)
            },
        )

        cases.forEach { case ->
            val coordinator = TtsPlaybackCoordinator()
            val staleToken = coordinator.begin("source", TtsPlaybackBackend.SYSTEM)
            val activeToken = coordinator.begin("translation", TtsPlaybackBackend.HTTP)

            assertEquals(case.name, false, case.staleUpdate(coordinator, staleToken))
            assertEquals(
                case.name,
                TtsPlaybackState(
                    phase = TtsPlaybackPhase.LOADING,
                    playbackId = "translation",
                    backend = TtsPlaybackBackend.HTTP,
                    token = activeToken,
                ),
                coordinator.state.value,
            )
        }
    }

    private fun state(
        phase: TtsPlaybackPhase = TtsPlaybackPhase.IDLE,
        playbackId: String? = null,
    ): TtsPlaybackState = TtsPlaybackState(
        phase = phase,
        playbackId = playbackId,
        backend = playbackId?.let { TtsPlaybackBackend.SYSTEM },
        token = if (playbackId == null) 0L else 1L,
    )
}
