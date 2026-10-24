package com.gameocr.app.onboarding

import android.content.Context

object OnboardingPrefs {
    private const val PREFS_NAME = "onboarding"
    private const val KEY_COMPLETED = "completed_v1"
    private const val KEY_USAGE = "usage_v1"
    private const val KEY_CAPTURE_SETUP_PENDING = "capture_setup_pending_v1"

    // Daily and manga setups share merge settings, so remember the actual choice.
    fun usage(context: Context): OnboardingUsage? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USAGE, null)
        return OnboardingUsage.entries.firstOrNull { it.name == stored }
    }

    fun rememberUsage(context: Context, usage: OnboardingUsage) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USAGE, usage.name)
            .apply()
    }

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        // Seed this before completing onboarding: new users still get the home setup once,
        // while existing completed_v1 users without the new key stay completed on upgrade.
        val captureSetupPending = isCaptureSetupPending(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CAPTURE_SETUP_PENDING, captureSetupPending)
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

    fun isCaptureSetupPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CAPTURE_SETUP_PENDING, !prefs.getBoolean(KEY_COMPLETED, false))
    }

    fun markCaptureSetupCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CAPTURE_SETUP_PENDING, false)
            .apply()
    }
}
