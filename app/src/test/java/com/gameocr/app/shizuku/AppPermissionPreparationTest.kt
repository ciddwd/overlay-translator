package com.gameocr.app.shizuku

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AppPermissionPreparationTest {
    private class Fixture(
        var state: AppPermissionState,
        val shizukuReady: Boolean = true,
        val overlaySucceeds: Boolean = true,
        val accessibilitySucceeds: Boolean = true,
        val commandsTakeEffect: Boolean = true,
    ) {
        val calls = mutableListOf<String>()
        var waits = 0
        var onReady: () -> Unit = {}
        var onOverlay: () -> Unit = {}
        var onWait: () -> Unit = {}

        suspend fun run() = prepareMissingAppPermissions(
            readState = { state },
            ensureShizukuReady = { calls += "ready"; onReady(); shizukuReady },
            grantOverlay = {
                calls += "overlay"
                if (overlaySucceeds && commandsTakeEffect) state = state.copy(overlayGranted = true)
                onOverlay()
                overlaySucceeds
            },
            enableAccessibility = {
                calls += "accessibility"
                if (accessibilitySucceeds && commandsTakeEffect) {
                    state = state.copy(accessibilityEnabled = true, accessibilityConnected = true)
                }
                accessibilitySucceeds
            },
            waitForRetry = { waits++; onWait() },
        )
    }

    @Test fun grantsOnlyMissingPermissionsAndDoesNotRepeatThem_tableDriven() = runBlocking {
        for (overlay in listOf(false, true)) for (accessibility in listOf(false, true)) {
            val fixture = Fixture(AppPermissionState(overlay, accessibility, accessibility))
            assertEquals(AppPermissionResult(true, true), fixture.run())
            val expected = buildList {
                if (!overlay || !accessibility) add("ready")
                if (!overlay) add("overlay")
                if (!accessibility) add("accessibility")
            }
            assertEquals("overlay=$overlay accessibility=$accessibility", expected, fixture.calls)
            assertEquals(0, fixture.waits)
            fixture.calls.clear()
            assertEquals(AppPermissionResult(true, true), fixture.run())
            assertTrue("already complete requires no commands or authorization", fixture.calls.isEmpty())
        }
    }

    @Test fun unavailableOrUnauthorizedShizukuDoesNotWriteOrWait_tableDriven() = runBlocking {
        for (overlay in listOf(false, true)) for (enabled in listOf(false, true))
            for (connected in listOf(false, true)) {
            val state = AppPermissionState(overlay, enabled, connected)
            val fixture = Fixture(state, shizukuReady = false)
            assertEquals(state.result(), fixture.run())
            assertEquals(if (state.complete) emptyList<String>() else listOf("ready"), fixture.calls)
            assertEquals("not ready must return immediately", 0, fixture.waits)
        }
    }

    @Test fun oneFailedPermissionDoesNotSkipTheOtherOrClaimSuccess_tableDriven() = runBlocking {
        for (overlaySucceeds in listOf(false, true)) for (accessibilitySucceeds in listOf(false, true)) {
            val fixture = Fixture(AppPermissionState(false, false, false),
                overlaySucceeds = overlaySucceeds, accessibilitySucceeds = accessibilitySucceeds)
            assertEquals(AppPermissionResult(overlaySucceeds, accessibilitySucceeds), fixture.run())
            assertEquals(listOf("ready", "overlay", "accessibility"), fixture.calls)
            assertEquals("failed commands must not cause an idle wait", 0, fixture.waits)
        }
        val acceptedButNotApplied = Fixture(AppPermissionState(false, false, false), commandsTakeEffect = false)
        assertEquals(AppPermissionResult(false, false), acceptedButNotApplied.run())
        assertEquals("a zero command exit code is not proof of permission", 20, acceptedButNotApplied.waits)
    }

    @Test fun enabledButNotConnectedWaitsWithoutRewritingOrTogglingService_tableDriven() = runBlocking {
        for (connectAfter in listOf(1, 19, 20, 21)) {
            val fixture = Fixture(AppPermissionState(true, true, false))
            fixture.onWait = {
                if (fixture.waits == connectAfter) fixture.state = fixture.state.copy(accessibilityConnected = true)
            }
            assertEquals(AppPermissionResult(true, connectAfter <= 20), fixture.run())
            assertEquals(listOf("ready"), fixture.calls)
            assertEquals(connectAfter.coerceAtMost(20), fixture.waits)
        }
        val staleConnection = Fixture(AppPermissionState(true, false, true), shizukuReady = false)
        assertFalse("connection alone is not an enabled service", staleConnection.run().accessibilityConnected)
    }

    @Test fun failedCommandDoesNotPreventWaitingForOtherAcceptedPermission() = runBlocking {
        for (overlaySucceeds in listOf(false, true)) {
            val fixture = Fixture(AppPermissionState(false, false, false),
                overlaySucceeds = overlaySucceeds, accessibilitySucceeds = !overlaySucceeds,
                commandsTakeEffect = false)
            fixture.onWait = {
                fixture.state = AppPermissionState(overlaySucceeds, !overlaySucceeds, !overlaySucceeds)
            }
            assertEquals(AppPermissionResult(overlaySucceeds, !overlaySucceeds), fixture.run())
            assertEquals(1, fixture.waits)
            assertEquals(listOf("ready", "overlay", "accessibility"), fixture.calls)
        }
        val failedAccessibility = Fixture(AppPermissionState(true, false, false), accessibilitySucceeds = false)
        assertEquals(AppPermissionResult(true, false), failedAccessibility.run())
        assertEquals(0, failedAccessibility.waits)
    }

    @Test fun stateIsRecheckedAfterAuthorizationAndBetweenCommands() = runBlocking {
        val duringAuthorization = Fixture(AppPermissionState(false, false, false))
        duringAuthorization.onReady = { duringAuthorization.state = AppPermissionState(true, true, true) }
        assertEquals(AppPermissionResult(true, true), duringAuthorization.run())
        assertEquals(listOf("ready"), duringAuthorization.calls)

        val betweenCommands = Fixture(AppPermissionState(false, false, false))
        betweenCommands.onOverlay = { betweenCommands.state = AppPermissionState(true, true, true) }
        assertEquals(AppPermissionResult(true, true), betweenCommands.run())
        assertEquals(listOf("ready", "overlay"), betweenCommands.calls)
    }

    @Test fun allExplicitAccessibilityEntriesUseTheReadyOnlySharedPath() {
        fun source(path: String) = File("src/main/java/com/gameocr/app/$path").readText()
        for (path in listOf("ui/MainScreen.kt", "ui/SettingsViewModel.kt")) {
            assertTrue(path, source(path).contains(
                "fun enableAccessibilityViaShizuku(): Boolean = appPermissions.configureIfReady().accessibilityConnected"))
        }
        for (path in listOf("ui/MainScreen.kt", "ui/SettingsScreen.kt")) {
            val ui = source(path)
            assertTrue(path, ui.contains("if (!viewModel.enableAccessibilityViaShizuku()) {"))
            assertTrue(path, ui.contains("ACTION_ACCESSIBILITY_SETTINGS"))
        }
        val coordinator = source("shizuku/AppPermissionCoordinator.kt")
        val entry = coordinator.substringAfter("suspend fun configureIfReady(): AppPermissionResult {")
            .substringBefore("private fun hasReadyShizuku")
        assertTrue(entry.contains("if (!hasReadyShizuku()) return currentState().result()"))
        assertTrue(entry.indexOf("if (!hasReadyShizuku())") < entry.indexOf("return configure(requireAlreadyReady = true)"))
        val readyOnly = coordinator.substringAfter("if (requireAlreadyReady) {").substringBefore("} else {")
        assertTrue(readyOnly.contains("verifyShellPrivilegeAsync()"))
        assertTrue(readyOnly.contains("hasReadyShizuku()"))
        assertTrue(coordinator.contains("shizuku.isServiceRunning() && shizuku.hasPermission() && shizuku.shellPrivilegeOk.value"))
        assertFalse("manual entry must not request Shizuku permission", readyOnly.contains("ensureReady()"))
        assertTrue(coordinator.contains("mutex.withLock"))
        assertTrue(coordinator.contains("prepareMissingAppPermissions("))
        assertTrue(source("shizuku/AppPermissionPreparation.kt").contains("waitForRetry: suspend () -> Unit = { delay(100) }"))
    }
}
