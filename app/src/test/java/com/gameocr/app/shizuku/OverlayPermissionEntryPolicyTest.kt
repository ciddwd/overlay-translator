package com.gameocr.app.shizuku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPermissionEntryPolicyTest {
    @Test
    fun resolveOverlayPermissionEntryAction_tableDriven_usesShizukuOnlyWhenUsable() {
        data class Case(
            val name: String,
            val overlayGranted: Boolean,
            val availability: ShizukuCapabilities.Availability,
            val expected: OverlayPermissionEntryAction,
        )

        listOf(
            Case("already granted", true, ShizukuCapabilities.Availability.NOT_INSTALLED, OverlayPermissionEntryAction.ALREADY_GRANTED),
            Case("Shizuku ready", false, ShizukuCapabilities.Availability.READY, OverlayPermissionEntryAction.TRY_SHIZUKU),
            Case("Shizuku permission missing", false, ShizukuCapabilities.Availability.INSTALLED_NOT_GRANTED, OverlayPermissionEntryAction.TRY_SHIZUKU),
            Case("shell privilege missing", false, ShizukuCapabilities.Availability.INSTALLED_NOT_PAIRED, OverlayPermissionEntryAction.OPEN_SYSTEM_SETTINGS),
            Case("compatibility bridge missing", false, ShizukuCapabilities.Availability.INSTALLED_COMPATIBILITY_REQUIRED, OverlayPermissionEntryAction.OPEN_SYSTEM_SETTINGS),
            Case("service stopped", false, ShizukuCapabilities.Availability.NOT_RUNNING, OverlayPermissionEntryAction.OPEN_SYSTEM_SETTINGS),
            Case("not installed", false, ShizukuCapabilities.Availability.NOT_INSTALLED, OverlayPermissionEntryAction.OPEN_SYSTEM_SETTINGS),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                resolveOverlayPermissionEntryAction(case.overlayGranted, case.availability),
            )
        }
    }

    @Test
    fun overlayPermissionAppOpsCommand_keepsPackageAsSingleArgument() {
        assertArrayEquals(
            arrayOf(
                "appops",
                "set",
                "--user",
                "current",
                "com.gameocr.app.debug",
                "android:system_alert_window",
                "allow",
            ),
            overlayPermissionAppOpsCommand("com.gameocr.app.debug"),
        )
    }
}
