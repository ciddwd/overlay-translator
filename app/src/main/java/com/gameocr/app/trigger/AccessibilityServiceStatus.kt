package com.gameocr.app.trigger

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.view.accessibility.AccessibilityManager

/** Single source of truth for whether this app's accessibility service is enabled. */
object AccessibilityServiceStatus {
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        if (!manager.isEnabled) return false
        val expected = ComponentName(context, GameOcrAccessibilityService::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { enabled ->
                val service = enabled.resolveInfo?.serviceInfo ?: return@any false
                matchesAccessibilityService(
                    packageName = service.packageName,
                    className = service.name,
                    expectedPackageName = expected.packageName,
                    expectedClassName = expected.className,
                )
            }
    }
}

internal fun matchesAccessibilityService(
    packageName: String?,
    className: String?,
    expectedPackageName: String,
    expectedClassName: String,
): Boolean = packageName == expectedPackageName && className == expectedClassName
