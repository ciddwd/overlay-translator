package com.gameocr.app.tile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 磁贴集成层的接线契约：这些接线点只有真机才能跑，所以把源码当文本读来锁住它们。 */
class CaptureTileWiringTest {

    private val service by lazy { source("src/main/java/com/gameocr/app/tile/CaptureTileService.kt") }
    private val refresh by lazy { source("src/main/java/com/gameocr/app/tile/TileRefresh.kt") }
    private val captureService by lazy {
        source("src/main/java/com/gameocr/app/service/CaptureService.kt")
    }

    @Test
    fun clickDecision_tableDriven_goesThroughThePurePolicies() {
        data class Case(val name: String, val marker: String)

        listOf(
            Case("routing goes through the pure policy", "decideCaptureStartRoute("),
            Case("input carries onboarding completion", "OnboardingPrefs.isCompleted("),
            Case("input carries the overlay permission", "Settings.canDrawOverlays("),
            Case("input carries the running service state", "CaptureServiceState.running"),
            Case("input reuses the TileService built-in isLocked()", "isLocked = isLocked()"),
            Case("input carries the Shizuku readiness probe", "ShizukuCapabilities.Availability.READY"),
            Case("tile display state goes through the pure policy", "resolveCaptureTileState("),
            Case("overload choice goes through the pure policy", "resolveTileLaunchApi("),
            Case("BAL delegation check goes through the pure policy", "needsPendingIntentCreatorOptIn("),
            Case("tap throttle goes through the pure policy", "shouldAcceptTileTrigger("),
            Case("start outcome watch goes through the pure policy", "resolveTilePostClickState("),
            Case("only start routes have an outcome to watch", "routeStartsCaptureAttempt("),
        ).forEach { case ->
            assertTrue("missing `${case.marker}` for: ${case.name}", normalized().contains(case.marker))
        }
    }

    @Test
    fun stopRoute_stopsAndIsNeverThrottled() {
        assertShape(
            "STOP must go through CaptureService.stopIntent",
            "CaptureStartRoute.STOP -> startService(CaptureService.stopIntent(this))",
        )
        assertFalse(
            "STOP must never be throttled",
            normalized().contains("CaptureStartRoute.STOP -> if (acceptStart())"),
        )
    }

    @Test
    fun terminalState_isAppliedOnceAfterTheWholeDispatch() {
        assertShape(
            "the terminal state must be written after the whole route dispatch, not inside a branch: " +
                "a per-branch write is silently skipped whenever a new route forgets to render, which " +
                "is exactly how the tile got stuck grey after a direct start",
            "CaptureStartRoute.UNAVAILABLE -> openMainApp() } " +
                "resolveTileClickState(decision.route, startedDirectly)?.let(::applyTileState)",
        )
        val writings = normalized().split("resolveTileClickState(").size - 1
        assertEquals("the terminal state must be decided in exactly one place", 1, writings)
    }

    @Test
    fun clickHandler_neverRendersFromTheStaleRunningFlagAfterRouting() {
        val block = normalized()
            .substringAfter("override fun onClick()")
            .substringBefore("private fun startCaptureDirect(")
        assertFalse(
            "onClick must not blanket-render from CaptureServiceState: on the stop path the service is " +
                "destroyed asynchronously, so the flag still reads true and the tile would stay ACTIVE forever",
            block.contains("renderTileState()"),
        )
    }

    @Test
    fun throttle_tableDriven_guardsEveryStartRouteButNothingElse() {
        listOf(
            "DIRECT_FGS runs the direct start and records whether it really happened" to
                "CaptureStartRoute.DIRECT_FGS -> if (acceptStart()) " +
                "startedDirectly = startCaptureDirect(decision.useShizuku)",
            "TRAMPOLINE runs the projection gateway" to
                "CaptureStartRoute.TRAMPOLINE -> if (acceptStart()) launchProjectionGateway()",
        ).forEach { (what, shape) ->
            assertShape("$what only after the throttle accepts it", shape)
        }
    }

    @Test
    fun startRoutes_useTheRealCaptureServiceActions() {
        assertShape(
            "the direct start must send ACTION_START",
            "action = CaptureService.ACTION_START",
        )
        assertShape(
            "the direct start must pass the Shizuku decision to the service",
            "putExtra(CaptureService.EXTRA_USE_SHIZUKU, useShizuku)",
        )
        val gateway = normalized()
            .substringAfter("private fun launchProjectionGateway()")
            .substringBefore("private fun openOverlayPermissionSettings()")
        assertTrue(
            "the projection path must reuse the existing permission activity",
            gateway.contains("startActivityFromTile(MediaProjectionRequestActivity.newIntent(this))"),
        )
    }

