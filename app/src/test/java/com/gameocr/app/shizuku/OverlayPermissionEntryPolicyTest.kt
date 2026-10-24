package com.gameocr.app.shizuku

import com.gameocr.app.capture.CaptureStartMode
import kotlinx.coroutines.runBlocking

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPermissionEntryPolicyTest {
    @Test
    fun selectedBackendPreparesOverlayOnlyInForegroundWithExistingAuthorization_tableDriven() {
        for (mode in CaptureStartMode.entries) for (granted in listOf(false, true))
            for (availability in ShizukuCapabilities.Availability.entries) for (foreground in listOf(false, true)) {
            assertEquals("$mode/$granted/$availability/$foreground",
                foreground && mode == CaptureStartMode.SHIZUKU && !granted &&
                    availability == ShizukuCapabilities.Availability.READY,
                shouldPrepareSelectedShizukuOverlay(mode, granted, availability, foreground))
        }
    }

    @Test
    fun overlayPreparationVerifiesActualStateAndBoundsRetries_tableDriven() = runBlocking {
        data class Case(val granted: Boolean = false, val ready: Boolean = true,
            val accepted: Boolean = true, val appliesAfter: Int = 0, val expected: Boolean)
        listOf(
            Case(granted = true, ready = false, expected = true),
            Case(ready = false, expected = false),
            Case(accepted = false, expected = false),
            Case(expected = true),
            Case(appliesAfter = 1, expected = true),
            Case(appliesAfter = 20, expected = true),
            Case(appliesAfter = 21, expected = false),
        ).forEach { case ->
            var granted = case.granted
            var commands = 0
            var waits = 0
            val actual = prepareOverlayPermissionIfReady(
                overlayGranted = { granted }, shizukuReady = { case.ready },
                grantOverlay = {
                    commands++
                    if (case.accepted && case.appliesAfter == 0) granted = true
                    case.accepted
                },
                waitForRetry = {
                    waits++
                    if (waits == case.appliesAfter) granted = true
                },
            )
            assertEquals(case.toString(), case.expected, actual)
            assertEquals(case.toString(), if (case.granted || !case.ready) 0 else 1, commands)
            assertEquals(case.toString(), if (case.granted || !case.ready || !case.accepted) 0
                else case.appliesAfter.coerceAtMost(20), waits)
        }
    }

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
