package com.gameocr.app.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.Configuration
import android.view.View
import java.util.Locale

/**
 * 应用语言（per-app locale）的自管持久化。绕开 [androidx.appcompat.app.AppCompatDelegate]：
 * 在 ComponentActivity + 部分 ROM（如 HyperOS）上 `setApplicationLocales` 持久化不可靠，
 * 重建后 `getApplicationLocales` 又返回空。自己存 BCP-47 tag + attachBaseContext 包装
 * Configuration 是最稳的路径。
 *
 * 空 tag = 跟随系统。
 *
 * Activity 通过 [wrap] 在 `attachBaseContext(newBase)` 中改写 Configuration locale，
 * 这样 Compose 的 `LocalContext.current.resources` 就是目标 locale 的 Resources，
 * `stringResource` 自动读到新语言。
 */
object AppLocalePrefs {
    private const val FILE = "locale_prefs"
    private const val KEY_TAG = "tag"

    /** Long-lived overlay contexts resolve the current app language, not their creation-time language. */
    fun live(base: Context): Context = object : ContextWrapper(base) {
        private var lastTag: String? = null
        private var lastConfig: Configuration? = null
        private var localized: Context = base
        override fun getResources(): Resources {
            val tag = read(base)
            val config = base.resources.configuration
            if (tag != lastTag || config != lastConfig) {
                localized = wrap(base)
                lastTag = tag
                lastConfig = Configuration(config)
            }
            return localized.resources
        }
        override fun getAssets() = resources.assets
    }

    /** Listener lifetime follows its window; queued changes never refresh a detached window. */
    fun observe(view: View, refresh: () -> Unit) {
        val prefs = view.context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        var previous = read(view.context)
        fun update() {
            if (!view.isAttachedToWindow) return
            val current = read(view.context)
            if (current != previous) {
                previous = current
                refresh()
            }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TAG || key == null) view.post { update() }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                prefs.registerOnSharedPreferenceChangeListener(listener)
                update()
            }
            override fun onViewDetachedFromWindow(v: View) {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        })
        if (view.isAttachedToWindow) prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun read(context: Context): String = context
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getString(KEY_TAG, "") ?: ""

    fun write(context: Context, tag: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TAG, tag.trim())
            .apply()
    }

    /**
     * 用持久化的 locale 包装一个 Context（通常是 Activity 的 baseContext）。
     * 空 tag 时原样返回（跟随系统）。
     */
    fun wrap(base: Context): Context {
        val tag = read(base)
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(cfg)
    }
}
