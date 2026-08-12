package com.gameocr.app.data

/** Applies every compatibility and detector constraint associated with Manga OCR settings. */
object MangaOcrSettingsPolicy {
    fun normalize(settings: Settings): Settings =
        MangaOcrModelPolicy.normalize(
            MangaOcrAdvancedSettingsPolicy.normalize(settings)
        )

    fun normalize(preset: TranslationPreset): TranslationPreset =
        MangaOcrModelPolicy.normalize(
            MangaOcrAdvancedSettingsPolicy.normalize(preset)
        )
}
