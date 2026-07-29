package com.gameocr.app.appcontext

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageAccessStatusTest {
    @Test
    fun grantedStatus_dependsOnlyOnAllowedAppOpMode() {
        data class Case(val name: String, val mode: Int, val expected: Boolean)

        listOf(
            Case("allowed", AppOpsManager.MODE_ALLOWED, true),
            Case("ignored", AppOpsManager.MODE_IGNORED, false),
            Case("errored", AppOpsManager.MODE_ERRORED, false),
            Case("default", AppOpsManager.MODE_DEFAULT, false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, isUsageAccessModeGranted(case.mode))
        }
    }

    @Test
    fun grantedStatus_fallsBackToActualUsageEventReadability() {
        data class Case(
            val name: String,
            val mode: Int,
            val hasReadableUsageEvents: Boolean,
            val expected: Boolean,
        )

        listOf(
            Case("allowed without events", AppOpsManager.MODE_ALLOWED, false, true),
            Case("default with readable events", AppOpsManager.MODE_DEFAULT, true, true),
            Case("default without events", AppOpsManager.MODE_DEFAULT, false, false),
            Case("ignored with readable events", AppOpsManager.MODE_IGNORED, true, true),
            Case("ignored without events", AppOpsManager.MODE_IGNORED, false, false),
            Case("errored with readable events", AppOpsManager.MODE_ERRORED, true, true),
            Case("errored without events", AppOpsManager.MODE_ERRORED, false, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                isUsageAccessGranted(case.mode, case.hasReadableUsageEvents),
            )
        }
    }
}
