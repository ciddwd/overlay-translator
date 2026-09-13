package com.gameocr.app.data

/** Configurable rows are a UI preference, not a restriction on the OCR engines' language support. */
object AutoOcrLanguageListPolicy {
    private val defaults = setOf("zh", "ja", "en", "ko")

    private fun catalog(languages: List<Language>): List<Language> = languages
        .filterNot { AutoOcrSettings.languageKey(it.code) == "auto" }
        .distinctBy { AutoOcrSettings.languageKey(it.code) }

    fun isDefault(code: String): Boolean = AutoOcrSettings.languageKey(code) in defaults

    fun visible(settings: AutoOcrSettings, languages: List<Language> = Languages.ALL): List<Language> {
        val normalized = settings.normalized()
        val selected = defaults + normalized.additionalLanguages
        return catalog(languages).filter { AutoOcrSettings.languageKey(it.code) in selected }
    }

    fun addable(settings: AutoOcrSettings, languages: List<Language> = Languages.ALL): List<Language> {
        val selected = visible(settings, languages).mapTo(mutableSetOf()) { AutoOcrSettings.languageKey(it.code) }
        return catalog(languages).filterNot { AutoOcrSettings.languageKey(it.code) in selected }
    }

    fun add(settings: AutoOcrSettings, language: String): AutoOcrSettings {
        val key = AutoOcrSettings.languageKey(language)
        if (addable(settings).none { AutoOcrSettings.languageKey(it.code) == key }) return settings
        return settings.copy(additionalLanguages = settings.additionalLanguages + key).normalized()
    }

    fun remove(settings: AutoOcrSettings, language: String): AutoOcrSettings {
        val key = AutoOcrSettings.languageKey(language)
        if (key in defaults) return settings
        return settings.copy(
            routes = settings.routes.filterKeys { AutoOcrSettings.languageKey(it) != key },
            additionalLanguages = settings.additionalLanguages.filterNot { AutoOcrSettings.languageKey(it) == key },
        ).normalized()
    }
}
