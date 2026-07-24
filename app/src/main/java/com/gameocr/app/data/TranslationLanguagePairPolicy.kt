package com.gameocr.app.data

/**
 * Source and target must describe different languages.
 *
 * Automatic source detection remains valid because "auto" is not a concrete target language.
 */
internal fun translationLanguageCodesConflict(
    sourceLang: String,
    targetLang: String,
): Boolean {
    val source = sourceLang.trim()
    val target = targetLang.trim()
    return source.isNotEmpty() &&
        target.isNotEmpty() &&
        source.equals(target, ignoreCase = true)
}
