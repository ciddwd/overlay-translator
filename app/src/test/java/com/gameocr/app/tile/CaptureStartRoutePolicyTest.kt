package com.gameocr.app.tile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartRoutePolicyTest {

    private fun input(
        setupCompleted: Boolean = true,
        canDrawOverlay: Boolean = true,
        serviceRunning: Boolean = false,
        isLocked: Boolean = false,
        preferShizuku: Boolean = true,
    ) = CaptureStartRouteInput(
        setupCompleted = setupCompleted,
        canDrawOverlay = canDrawOverlay,
        serviceRunning = serviceRunning,
        isLocked = isLocked,
        preferShizuku = preferShizuku,
    )

    @Test
    fun route_tableDriven_matchesThePriorityOrder() {
        data class Case(
            val name: String,
            val input: CaptureStartRouteInput,
            val expectedRoute: CaptureStartRoute,
            val expectedUseShizuku: Boolean,
        )

        listOf(
            Case(
                "incomplete setup is unavailable",
                input(setupCompleted = false), CaptureStartRoute.UNAVAILABLE, false,
            ),
            Case(
                "incomplete setup wins over a running service",
                input(setupCompleted = false, serviceRunning = true),
                CaptureStartRoute.UNAVAILABLE, false,
            ),
            Case(
                "incomplete setup wins over the lock screen",
                input(setupCompleted = false, isLocked = true),
                CaptureStartRoute.UNAVAILABLE, false,
            ),

            Case(
                "a running service stops when Shizuku is preferred",
                input(serviceRunning = true), CaptureStartRoute.STOP, false,
            ),
            Case(
                "a running service stops on the projection path",
                input(serviceRunning = true, preferShizuku = false), CaptureStartRoute.STOP, false,
            ),
            Case(
                "a running service stops even while locked",
                input(serviceRunning = true, isLocked = true), CaptureStartRoute.STOP, false,
            ),
            Case(
                "a running service stops after the overlay permission was revoked",
                input(serviceRunning = true, canDrawOverlay = false),
                CaptureStartRoute.STOP, false,
            ),

            Case(
                "a missing overlay permission is reported first",
                input(canDrawOverlay = false), CaptureStartRoute.NEEDS_OVERLAY_PERMISSION, false,
            ),
            Case(
                "a missing overlay permission wins over ready Shizuku",
                input(canDrawOverlay = false, preferShizuku = true),
                CaptureStartRoute.NEEDS_OVERLAY_PERMISSION, false,
            ),
            Case(
                "a missing overlay permission wins over the lock screen",
                input(canDrawOverlay = false, isLocked = true),
                CaptureStartRoute.NEEDS_OVERLAY_PERMISSION, false,
            ),

            Case(
                "ready Shizuku starts the foreground service directly",
                input(preferShizuku = true), CaptureStartRoute.DIRECT_FGS, true,
            ),
            Case(
                "ready Shizuku still starts directly while locked",
                input(preferShizuku = true, isLocked = true), CaptureStartRoute.DIRECT_FGS, true,
            ),

            Case(
                "unavailable Shizuku falls back to the projection gateway",
                input(preferShizuku = false), CaptureStartRoute.TRAMPOLINE, false,
            ),
            Case(
                "unavailable Shizuku while locked asks to unlock first",
                input(preferShizuku = false, isLocked = true),
                CaptureStartRoute.BLOCKED_NEED_UNLOCK, false,
            ),
        ).forEach { case ->
            val actual = decideCaptureStartRoute(case.input)
            assertEquals(case.name, case.expectedRoute, actual.route)
            assertEquals(case.name, case.expectedUseShizuku, actual.useShizuku)
        }
    }

    @Test
    fun shizukuUnavailable_mustNotBlockTheTile() {
        // Shizuku is unavailable by default after every reboot for non-root users,
        // so the tile must fall back instead of refusing.
        val decision = decideCaptureStartRoute(input(preferShizuku = false))
        assertTrue(
            "unavailable Shizuku must not yield UNAVAILABLE",
            decision.route != CaptureStartRoute.UNAVAILABLE,
        )
        assertFalse("the fallback must not use Shizuku", decision.useShizuku)
        assertEquals(CaptureStartRoute.TRAMPOLINE, decision.route)
    }

    @Test
    fun missingOverlayPermission_mustNotBlockStop() {
        assertEquals(
            CaptureStartRoute.STOP,
            decideCaptureStartRoute(
                input(serviceRunning = true, canDrawOverlay = false),
            ).route,
        )
        assertEquals(
            CaptureStartRoute.NEEDS_OVERLAY_PERMISSION,
            decideCaptureStartRoute(
                input(serviceRunning = false, canDrawOverlay = false),
            ).route,
        )
    }

    @Test
    fun tileIsAToggle_onServiceRunning() {
        val running = decideCaptureStartRoute(input(serviceRunning = true))
        val idle = decideCaptureStartRoute(input(serviceRunning = false))
        assertEquals(CaptureStartRoute.STOP, running.route)
        assertTrue(
            "an idle service must not yield STOP",
            idle.route != CaptureStartRoute.STOP,
        )
    }

    @Test
    fun useShizuku_onlyWhenEveryPreconditionHolds() {
        listOf(true, false).forEach { setup ->
            listOf(true, false).forEach { overlay ->
                listOf(true, false).forEach { running ->
                    listOf(true, false).forEach { locked ->
                        listOf(true, false).forEach { prefer ->
                            val decision = decideCaptureStartRoute(
                                input(setup, overlay, running, locked, prefer),
                            )
                            val expected = setup && overlay && !running && prefer
                            assertEquals(
                                "setup=$setup overlay=$overlay running=$running " +
                                    "locked=$locked prefer=$prefer",
                                expected,
                                decision.useShizuku,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun lockedOnlyBlocksTheMediaProjectionPath() {
        assertEquals(
            CaptureStartRoute.DIRECT_FGS,
            decideCaptureStartRoute(input(isLocked = true, preferShizuku = true)).route,
        )
        assertEquals(
            CaptureStartRoute.STOP,
            decideCaptureStartRoute(input(serviceRunning = true, isLocked = true)).route,
        )
        assertEquals(
            CaptureStartRoute.BLOCKED_NEED_UNLOCK,
            decideCaptureStartRoute(input(isLocked = true, preferShizuku = false)).route,
        )
        assertEquals(
            CaptureStartRoute.NEEDS_OVERLAY_PERMISSION,
            decideCaptureStartRoute(
                input(isLocked = true, preferShizuku = false, canDrawOverlay = false),
            ).route,
        )
    }
}
