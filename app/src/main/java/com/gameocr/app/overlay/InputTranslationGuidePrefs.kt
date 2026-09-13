package com.gameocr.app.overlay

import android.content.Context

object InputTranslationGuidePrefs {
    private const val PREFS_NAME = "input_translation_guide"
    private const val KEY_COMPLETED = "completed_v1"

    fun shouldShow(context: Context): Boolean = !context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_COMPLETED, false)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }
}
