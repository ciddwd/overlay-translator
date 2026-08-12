package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationPromptContext
import java.util.Locale

/** Builds Hy-MT2 prompts from the templates published with the official model. */
internal object HyMt2PromptPolicy {
    private val officialLanguageNames = mapOf(
        "zh" to "Chinese",
        "en" to "English",
        "fr" to "French",
        "pt" to "Portuguese",
        "es" to "Spanish",
        "ja" to "Japanese",
        "tr" to "Turkish",
        "ru" to "Russian",
        "ar" to "Arabic",
        "ko" to "Korean",
        "th" to "Thai",
        "it" to "Italian",
        "de" to "German",
        "vi" to "Vietnamese",
        "ms" to "Malay",
        "id" to "Indonesian",
        "tl" to "Filipino",
        "hi" to "Hindi",
        "zh-hant" to "Traditional Chinese",
        "pl" to "Polish",
        "cs" to "Czech",
        "nl" to "Dutch",
        "km" to "Khmer",
        "my" to "Burmese",
        "fa" to "Persian",
        "gu" to "Gujarati",
        "ur" to "Urdu",
        "te" to "Telugu",
        "mr" to "Marathi",
        "he" to "Hebrew",
        "bn" to "Bengali",
        "ta" to "Tamil",
        "uk" to "Ukrainian",
        "bo" to "Tibetan",
        "kk" to "Kazakh",
        "mn" to "Mongolian",
        "ug" to "Uyghur",
        "yue" to "Cantonese",
    )

    fun build(
        source: String,
        targetLang: String,
        context: RuntimeTranslationPromptContext,
    ): String {
        val targetLanguage = targetLanguageName(targetLang)
        val glossaryPrefix = glossaryPrefix(context)
        val background = backgroundInformation(source, context)
        val translationPrompt = if (background.isEmpty()) {
            val outputConstraint = if (glossaryPrefix.isEmpty()) {
                "Note that you should only output the translated result without any additional explanation:"
            } else {
                "Note that you must ONLY output the translated result without any additional explanation:"
            }
            "Translate the following text into $targetLanguage. $outputConstraint\n" +
                source
        } else {
            buildString {
                append("[Background Information]\n")
                append(background)
                append('\n')
                append("Please translate the following text into ")
                append(targetLanguage)
                append(", taking the provided background information into consideration.\n")
                append("[Source Text]\n")
                append(source)
            }
        }
        return glossaryPrefix + translationPrompt
    }

    fun buildWithoutBackground(
        source: String,
        targetLang: String,
        context: RuntimeTranslationPromptContext,
    ): String = build(
        source = source,
        targetLang = targetLang,
        context = context.copy(
            currentApplication = null,
            currentPage = emptyList(),
            previousFrame = emptyList(),
        ),
    )

    fun isBackgroundPrompt(prompt: String): Boolean =
        prompt.contains("[Background Information]\n") && prompt.contains("[Source Text]\n")

    fun normalizeTargetLang(targetLang: String): String {
        val normalized = targetLang.trim()
        return if (normalized.isBlank() || normalized.equals("auto", ignoreCase = true)) {
            "zh-CN"
        } else {
            normalized.replace('_', '-')
        }
    }

    fun targetLanguageName(targetLang: String): String {
        val normalized = normalizeTargetLang(targetLang)
        val normalizedLower = normalized.lowercase(Locale.ROOT)
        return when (normalizedLower) {
            "zh-tw", "zh-hk", "zh-mo", "zh-hant" -> "Traditional Chinese"
            "zh", "zh-cn", "zh-sg", "zh-hans" -> "Chinese"
            else -> officialLanguageNames[normalizedLower]
                ?: officialLanguageNames[normalizedLower.substringBefore('-')]
                ?: Locale.forLanguageTag(normalized)
                    .getDisplayLanguage(Locale.ENGLISH)
                    .takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                ?: normalized
        }
    }

    private fun glossaryPrefix(context: RuntimeTranslationPromptContext): String {
        val terms = context.glossary.mapNotNull { term ->
            val source = term.source.toInlineText()
            val target = term.target.toInlineText()
            if (source.isEmpty() || target.isEmpty()) null else "$source translates to $target"
        }
        if (terms.isEmpty()) return ""
        return buildString {
            append("Reference the following translations:\n")
            append(terms.joinToString("\n"))
            append("\n\n")
        }
    }

    private fun backgroundInformation(
        activeSource: String,
        context: RuntimeTranslationPromptContext,
    ): String {
        val sections = mutableListOf<String>()
        context.currentApplication?.toInlineText()?.takeIf(String::isNotEmpty)?.let { app ->
            sections += "Current application: $app"
        }

        val previousTurns = context.previousFrame.mapNotNull { turn ->
            val source = turn.source.trim()
            if (source.isEmpty()) return@mapNotNull null
            buildString {
                append("Source: ").append(source)
                turn.translation?.trim()?.takeIf(String::isNotEmpty)?.let { translation ->
                    append("\nTranslation: ").append(translation)
                }
            }
        }
        if (previousTurns.isNotEmpty()) {
            sections += buildString {
                append("Previous dialogue:\n")
                append(previousTurns.joinToString("\n\n"))
            }
        }

        val otherCurrentSources = context.currentPage.withoutFirstActiveSource(activeSource)
        if (otherCurrentSources.isNotEmpty()) {
            sections += buildString {
                append("Other text on the current page:\n")
                append(otherCurrentSources.joinToString("\n"))
            }
        }
        return sections.joinToString("\n\n")
    }

    private fun List<String>.withoutFirstActiveSource(activeSource: String): List<String> {
        val active = activeSource.trim()
        var removed = false
        return mapNotNull { candidate ->
            val trimmed = candidate.trim()
            when {
                trimmed.isEmpty() -> null
                !removed && trimmed == active -> {
                    removed = true
                    null
                }
                else -> trimmed
            }
        }
    }

    private fun String.toInlineText(): String =
        trim().replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s{2,}"), " ")
}
