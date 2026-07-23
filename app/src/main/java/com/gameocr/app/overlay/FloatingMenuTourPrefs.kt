package com.gameocr.app.overlay

import android.content.Context

object FloatingMenuTourPrefs {
    private const val PREFS_NAME = "floating_menu_tour"
    private const val KEY_COMPLETED = "completed_v1"

    fun shouldShow(context: Context): Boolean =
        !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

    fun reset(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_COMPLETED)
            .apply()
    }
}
