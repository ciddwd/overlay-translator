package com.gameocr.app.appcontext

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageAccessDeviceSmokeTest {

    @Test
    fun enabledSystemUsageAccess_isDetectedByTheApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val hasReadableUsageEvents = usage.queryEvents(
            now - 24L * 60L * 60L * 1000L,
            now,
        ).hasNextEvent()

        assertTrue(
            "package=${context.packageName}, uid=${Process.myUid()}, " +
                "appOpMode=$mode, hasReadableUsageEvents=$hasReadableUsageEvents",
            isUsageAccessGranted(context),
        )
    }
}