    @Test
    fun activityLaunch_branchesOnSdkAtTheCallSite() {
        assertShape(
            "API 34+ branch uses the PendingIntent overload",
            "startActivityAndCollapse(pendingIntentFor(launch))",
        )
        assertShape(
            "API <34 branch uses the deprecated Intent overload, suppressed for both the compiler and " +
                "lint (`@Suppress(\"DEPRECATION\")` alone does not cover the lint id)",
            "@Suppress(\"DEPRECATION\") @SuppressLint(\"StartActivityAndCollapseDeprecated\") " +
                "startActivityAndCollapse(launch)",
        )
        assertShape(
            "the overload choice must come from the policy, not an inline SDK_INT comparison",
            "resolveTileLaunchApi(sdkInt) == TileLaunchApi.PENDING_INTENT",
        )
        assertShape(
            "the PendingIntent creator must opt in to delegating BAL, or the launch is silently blocked",
            "setPendingIntentCreatorBackgroundActivityStartMode( ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED )",
        )
        assertShape(
            "lint does not follow indirect checks, so the policy result must be translated for it",
            "@ChecksSdkIntAtLeast(parameter = 0, api = TILE_PENDING_INTENT_MIN_SDK)",
        )
        assertShape(
            "the BAL opt-in needs its own lint translation too, or lint's NewApi check fails the build",
            "@ChecksSdkIntAtLeast(parameter = 0, api = PENDING_INTENT_CREATOR_OPT_IN_MIN_SDK)",
        )
    }

    @Test
    fun tileNeverCallsPlainStartActivity() {
        assertFalse(
            "launching from a tile must go through startActivityAndCollapse",
            normalized().contains("startActivity("),
        )
    }

    @Test
    fun directForegroundStart_hasAFallback() {
        val block = normalized()
            .substringAfter("private fun startCaptureDirect(")
            .substringBefore("private fun launchProjectionGateway(")
        assertTrue("startCaptureDirect must guard with try/catch", block.contains("try {"))
        assertTrue(
            "the fallback must be the MediaProjection gateway",
            block.contains("catch (t: Throwable)") && block.contains("launchProjectionGateway()"),
        )
    }

    @Test
    fun serviceNotifiesTheTileWhenItsStateChanges() {
        assertTrue(
            "TileRefresh must ask the system to re-bind through requestListeningState",
            normalized(refresh).contains("requestListeningState("),
        )
        assertTrue(
            "TileRefresh must target the tile component itself",
            normalized(refresh).contains("CaptureTileService::class.java"),
        )
        // 按行计数必须用保留换行的 code()：normalized() 会把换行也折成空格，lineSequence() 只剩一行。
        val notifications = code(captureService).lineSequence()
            .count { it.contains("requestCaptureTileRefresh(this)") }
        assertEquals(
            "the service must notify the tile on start, on an explicit stop, and on destroy",
            3,
            notifications,
        )
    }

    @Test
    fun explicitStop_flipsTheRunningFlagBeforeDestroyingTheService() {
        val block = normalized(captureService)
            .substringAfter("ACTION_STOP -> {")
            .substringBefore("ACTION_TRIGGER_ONCE")
        assertTrue(
            "the running flag must flip on the stop command itself: onDestroy is not guaranteed to run, " +
                "and a tile tap leaves no second chance to render afterwards",
            block.indexOf("setRunning(false)") in 0 until block.indexOf("stopSelf()"),
        )
    }

    @Test
    fun tileAdded_rendersImmediatelyInsteadOfWaitingForTheFirstTap() {
        assertShape(
            "adding the tile while the service runs must render at once, not leave the system default",
            "override fun onTileAdded() { super.onTileAdded() renderTileState() }",
        )
    }

    @Test
    fun theWatchIsArmedAfterTheTerminalStateIsWritten() {
        assertShape(
            "the tile must keep looking for the start outcome after writing the optimistic state",
            "resolveTileClickState(decision.route, startedDirectly)?.let(::applyTileState) " +
                "if (routeStartsCaptureAttempt(decision.route)) watchStartOutcome()",
        )
    }

