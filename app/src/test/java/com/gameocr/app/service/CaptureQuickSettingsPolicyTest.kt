package com.gameocr.app.service

import com.gameocr.app.capture.CaptureStartStage
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CaptureQuickSettingsPolicyTest {
    @Test fun tileOnlyDecidesStartOrStop() {
        listOf(false to CaptureQuickSettingsAction.REQUEST_START,
            true to CaptureQuickSettingsAction.STOP_SERVICE).forEach { (running, expected) ->
            assertEquals(expected, resolveCaptureQuickSettingsAction(running))
        }
    }

    @Test fun homeAndTileUseOneEntryAndTileRetainsPlatformBoundaries() {
        val tile = File("src/main/java/com/gameocr/app/service/CaptureQuickSettingsTileService.kt").readText()
        val home = File("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        for (source in listOf(tile, home)) {
            assertTrue(source.contains("CaptureStartRequestActivity.newIntent("))
            assertFalse(source.contains("CaptureService.ACTION_START"))
            assertFalse(source.contains("EXTRA_USE_SHIZUKU"))
            assertFalse(source.contains("createScreenCaptureIntent"))
        }
        for (marker in listOf("unlockAndRun(startAction)", "if (!CaptureServiceState.running.value)",
            "PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE",
            "Build.VERSION_CODES.UPSIDE_DOWN_CAKE", "startActivityAndCollapse(pendingIntent)",
            "CaptureService.stopIntent(this)")) assertTrue(marker, tile.contains(marker))
        assertFalse(tile.contains("shizukuCapabilities.availability"))
    }

    @Test fun tileLaunch_keepsLegacyCallOnlyInPre34Branch() {
        val tile = File("src/main/java/com/gameocr/app/service/CaptureQuickSettingsTileService.kt").readText()
        val launch = tile.substringAfter("private fun launchActivity(").substringBefore("private fun updateTileState")
        assertTrue(launch.contains("if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)"))
        val modern = launch.substringBefore("} else {")
        val legacy = launch.substringAfter("} else {")
        data class Case(val source: String, val required: String, val forbidden: String)
        listOf(
            Case(modern, "startActivityAndCollapse(pendingIntent)", "startActivityAndCollapse(intent."),
            Case(legacy, "startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))", "startActivityAndCollapse(pendingIntent)"),
        ).forEach { case ->
            assertTrue(case.required, case.source.contains(case.required))
            assertFalse(case.forbidden, case.source.contains(case.forbidden))
        }
        assertTrue(tile.contains("@SuppressLint(\"StartActivityAndCollapseDeprecated\")"))
    }

    @Test fun launchHostNavigationTable_preservesCallerExceptWhenSetupIsRequired() {
        val host = File("src/main/java/com/gameocr/app/capture/CaptureStartRequestActivity.kt").readText()
            .replace("\r\n", "\n")
        val dispatch = host.substringAfter("when (stage) {").substringBefore("private fun finishRequest()")
        val stages = CaptureStartStage.entries
        data class Case(val stage: CaptureStartStage, val expected: String, val opensHome: Boolean)
        val cases = listOf(
            Case(CaptureStartStage.WAITING, "Unit", false),
            Case(CaptureStartStage.PROJECTION, "launcher.launch(mpm.createScreenCaptureIntent())", false),
            Case(CaptureStartStage.OVERLAY_PERMISSION, "openOverlayPermissionSettings(this@CaptureStartRequestActivity)", false),
            Case(CaptureStartStage.SETUP, "finishRequest()", true),
            Case(CaptureStartStage.FINISHED, "finishRequest()", false),
        )
        assertEquals(stages.toSet(), cases.map { it.stage }.toSet())
        cases.forEach { case ->
            val branch = dispatch.substringAfter("CaptureStartStage.${case.stage} ->")
                .substringBefore("CaptureStartStage.")
            assertTrue(case.stage.name, branch.contains(case.expected))
            assertEquals(case.stage.name, case.opensHome, branch.contains("MainActivity::class.java"))
            if (case.opensHome) {
                for (flag in listOf("FLAG_ACTIVITY_NEW_TASK", "FLAG_ACTIVITY_CLEAR_TOP", "FLAG_ACTIVITY_SINGLE_TOP")) {
                    assertTrue("setup $flag", branch.contains(flag))
                }
            }
        }
        val resultCallback = host.substringAfter("ActivityResultContracts.StartActivityForResult()")
            .substringBefore("override fun onCreate")
        assertTrue("grant callback is preserved", resultCallback.contains("viewModel.projectionGranted("))
        assertTrue("both grant and denial return to caller", resultCallback.trimEnd().endsWith("finishRequest()\n    }"))
        assertFalse(resultCallback.contains("MainActivity"))
        assertTrue(host.contains("if (isTaskRoot) finishAndRemoveTask() else finish()"))
        assertFalse("never clear another activity's task", host.contains("finishAffinity()"))
        val factory = host.substringAfter("fun newIntent(context: Context)").substringBefore("enum class")
        assertTrue(factory.contains("Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION"))
    }
}
