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
}
