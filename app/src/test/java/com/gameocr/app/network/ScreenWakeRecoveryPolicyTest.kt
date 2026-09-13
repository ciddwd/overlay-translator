package com.gameocr.app.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenWakeRecoveryPolicyTest {
    @Test
    fun initialState_tableDriven_tracksWhetherRecoveryIsAlreadyPending() {
        data class Case(
            val name: String,
            val interactive: Boolean,
            val nowMs: Long,
            val expected: ScreenWakeRecoveryState,
        )

        listOf(
            Case("screen already on", true, 100L, ScreenWakeRecoveryState()),
            Case(
                "service starts while screen off",
                false,
                250L,
                ScreenWakeRecoveryState(screenOffAtElapsedMs = 250L, recoveryPending = true),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                ScreenWakeRecoveryPolicy.initialState(case.interactive, case.nowMs),
            )
        }
    }

    @Test
    fun transition_tableDriven_evictsExactlyOnceForEachOffWakeCycle() {
        data class Case(
            val name: String,
            val initial: ScreenWakeRecoveryState,
            val events: List<Pair<ScreenPowerEvent, Long>>,
            val expectedEvictions: List<Boolean>,
            val expectedDurationMs: Long?,
            val expectedFinal: ScreenWakeRecoveryState,
        )

        val pendingAt100 = ScreenWakeRecoveryState(100L, recoveryPending = true)
        listOf(
            Case(
                name = "normal screen off then on",
                initial = ScreenWakeRecoveryState(),
                events = listOf(ScreenPowerEvent.SCREEN_OFF to 100L, ScreenPowerEvent.SCREEN_ON to 900L),
                expectedEvictions = listOf(false, true),
                expectedDurationMs = 800L,
                expectedFinal = ScreenWakeRecoveryState(),
            ),
            Case(
                name = "user present recovers when screen on was missed",
                initial = pendingAt100,
                events = listOf(ScreenPowerEvent.USER_PRESENT to 400L),
                expectedEvictions = listOf(true),
                expectedDurationMs = 300L,
                expectedFinal = ScreenWakeRecoveryState(),
            ),
            Case(
                name = "screen on and user present do not double evict",
                initial = pendingAt100,
                events = listOf(ScreenPowerEvent.SCREEN_ON to 200L, ScreenPowerEvent.USER_PRESENT to 300L),
                expectedEvictions = listOf(true, false),
                expectedDurationMs = 100L,
                expectedFinal = ScreenWakeRecoveryState(),
            ),
            Case(
                name = "duplicate screen off keeps original sleep start",
                initial = ScreenWakeRecoveryState(),
                events = listOf(
                    ScreenPowerEvent.SCREEN_OFF to 100L,
                    ScreenPowerEvent.SCREEN_OFF to 300L,
                    ScreenPowerEvent.SCREEN_ON to 600L,
                ),
                expectedEvictions = listOf(false, false, true),
                expectedDurationMs = 500L,
                expectedFinal = ScreenWakeRecoveryState(),
            ),
            Case(
                name = "spurious wake while already on is ignored",
                initial = ScreenWakeRecoveryState(),
                events = listOf(ScreenPowerEvent.SCREEN_ON to 100L, ScreenPowerEvent.OTHER to 200L),
                expectedEvictions = listOf(false, false),
                expectedDurationMs = null,
                expectedFinal = ScreenWakeRecoveryState(),
            ),
            Case(
                name = "non monotonic clock is clamped defensively",
                initial = pendingAt100,
                events = listOf(ScreenPowerEvent.SCREEN_ON to 50L),
                expectedEvictions = listOf(true),
                expectedDurationMs = 0L,
                expectedFinal = ScreenWakeRecoveryState(),
            ),
        ).forEach { case ->
            var state = case.initial
            val decisions = case.events.map { (event, nowMs) ->
                ScreenWakeRecoveryPolicy.transition(state, event, nowMs).also {
                    state = it.state
                }
            }

            assertEquals(case.name, case.expectedEvictions, decisions.map { it.evictIdleConnections })
            assertEquals(
                case.name,
                case.expectedDurationMs,
                decisions.lastOrNull { it.evictIdleConnections }?.screenOffDurationMs,
            )
            assertEquals(case.name, case.expectedFinal, state)
        }
    }
}
