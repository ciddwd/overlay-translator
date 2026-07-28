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

internal fun swappedTranslationLanguagePair(
    sourceLang: String,
    targetLang: String,
): Pair<String, String>? {
    val source = sourceLang.trim()
    val target = targetLang.trim()
    if (source.isEmpty() || target.isEmpty()) return null
    if (source.equals(Languages.AUTO.code, ignoreCase = true)) return null
    if (target.equals(Languages.AUTO.code, ignoreCase = true)) return null
    if (translationLanguageCodesConflict(source, target)) return null
    return target to source
}
