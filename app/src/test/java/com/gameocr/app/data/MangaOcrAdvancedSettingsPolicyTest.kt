package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MangaOcrAdvancedSettingsPolicyTest {

    @Test
    fun effectiveValues_ignoreEveryStoredValueAndReturnZero() {
        val cases = listOf(
            "minimum integer" to Int.MIN_VALUE,
            "negative legacy value" to -1,
            "already zero" to 0,
            "small positive value" to 1,
            "old cluster default" to 32,
            "old crop maximum" to 64,
            "maximum integer" to Int.MAX_VALUE,
        )

        cases.forEach { (name, storedValue) ->
            assertEquals(
                "$name bubble gap",
                0,
                MangaOcrAdvancedSettingsPolicy.effectiveBubbleClusterGap(storedValue),
            )
            assertEquals(
                "$name crop padding",
                0,
                MangaOcrAdvancedSettingsPolicy.effectiveCropPaddingPx(storedValue),
            )
        }
    }

    @Test
    fun normalize_resetsSettingsAndNestedLegacyPresetsWithoutChangingOtherValues() {
        data class Case(val name: String, val gap: Int, val cropPadding: Int)
        val cases = listOf(
            Case("negative values", -7, -3),
            Case("old defaults", 32, 8),
            Case("large values", 999, 777),
        )

        cases.forEachIndexed { index, case ->
            val legacyPreset = TranslationPreset(
                id = "legacy_$index",
                name = case.name,
                model = "preset-model-$index",
                bubbleClusterGap = case.gap,
                mangaOcrCropPaddingPx = case.cropPadding,
                settingsHash = "legacy-hash-$index",
            )
            val settings = Settings(
                model = "settings-model-$index",
                bubbleClusterGap = case.gap,
                mangaOcrCropPaddingPx = case.cropPadding,
                translationPresets = listOf(legacyPreset),
            )

            val normalized = MangaOcrAdvancedSettingsPolicy.normalize(settings)
            val normalizedPreset = normalized.translationPresets.single()

            assertEquals(case.name, 0, normalized.bubbleClusterGap)
            assertEquals(case.name, 0, normalized.mangaOcrCropPaddingPx)
            assertEquals(case.name, settings.model, normalized.model)
            assertEquals(case.name, 0, normalizedPreset.bubbleClusterGap)
            assertEquals(case.name, 0, normalizedPreset.mangaOcrCropPaddingPx)
            assertEquals(case.name, legacyPreset.model, normalizedPreset.model)
            assertNotEquals(case.name, legacyPreset.settingsHash, normalizedPreset.settingsHash)
            assertEquals(
                "$case is idempotent",
                normalized,
                MangaOcrAdvancedSettingsPolicy.normalize(normalized),
            )
        }
    }
}
