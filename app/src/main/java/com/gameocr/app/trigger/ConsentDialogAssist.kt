package com.gameocr.app.trigger

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

internal enum class ConsentDialogKind { PROJECTION, RECORD_AUDIO }

/**
 * Decides whether a window is one of the consent dialogs this app's own speech request just
 * opened. Pure, so the scope rules are table-tested.
 */
internal object ConsentDialogPolicy {
    /** AOSP SystemUI hosts the projection dialog; vendors ship it under the same package. */
    val SYSTEM_UI_PACKAGES = setOf("com.android.systemui")
    val PERMISSION_CONTROLLER_PACKAGES = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    /**
     * Matches only while a request is armed and unexpired, only for the two system packages, only
     * when the dialog names this app, and each kind at most once per request.
     */
    fun kindFor(
        packageName: String?,
        windowText: String,
        appLabel: String,
        armedUntilMs: Long,
        nowMs: Long,
        alreadyClicked: Set<ConsentDialogKind>,
    ): ConsentDialogKind? {
        if (nowMs > armedUntilMs || appLabel.isBlank() || !windowText.contains(appLabel)) return null
        val kind = when (packageName) {
            in SYSTEM_UI_PACKAGES -> ConsentDialogKind.PROJECTION
            in PERMISSION_CONTROLLER_PACKAGES -> ConsentDialogKind.RECORD_AUDIO
            else -> return null
        }
        return kind.takeIf { it !in alreadyClicked }
    }

    /** Positive buttons by view id, most specific first (verified against AOSP layouts). */
    fun buttonIds(kind: ConsentDialogKind, packageName: String): List<String> = when (kind) {
        // The system alert dialog (Android 10-13) and screen_share_dialog.xml (14+) both use android:id/button1.
        ConsentDialogKind.PROJECTION -> listOf("android:id/button1")
        // "While using the app"; plain "Allow" on versions without a foreground-only choice.
        ConsentDialogKind.RECORD_AUDIO -> listOf(
            "$packageName:id/permission_allow_foreground_only_button",
            "$packageName:id/permission_allow_button",
        )
    }
}

/**
 * Lets the accessibility service confirm the consent dialogs of the speech request the user just
 * started from the ball. It is armed per request with a deadline and disarmed when the request
 * reports back; unrelated permission dialogs are never touched.
 */
internal object ConsentDialogAssist {
    private class Request(val token: Long, val appLabel: String, val armedUntilMs: Long) {
        val clicked = mutableSetOf<ConsentDialogKind>()
    }

    private val lock = Any()
    private var nextToken = 1L
    private var active: Request? = null

    fun arm(appLabel: String, timeoutMs: Long): Long = synchronized(lock) {
        val token = nextToken++
        active = Request(token, appLabel, SystemClock.elapsedRealtime() + timeoutMs)
        token
    }

    fun disarm(token: Long) = synchronized(lock) {
        if (active?.token == token) active = null
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        val request = synchronized(lock) { active } ?: return
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in ConsentDialogPolicy.SYSTEM_UI_PACKAGES &&
            packageName !in ConsentDialogPolicy.PERMISSION_CONTROLLER_PACKAGES
        ) return
        val root = event.source?.let(::rootOf) ?: service.rootInActiveWindow ?: return
        val kind = ConsentDialogPolicy.kindFor(
            packageName = packageName,
            windowText = collectText(root),
            appLabel = request.appLabel,
            armedUntilMs = request.armedUntilMs,
            nowMs = SystemClock.elapsedRealtime(),
            alreadyClicked = synchronized(lock) { request.clicked.toSet() },
        ) ?: return
        val button = ConsentDialogPolicy.buttonIds(kind, packageName).firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isEnabled && it.isVisibleToUser }
        } ?: return
        val clicked = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) synchronized(lock) { request.clicked += kind }
        Timber.i("[asr-consent-assist] kind=%s clicked=%s", kind, clicked)
    }

    private fun rootOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current = node
        while (true) current = current.parent ?: return current
    }

    /** Visible text of the dialog, bounded so a huge window cannot stall the service. */
    private fun collectText(root: AccessibilityNodeInfo): String {
        val out = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            node.text?.let { out.append(it).append('\n') }
            node.contentDescription?.let { out.append(it).append('\n') }
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
        }
        return out.toString()
    }

    private const val MAX_NODES = 200
}
