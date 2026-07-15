package com.gameocr.app.appcontext

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.gameocr.app.data.ForegroundAppDetectionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ForegroundAppSource { ACCESSIBILITY, USAGE_ACCESS }

data class ForegroundApp(
    val packageName: String,
    val displayName: String,
    val observedAtMs: Long,
    val source: ForegroundAppSource,
)

internal fun isUsageAccessModeGranted(mode: Int): Boolean = mode == AppOpsManager.MODE_ALLOWED

internal fun isUsageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    return isUsageAccessModeGranted(
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
    )
}

internal object ForegroundAppSelectionPolicy {
    fun select(
        mode: ForegroundAppDetectionMode,
        accessibility: ForegroundApp?,
        usageAccess: ForegroundApp?,
    ): ForegroundApp? = when (mode) {
        ForegroundAppDetectionMode.AUTO -> listOfNotNull(accessibility, usageAccess)
            .maxByOrNull(ForegroundApp::observedAtMs)
        ForegroundAppDetectionMode.ACCESSIBILITY -> accessibility
        ForegroundAppDetectionMode.USAGE_ACCESS -> usageAccess
        ForegroundAppDetectionMode.DISABLED -> null
    }
}

@Singleton
class ForegroundAppResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile private var accessibilityApp: ForegroundApp? = null

    fun recordAccessibilityPackage(packageName: CharSequence?) {
        val candidate = packageName?.toString().orEmpty()
        if (!isCandidate(candidate)) return
        accessibilityApp = app(candidate, System.currentTimeMillis(), ForegroundAppSource.ACCESSIBILITY)
    }

    suspend fun resolve(mode: ForegroundAppDetectionMode): ForegroundApp? = withContext(Dispatchers.IO) {
        ForegroundAppSelectionPolicy.select(
            mode = mode,
            accessibility = accessibilityApp?.takeIf { isCandidate(it.packageName) },
            usageAccess = if (hasUsageAccess()) latestUsageApp() else null,
        )
    }

    fun hasUsageAccess(): Boolean {
        return isUsageAccessGranted(context)
    }

    private fun latestUsageApp(): ForegroundApp? {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - USAGE_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var latest: ForegroundApp? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            val packageName = event.packageName.orEmpty()
            if (isForeground && isCandidate(packageName)) {
                latest = app(packageName, event.timeStamp, ForegroundAppSource.USAGE_ACCESS)
            }
        }
        return latest
    }

    private fun app(packageName: String, observedAtMs: Long, source: ForegroundAppSource): ForegroundApp {
        val label = runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        return ForegroundApp(packageName, label, observedAtMs, source)
    }

    private fun isCandidate(packageName: String): Boolean {
        if (packageName.isBlank() || packageName == context.packageName) return false
        if (packageName in SYSTEM_PACKAGES) return false
        val launcher = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0,
            )?.activityInfo?.packageName
        }.getOrNull()
        return packageName != launcher
    }

    private companion object {
        const val USAGE_LOOKBACK_MS = 24L * 60L * 60L * 1000L
        val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
        )
    }
}
