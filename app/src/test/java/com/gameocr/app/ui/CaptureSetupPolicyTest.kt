package com.gameocr.app.ui

import com.gameocr.app.capture.CaptureStartMode
import com.gameocr.app.shizuku.ShizukuCapabilities
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSetupPolicyTest {
    @Test
    fun oneButtonSetupKeepsRootShizukuAccessibilitySystemOrder() {
        assertEquals(
            listOf(CaptureStartMode.ROOT, CaptureStartMode.SHIZUKU, CaptureStartMode.ACCESSIBILITY, CaptureStartMode.SYSTEM),
            captureSetupCheckOrder,
        )
        val nn = CaptureMethodCheck.NOT_NEEDED
        val un = CaptureMethodCheck.UNAVAILABLE
        data class Case(
            val name: String,
            val root: Boolean,
            val shizuku: Boolean,
            val sdk: Int,
            val a11y: Boolean,
            val declined: Boolean,
            val resolved: CaptureStartMode?,
            val checks: List<CaptureMethodCheck>,
        )
        listOf(
            Case("granted root wins", true, true, 34, true, false, CaptureStartMode.ROOT,
                listOf(CaptureMethodCheck.SELECTED, nn, nn, nn)),
            Case("usable Shizuku is next", false, true, 34, true, false, CaptureStartMode.SHIZUKU,
                listOf(un, CaptureMethodCheck.SELECTED, nn, nn)),
            Case("old Android skips Accessibility", false, false, 29, true, false, CaptureStartMode.SYSTEM,
                listOf(un, un, CaptureMethodCheck.NEEDS_ANDROID_11, CaptureMethodCheck.SELECTED)),
            Case("enabled Accessibility is next", false, false, 34, true, false, CaptureStartMode.ACCESSIBILITY,
                listOf(un, un, CaptureMethodCheck.SELECTED, nn)),
            Case("off Accessibility falls back without asking", false, false, 34, false, false, CaptureStartMode.SYSTEM,
                listOf(un, un, CaptureMethodCheck.SKIPPED, CaptureMethodCheck.SELECTED)),
            Case("declined Accessibility falls back to System", false, false, 34, false, true, CaptureStartMode.SYSTEM,
                listOf(un, un, CaptureMethodCheck.SKIPPED, CaptureMethodCheck.SELECTED)),
        ).forEach { case ->
            val result = captureSetupMethodResult(null, case.root, case.shizuku, case.sdk, case.a11y)
            assertEquals(case.name, case.resolved, result.resolved)
            assertEquals(case.name, case.checks, captureSetupCheckOrder.map { result.checks.getValue(it) })
        }
    }

    @Test
    fun oneButtonSetupHonoursTheSessionChoiceTheLaunchReads() {
        val nn = CaptureMethodCheck.NOT_NEEDED
        val un = CaptureMethodCheck.UNAVAILABLE
        val sel = CaptureMethodCheck.SELECTED
        data class Case(
            val name: String,
            val preferred: CaptureStartMode,
            val root: Boolean,
            val shizuku: Boolean,
            val a11y: Boolean,
            val declined: Boolean,
            val resolved: CaptureStartMode?,
            val checks: List<CaptureMethodCheck>,
        )
        listOf(
            Case("explicit System skips everything else", CaptureStartMode.SYSTEM, true, true, true, false,
                CaptureStartMode.SYSTEM, listOf(nn, nn, nn, sel)),
            Case("explicit Accessibility beats root and Shizuku", CaptureStartMode.ACCESSIBILITY, true, true, true, false,
                CaptureStartMode.ACCESSIBILITY, listOf(nn, nn, sel, nn)),
            Case("explicit Accessibility that is off falls back", CaptureStartMode.ACCESSIBILITY, false, false, false, false,
                CaptureStartMode.SYSTEM, listOf(nn, nn, CaptureMethodCheck.SKIPPED, sel)),
            Case("declined explicit Accessibility resolves to System for this launch", CaptureStartMode.ACCESSIBILITY,
                false, false, false, true, CaptureStartMode.SYSTEM, listOf(nn, nn, CaptureMethodCheck.SKIPPED, sel)),
            Case("explicit Shizuku skips root", CaptureStartMode.SHIZUKU, true, true, false, false,
                CaptureStartMode.SHIZUKU, listOf(nn, sel, nn, nn)),
            Case("explicit Shizuku that stopped working falls back in order", CaptureStartMode.SHIZUKU, false, false, true, false,
                CaptureStartMode.ACCESSIBILITY, listOf(nn, un, sel, nn)),
            Case("explicit Root that is granted is used", CaptureStartMode.ROOT, true, true, true, false,
                CaptureStartMode.ROOT, listOf(sel, nn, nn, nn)),
            Case("explicit Root that is refused falls back in order", CaptureStartMode.ROOT, false, true, false, false,
                CaptureStartMode.SHIZUKU, listOf(un, sel, nn, nn)),
        ).forEach { case ->
            val result = captureSetupMethodResult(case.preferred, case.root, case.shizuku, 34, case.a11y)
            assertEquals(case.name, case.resolved, result.resolved)
            assertEquals(case.name, case.checks, captureSetupCheckOrder.map { result.checks.getValue(it) })
        }
    }

    @Test
    fun setupDetectsBeforeGrantingAndLaunchesTheResolvedMethodWithoutPrompting() {
        val main = File("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val card = File("src/main/java/com/gameocr/app/ui/CaptureServiceCard.kt").readText()
        val activity = File("src/main/java/com/gameocr/app/capture/CaptureStartRequestActivity.kt").readText()
        val coordinator = File("src/main/java/com/gameocr/app/capture/CaptureStartCoordinator.kt").readText()
        val flow = main.substringAfter("fun continueCaptureSetup()").substringBefore("val applyPresetNow")
        assertTrue(flow.contains("preferred = CaptureStartPreference.mode.value"))
        assertTrue(flow.contains("CaptureStartRequestActivity.newIntent(context, modeOverride = resolved)"))
        assertTrue(activity.contains("coordinator.prepare(modeOverride)"))
        assertTrue(coordinator.contains("val preference = modeOverride ?: CaptureStartPreference.mode.value"))
        assertTrue(!flow.contains("AccessibilityCapturePromptSheet"))
        assertTrue(!card.contains("MethodCheckPill"))
        assertTrue(flow.indexOf("captureSetupMethodResult(") < flow.indexOf("viewModel.grantOverlayViaRoot()"))
        assertTrue(flow.indexOf("captureSetupMethodResult(") < flow.indexOf("openOverlayPermissionSettings(context)"))
    }

    @Test
    fun stoppedShizukuOnlyShowsUnavailableInTheMethodPicker() {
        assertEquals(com.gameocr.app.R.string.main_check_unavailable,
            shizukuHintRes(ShizukuCapabilities.Availability.NOT_RUNNING))
    }

    @Test
    fun setupFlowResumesFromSettingsAndHandsStartToTheCoordinator() {
        val main = File("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        listOf(
            "captureSetupAwaiting = CAPTURE_SETUP_AWAIT_OVERLAY",
            "captureSetupAwaiting = CAPTURE_SETUP_AWAIT_START",
            "if (captureSetupAwaiting != null) captureSetupResumeTick++",
            "viewModel.grantOverlayPermissionViaShizuku()",
            "viewModel.requestShizukuCapture()",
        ).forEach { marker -> assertTrue(marker, main.contains(marker)) }
        val flow = main.substringAfter("fun continueCaptureSetup()").substringBefore("val applyPresetNow")
        assertTrue("start goes through the single coordinator",
            flow.contains("context.startActivity(CaptureStartRequestActivity.newIntent(context, modeOverride = resolved))"))
    }
}
