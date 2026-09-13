package com.gameocr.app.shizuku

internal enum class OverlayPermissionEntryAction {
    ALREADY_GRANTED,
    TRY_SHIZUKU,
    OPEN_SYSTEM_SETTINGS,
}

/**
 * Keeps the main-screen permission button deterministic. Shizuku is attempted only when its
 * Binder is alive and the only missing step is this app's authorization (or it is fully ready).
 */
internal fun resolveOverlayPermissionEntryAction(
    overlayPermissionGranted: Boolean,
    shizukuAvailability: ShizukuCapabilities.Availability,
): OverlayPermissionEntryAction = when {
    overlayPermissionGranted -> OverlayPermissionEntryAction.ALREADY_GRANTED
    shizukuAvailability == ShizukuCapabilities.Availability.READY ||
        shizukuAvailability == ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED ->
        OverlayPermissionEntryAction.TRY_SHIZUKU
    else -> OverlayPermissionEntryAction.OPEN_SYSTEM_SETTINGS
}

internal fun overlayPermissionAppOpsCommand(packageName: String, user: String = "current"): Array<String> = arrayOf(
    "appops",
    "set",
    "--user",
    user,
    packageName,
    "android:system_alert_window",
    "allow",
)
