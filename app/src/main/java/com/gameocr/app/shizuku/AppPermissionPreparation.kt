package com.gameocr.app.shizuku

import kotlinx.coroutines.delay

data class AppPermissionResult(val overlayGranted: Boolean, val accessibilityConnected: Boolean)

internal data class AppPermissionState(
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val accessibilityConnected: Boolean,
) {
    val accessibilityReady: Boolean get() = accessibilityEnabled && accessibilityConnected
    val complete: Boolean get() = overlayGranted && accessibilityReady
    fun result() = AppPermissionResult(overlayGranted, accessibilityReady)
}

/** Shared by explicit permission actions and capture startup, under the coordinator's mutex. */
internal suspend fun prepareMissingAppPermissions(
    readState: () -> AppPermissionState,
    ensureShizukuReady: suspend () -> Boolean,
    grantOverlay: suspend () -> Boolean,
    enableAccessibility: suspend () -> Boolean,
    waitForRetry: suspend () -> Unit = { delay(100) },
): AppPermissionResult {
    if (readState().complete || !ensureShizukuReady()) return readState().result()

    // Recheck after authorization and each command. Never rewrite an already-enabled permission.
    // A failed command for one permission must not skip the other permission.
    val overlayAccepted = if (!readState().overlayGranted) grantOverlay() else false
    val accessibilityAccepted = if (!readState().accessibilityEnabled) enableAccessibility() else false

    // Command success is not permission success; wait for actual state and service connection.
    repeat(20) {
        val state = readState()
        if (state.complete) return state.result()
        val overlayPending = !state.overlayGranted && overlayAccepted
        val accessibilityPending = !state.accessibilityReady &&
            (state.accessibilityEnabled || accessibilityAccepted)
        if (!overlayPending && !accessibilityPending) return state.result()
        waitForRetry()
    }
    return readState().result()
}
