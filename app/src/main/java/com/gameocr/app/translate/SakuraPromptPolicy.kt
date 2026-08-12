package com.gameocr.app.translate

import com.gameocr.app.data.RuntimeTranslationPromptContext

/** Builds only prompt variants documented by SakuraLLM v1.0. */
internal object SakuraPromptPolicy {
    const val BASIC_INSTRUCTION = "将下面的日文文本翻译成中文："
    const val GLOSSARY_HEADER = "根据以下术语表（可以为空）："
    const val GLOSSARY_INSTRUCTION = "将下面的日文文本根据对应关系和备注翻译成中文："

    fun build(source: String, context: RuntimeTranslationPromptContext): String {
        val referenceTranslations = linkedMapOf<String, String>()
        context.glossary.forEach { term ->
            val sourceTerm = term.source.toInlineText()
            val targetTerm = term.target.toInlineText()
            if (sourceTerm.isNotEmpty() && targetTerm.isNotEmpty()) {
                referenceTranslations[sourceTerm] = targetTerm
            }
        }
        context.previousFrame.forEach { turn ->
            val previousSource = turn.source.toInlineText()
            val previousTranslation = turn.translation.orEmpty().toInlineText()
            if (
                previousSource.isNotEmpty() &&
                previousTranslation.isNotEmpty() &&
                previousSource !in referenceTranslations
            ) {
                referenceTranslations[previousSource] = previousTranslation
            }
        }
        val glossary = referenceTranslations.map { (sourceTerm, targetTerm) ->
            "$sourceTerm->$targetTerm"
        }
        if (glossary.isEmpty()) return BASIC_INSTRUCTION + source
        return buildString {
            append(GLOSSARY_HEADER)
            append('\n')
            append(glossary.joinToString("\n"))
            append('\n')
            append(GLOSSARY_INSTRUCTION)
            append(source)
        }
    }

    private fun String.toInlineText(): String =
        trim().replace(Regex("[\\r\\n]+"), " ").replace(Regex("\\s{2,}"), " ")
}
