package com.gameocr.app.overlay

import com.gameocr.app.data.Settings
import com.gameocr.app.data.SettingsFieldPolicy
import com.gameocr.app.data.TranslationPresetCatalog
import com.gameocr.app.data.normalizedFloatingButtonAlpha
import java.io.File
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class FloatingButtonOpacityTest {
    @Test fun safeOpacityTableAndLegacyDefault() {
        listOf(-2f to 0.1f, 0f to 0.1f, 0.1f to 0.1f, 0.42f to 0.42f,
            1f to 1f, 2f to 1f, Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f,
            Float.NEGATIVE_INFINITY to 1f).forEach { (value, expected) ->
            assertEquals("$value", expected, normalizedFloatingButtonAlpha(value), 0f)
        }
        assertEquals(1f, SettingsFieldPolicy.decodePortable(JsonObject(emptyMap())).settings.floatingButtonAlpha, 0f)
    }

    @Test fun translationPresetDoesNotOverwriteGlobalButtonOpacity() {
        val preset = TranslationPresetCatalog.fromSettings(
            id = "button_alpha", name = "test", shortName = "test", settings = Settings(),
        )
        for (alpha in listOf(0.1f, 0.5f, 1f)) {
            assertEquals(alpha, preset.applyTo(Settings(floatingButtonAlpha = alpha)).floatingButtonAlpha, 0f)
        }
    }

    @Test fun windowOpacityIsIsolatedFromMenuAndExistingButtonAnimations() {
        val source = File("src/main/java/com/gameocr/app/overlay/FloatingButtonManager.kt").readText()
        val apply = source.substringAfter("fun applyOpacity(value: Float)").substringBefore("/**")
        assertTrue(apply.contains("normalizedFloatingButtonAlpha(value)"))
        assertTrue(apply.contains("if (params.alpha == buttonAlpha) return"))
        assertTrue(apply.contains("params.alpha = buttonAlpha"))
        assertTrue(apply.contains("wm.updateViewLayout(button, params)"))
        assertFalse(apply.contains("animate()"))
        assertFalse(apply.contains("menuParams"))
        assertTrue(source.contains("alpha = buttonAlpha\n") || source.contains("alpha = buttonAlpha\r\n"))
        // Existing interaction animation stays a separate, multiplicative View alpha.
        assertTrue(source.contains("v.animate().alpha(0.75f)"))
        assertTrue(source.contains("v?.alpha = 1.0f"))
        val service = File("src/main/java/com/gameocr/app/service/CaptureService.kt").readText()
        assertTrue(service.contains("floatingButton?.applyOpacity(s.floatingButtonAlpha)"))
        assertTrue(service.contains("it.applyOpacity(settings.floatingButtonAlpha)"))
    }

    @Test fun settingsSliderUsesExistingCardAndPersistenceBoundaries() {
        val ui = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val card = ui.substringAfter("item(key = SectionKeys.FLOATING)")
            .substringBefore("item(key = SectionKeys.ARC_MENU)")
        assertTrue(card.indexOf("value = floatingSize") < card.indexOf("value = floatingAlpha"))
        assertTrue(card.indexOf("value = floatingAlpha") < card.indexOf("settings_search_item_floating_snap"))
        assertTrue(card.contains("valueRange = 0.1f..1f"))
        assertTrue(card.contains("settings_alpha_label_format, (floatingAlpha * 100).roundToInt()"))
        assertEquals(2, Regex("floatingAlpha = normalizedFloatingButtonAlpha\\(s.floatingButtonAlpha\\)").findAll(ui).count())
        assertEquals(2, Regex("floatingButtonAlpha = floatingAlpha,").findAll(ui).count())
        assertTrue(ui.contains("SearchEntry(SectionKeys.FLOATING, R.string.settings_section_floating, R.string.settings_search_item_floating_alpha"))
        assertTrue("floatingButtonAlpha" in SettingsFieldPolicy.portableFieldNames)
    }
}