    @Test
    fun theStartOutcomeWatchIsBoundedAndReadsTheProcessStateOnly() {
        val watch = normalized()
            .substringAfter("private fun watchStartOutcome()")
            .substringBefore("private fun toast(")
        assertTrue(
            "the watch must poll the in-process state, not ask the system",
            watch.contains("CaptureServiceState.running.value"),
        )
        assertTrue(
            "the watch must compare the stop counter against the value captured at click time, or a " +
                "count left over from an earlier attempt would settle this one",
            watch.contains(
                "stoppedWithoutRunningSinceClick = " +
                    "CaptureServiceState.stoppedWithoutRunning.value > stoppedAtClick",
            ),
        )
        assertTrue(
            "running out of attempts must not write anything — only the policy's verdict does: a wrong " +
                "INACTIVE gets the next tap routed to STOP, killing a service that is still coming up",
            watch.contains(
                "if (--remaining > 0) { watchHandler.postDelayed(this, TILE_START_WATCH_INTERVAL_MS) }",
            ),
        )
        assertTrue(
            "the watch must keep polling after it writes ACTIVE: on the direct path the start looks " +
                "successful for seconds before the dry run fails, so stopping at the first verdict " +
                "leaves the tile parked on ACTIVE",
            watch.contains("watchRunnable = null"),
        )
        assertTrue(
            "a verdict may only be written when it changes, or a successful start would re-render the " +
                "tile every 250ms for the whole window",
            watch.contains(
                "if (verdict != null && verdict != lastWritten) { applyTileState(verdict) lastWritten = verdict }",
            ),
        )
    }

    @Test
    fun theStartOutcomeWatchOutlastsTheServicesSlowestSettlePath() {
        // 直连路径会先报 running=true，之后才由 dry-run 发现起不来 —— 窗口短于服务自己的延迟就永远看不到。
        val attempts = constant("TILE_START_WATCH_ATTEMPTS")
        val intervalMs = constant("TILE_START_WATCH_INTERVAL_MS")
        // 锚定 dry-run 那一段再取延迟：全文取「第一个」的话，将来在它前面新增任何一个
        // `kotlinx.coroutines.delay(NNNL)` 都会让本断言静默比对错目标 —— 而它照样通过。
        val dryRunAnchor = "Shizuku dry-run failed"
        assertTrue(
            "the dry-run failure log moved or was renamed; this check anchors on it to locate the " +
                "service's own delayed stop, so a whole-file fallback would compare the wrong delay",
            captureService.contains(dryRunAnchor),
        )
        val serviceDelayMs = Regex("""kotlinx\.coroutines\.delay\((\d+)L\)""")
            .find(captureService.substringAfter(dryRunAnchor))
            ?.groupValues
            ?.get(1)
            ?.toLong()
            ?: error("the service's delayed stop was not found after the Shizuku dry-run failure")

        assertTrue(
            "the watch window (${attempts * intervalMs}ms) must outlast the service's delayed stop " +
                "(${serviceDelayMs}ms), otherwise a start that fails after that delay is never seen",
            attempts * intervalMs > serviceDelayMs,
        )
    }

    @Test
    fun theWatchIsCancelledWhenTheTileIsDestroyed() {
        val destroy = normalized().substringAfter("override fun onDestroy()")
        assertTrue(
            "the pending poll must be removed on destroy, or it keeps ticking after the tile is gone",
            destroy.contains("watchRunnable?.let(watchHandler::removeCallbacks)"),
        )
    }

    private fun constant(name: String): Long = Regex("""const val $name = (\d+)""")
        .find(code(service))
        ?.groupValues
        ?.get(1)
        ?.toLong()
        ?: error("$name not found in CaptureTileService")

    @Test
    fun serviceSignalsEveryStartFailureThatHasNoOtherExit() {
        assertTrue(
            "both dead ends must hand the user off to the gateway: on the direct path the missing " +
                "projection token has no other source",
            captureService.contains("handOffToProjectionGateway(intent)") &&
                captureService.contains("handOffToProjectionGateway(originalIntent)"),
        )
        assertTrue(
            "the hand-off decision must come from the pure policy",
            normalized(captureService).contains("decideCaptureStartRecovery( CaptureStartRecoveryInput("),
        )
        val destroy = normalized(captureService).substringAfter("override fun onDestroy()")
        assertTrue(
            "the tile can only notice a start that ended without running through this counter: the " +
                "refresh request is swallowed while the panel is open",
            destroy.contains("CaptureServiceState.signalStoppedWithoutRunning()"),
        )
    }

    /** Assert the full "route -> mechanism" shape after collapsing whitespace, not just that a token exists somewhere. */
    private fun assertShape(message: String, shape: String) {
        assertTrue(
            "$message\nexpected shape: $shape",
            normalized().contains(shape),
        )
    }

    private fun normalized(source: String = service): String = code(source).replace(Regex("\\s+"), " ")

    /**
     * 剥掉注释、保留换行：断言读的是**代码**，不是「文件里出现过这串字」。少了这一步，把真实调用注释掉、
     * 只在注释里提一句同名 token，断言照样通过 —— 接线的活性就没人守了。
     */
    private fun code(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
        .replace(Regex("//[^\n]*"), "")

    // Paths are relative to the module root; both candidates cover the repo-root and app/ working directories.
    private fun source(path: String): String = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?.readText()
        ?: error("Source not found: $path")
}
