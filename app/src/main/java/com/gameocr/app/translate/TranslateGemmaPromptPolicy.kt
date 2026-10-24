package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Typed content required by the model's own GGUF template, not a replacement chat template. */
internal object TranslateGemmaPromptPolicy {
    fun languageCode(raw: String): String = when (val code = raw.trim().replace('_', '-').lowercase(Locale.ROOT)) {
        "zh", "zh-cn", "zh-sg", "zh-hans", "zh-hans-cn" -> "zh-Hans"
        "zh-tw", "zh-hk", "zh-mo", "zh-hant", "zh-hant-tw" -> "zh-Hant"
        else -> Locale.forLanguageTag(code).language
    }

    fun build(source: String, settings: Settings): String = buildJsonObject {
        put("source_lang_code", languageCode(settings.sourceLang))
        put("target_lang_code", languageCode(settings.targetLang))
        put("text", source)
        val requirements = buildString {
            if (settings.runtimeLocalModelConfig?.unlocked == true) {
                append(ManagedLocalModelRequestPolicy.withInstructions("", settings))
            }
            append(settings.runtimeTranslationContext.takeIf(String::isNotBlank)?.plus("\n").orEmpty())
            append(GenericLocalPromptPolicy.contextSection(source, settings))
        }.trim()
        if (requirements.isNotEmpty()) put("requirements", requirements)
    }.toString()

    /** Readable preview; native execution renders this instruction from the GGUF itself. */
    fun preview(source: String, sourceLang: String, targetLang: String): String {
        fun name(code: String): String = Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH)
        val from = languageCode(sourceLang)
        val to = languageCode(targetLang)
        return "You are a professional ${name(from)} ($from) to ${name(to)} ($to) translator. " +
            "Your goal is to accurately convey the meaning and nuances of the original ${name(from)} text " +
            "while adhering to ${name(to)} grammar, vocabulary, and cultural sensitivities.\n" +
            "Produce only the ${name(to)} translation, without any additional explanations or commentary. " +
            "Please translate the following ${name(from)} text into ${name(to)}:\n\n\n$source"
    }
}
