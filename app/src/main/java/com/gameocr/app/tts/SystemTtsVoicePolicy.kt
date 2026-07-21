package com.gameocr.app.tts

import java.util.Locale

data class SystemTtsVoiceOption(
    val name: String,
    val localeTag: String,
    val networkConnectionRequired: Boolean,
)

internal fun shouldLoadSystemTtsVoices(
    ttsEnabled: Boolean,
    systemProviderSelected: Boolean,
): Boolean = ttsEnabled && systemProviderSelected

internal fun orderedSystemTtsVoiceOptions(
    voices: Collection<SystemTtsVoiceOption>,
    preferredLanguageTag: String,
): List<SystemTtsVoiceOption> {
    val preferred = preferredLanguageTag
        .trim()
        .takeUnless { it.isEmpty() || it.equals("auto", ignoreCase = true) }
        ?.let(Locale::forLanguageTag)
        ?.takeUnless { it.language.isBlank() || it.language == "und" }

    return voices
        .asSequence()
        .filter { it.name.isNotBlank() }
        .sortedWith(
            compareBy<SystemTtsVoiceOption>(
                { systemTtsVoiceLocaleRank(it.localeTag, preferred) },
                { it.networkConnectionRequired },
                { it.localeTag.lowercase(Locale.ROOT) },
                { it.name.lowercase(Locale.ROOT) },
            )
        )
        .distinctBy { it.name.lowercase(Locale.ROOT) }
        .toList()
}

internal fun selectSystemTtsVoiceForLanguage(
    voices: Collection<SystemTtsVoiceOption>,
    requested: String,
    languageTag: String,
): String? {
    val requestedVoice = requested.trim()
    if (requestedVoice.isBlank()) return null
    val language = Locale.forLanguageTag(languageTag).language
        .takeUnless { it.isBlank() || it == "und" }
    return voices.firstOrNull { voice ->
        voice.name.equals(requestedVoice, ignoreCase = true) && (
            language == null || Locale.forLanguageTag(voice.localeTag).language.equals(
                language,
                ignoreCase = true,
            )
            )
    }?.name
}

private fun systemTtsVoiceLocaleRank(localeTag: String, preferred: Locale?): Int {
    preferred ?: return 2
    val voiceLocale = Locale.forLanguageTag(localeTag)
    return when {
        voiceLocale.toLanguageTag().equals(preferred.toLanguageTag(), ignoreCase = true) -> 0
        voiceLocale.language.equals(preferred.language, ignoreCase = true) -> 1
        else -> 2
    }
}
