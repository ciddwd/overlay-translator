package com.gameocr.app.ui

import com.gameocr.app.data.Languages
import com.gameocr.app.translate.MlKitLanguagePolicy

internal val DEFAULT_ML_KIT_RECENT_SOURCE_LANGUAGES = listOf("en", "zh-CN", "ja", "ko")

internal fun mlKitRecentSourceLanguages(
    stored: List<String>,
    selected: String? = null,
): List<String> {
    val result = mutableListOf<String>()
    val identities = mutableSetOf<String>()
    sequenceOf(listOfNotNull(selected), stored, DEFAULT_ML_KIT_RECENT_SOURCE_LANGUAGES)
        .flatten()
        .forEach { languageTag ->
            val identity = mlKitLanguageIdentity(languageTag) ?: return@forEach
            if (identities.add(identity)) result += languageTag
        }
    return result.take(DEFAULT_ML_KIT_RECENT_SOURCE_LANGUAGES.size)
}

internal fun mlKitDownloadedPickerLanguageCodes(downloadedLanguageTags: Set<String>): List<String> {
    val downloadedIdentities = downloadedLanguageTags.mapNotNull(::mlKitLanguageIdentity).toSet()
    return Languages.ALL.mapNotNull { language ->
        language.code.takeIf { mlKitLanguageIdentity(it) in downloadedIdentities }
    }
}

internal fun mlKitLanguageTagsMatch(first: String, second: String): Boolean {
    val firstIdentity = mlKitLanguageIdentity(first) ?: return false
    return firstIdentity == mlKitLanguageIdentity(second)
}

private fun mlKitLanguageIdentity(languageTag: String): String? =
    if (MlKitLanguagePolicy.isSupportedLanguageTag(languageTag)) {
        MlKitLanguagePolicy.resolveConfiguredSource(languageTag)
    } else {
        null
    }
