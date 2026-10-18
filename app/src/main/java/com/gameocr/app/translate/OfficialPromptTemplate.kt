package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.llm.LlmModelKind

/**
 * The official prompt a translation-tuned model receives, built by the same policies the
 * translators use, with [sourcePlaceholder] in place of the text. Read-only display only.
 */
internal object OfficialPromptTemplate {
    fun render(kind: LlmModelKind, sourceLang: String, targetLang: String, sourcePlaceholder: String): String? {
        val empty = RuntimeTranslationPromptContext()
        return when (kind) {
            LlmModelKind.SAKURA_1_5B_Q4 ->
                SakuraPromptPolicy.SYSTEM_PROMPT + "\n" + SakuraPromptPolicy.build(sourcePlaceholder, empty)
            LlmModelKind.HY_MT2_1_8B_Q4_K_M -> HyMt2PromptPolicy.build(sourcePlaceholder, targetLang, empty)
            LlmModelKind.INDEX_TRANSLATE_2B_Q4_K_M ->
                IndexTranslatePromptPolicy.build(sourcePlaceholder, targetLang, sourceLang, empty)
            LlmModelKind.TRANSLATEGEMMA_4B_Q4_K_M ->
                TranslateGemmaPromptPolicy.preview(sourcePlaceholder, sourceLang, targetLang)
            else -> null
        }
    }
}
