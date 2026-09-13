package com.gameocr.app.network

/** Screen lifecycle events relevant to restoring cloud translation connectivity. */
internal enum class ScreenPowerEvent {
    SCREEN_OFF,
    SCREEN_ON,
    USER_PRESENT,
    OTHER,
}

internal data class ScreenWakeRecoveryState(
    val screenOffAtElapsedMs: Long? = null,
    val recoveryPending: Boolean = false,
)

internal data class ScreenWakeRecoveryDecision(
    val state: ScreenWakeRecoveryState,
    val evictIdleConnections: Boolean,
    val screenOffDurationMs: Long? = null,
)

/**
 * Keeps screen broadcasts idempotent. Some ROMs emit SCREEN_ON followed by USER_PRESENT; only the
 * first wake event should clear the idle HTTP connection pool.
 */
internal object ScreenWakeRecoveryPolicy {
    fun initialState(
        isInteractive: Boolean,
        nowElapsedMs: Long,
    ): ScreenWakeRecoveryState = if (isInteractive) {
        ScreenWakeRecoveryState()
    } else {
        ScreenWakeRecoveryState(
            screenOffAtElapsedMs = nowElapsedMs,
            recoveryPending = true,
        )
    }

    fun transition(
        state: ScreenWakeRecoveryState,
        event: ScreenPowerEvent,
        nowElapsedMs: Long,
    ): ScreenWakeRecoveryDecision = when (event) {
        ScreenPowerEvent.SCREEN_OFF -> ScreenWakeRecoveryDecision(
            state = if (state.recoveryPending) {
                state
            } else {
                ScreenWakeRecoveryState(
                    screenOffAtElapsedMs = nowElapsedMs,
                    recoveryPending = true,
                )
            },
            evictIdleConnections = false,
        )

        ScreenPowerEvent.SCREEN_ON,
        ScreenPowerEvent.USER_PRESENT -> if (state.recoveryPending) {
            ScreenWakeRecoveryDecision(
                state = ScreenWakeRecoveryState(),
                evictIdleConnections = true,
                screenOffDurationMs = state.screenOffAtElapsedMs?.let { startedAt ->
                    (nowElapsedMs - startedAt).coerceAtLeast(0L)
                },
            )
        } else {
            ScreenWakeRecoveryDecision(state, evictIdleConnections = false)
        }

        ScreenPowerEvent.OTHER -> ScreenWakeRecoveryDecision(
            state = state,
            evictIdleConnections = false,
        )
    }
}
