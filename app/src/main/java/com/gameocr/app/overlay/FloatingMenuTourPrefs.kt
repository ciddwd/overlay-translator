package com.gameocr.app.overlay

import android.content.Context
import android.content.SharedPreferences

object FloatingMenuTourPrefs {
    private const val PREFS_NAME = "floating_menu_tour"
    private const val KEY_COMPLETED = "completed_v1"

    fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun shouldShow(context: Context): Boolean =
        !isCompleted(context)

    fun observeCompletion(
        context: Context,
        onChanged: (Boolean) -> Unit,
    ): () -> Unit {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_COMPLETED) {
                onChanged(preferences.getBoolean(KEY_COMPLETED, false))
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onChanged(preferences.getBoolean(KEY_COMPLETED, false))
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

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
