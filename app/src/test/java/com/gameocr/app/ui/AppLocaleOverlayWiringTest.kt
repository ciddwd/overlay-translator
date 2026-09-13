package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural regression checks; actual window refresh is verified in the normal app. */
class AppLocaleOverlayWiringTest {
    private fun source(path: String) = File("src/main/java/com/gameocr/app/$path.kt").readText()

    @Test fun entryPointsUseAppLocale_tableDriven() {
        listOf(
            "capture/RegionPickerActivity" to "AppLocalePrefs.wrap(newBase)",
            "translate/ProcessTextTranslateActivity" to "AppLocalePrefs.wrap(newBase)",
            "service/CaptureService" to "AppLocalePrefs.live(newBase)",
            "translate/ProcessTextTranslateActivity" to "AppLocalePrefs.live(applicationContext)",
        ).forEach { (path, call) -> assertTrue(path, source(path).contains(call)) }
    }

    @Test fun longLivedWindowsUseLiveResourcesAndObserveChanges_tableDriven() {
        for (name in listOf("WordSelectOverlay", "FloatingButtonManager", "TranslationCardOverlay", "LanguageQuickSwitchOverlay")) {
            val code = source("overlay/$name")
            assertTrue(name, code.contains("AppLocalePrefs.live(context)"))
            assertTrue(name, code.contains("AppLocalePrefs.observe("))
        }
        val locale = source("data/AppLocalePrefs")
        for (expected in listOf("tag != lastTag || config != lastConfig", "registerOnSharedPreferenceChangeListener(listener)",
            "unregisterOnSharedPreferenceChangeListener(listener)", "if (!view.isAttachedToWindow) return")) {
            assertTrue(expected, locale.contains(expected))
        }
    }

    @Test fun refreshPreservesTextAndDoesNotStopSpeech() {
        val card = source("overlay/TranslationCardOverlay")
        for (expected in listOf("val source = currentSource", "val translated = currentTranslation",
            "val word = currentWordResult", "if (!refreshingLocale) onDismissed()")) {
            assertTrue(expected, card.contains(expected))
        }
    }
}
