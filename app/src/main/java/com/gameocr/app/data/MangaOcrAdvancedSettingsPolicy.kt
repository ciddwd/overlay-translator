package com.gameocr.app.data

/**
 * Retired Manga OCR tuning values.
 *
 * The serialized fields remain in [Settings] and [TranslationPreset] so older DataStore values,
 * settings bundles, and preset exports can still be decoded. They are intentionally ignored and
 * normalized to zero at every boundary.
 */
object MangaOcrAdvancedSettingsPolicy {
    const val BUBBLE_CLUSTER_GAP: Int = 0
    const val CROP_PADDING_PX: Int = 0

    @Suppress("UNUSED_PARAMETER")
    fun effectiveBubbleClusterGap(storedValue: Int): Int = BUBBLE_CLUSTER_GAP

    @Suppress("UNUSED_PARAMETER")
    fun effectiveCropPaddingPx(storedValue: Int): Int = CROP_PADDING_PX

    fun normalize(settings: Settings): Settings {
        val normalizedPresets = settings.translationPresets.map(::normalize)
        if (
            settings.bubbleClusterGap == BUBBLE_CLUSTER_GAP &&
            settings.mangaOcrCropPaddingPx == CROP_PADDING_PX &&
            normalizedPresets == settings.translationPresets
        ) {
            return settings
        }
        return settings.copy(
            bubbleClusterGap = BUBBLE_CLUSTER_GAP,
            mangaOcrCropPaddingPx = CROP_PADDING_PX,
            translationPresets = normalizedPresets,
        )
    }

    fun normalize(preset: TranslationPreset): TranslationPreset {
        if (
            preset.bubbleClusterGap == BUBBLE_CLUSTER_GAP &&
            preset.mangaOcrCropPaddingPx == CROP_PADDING_PX
        ) {
            return preset
        }
        return TranslationPresetCatalog.fromSettings(
            id = preset.id,
            name = preset.name,
            shortName = preset.shortName,
            settings = preset.applyTo(Settings()),
        )
    }
}
