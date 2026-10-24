package com.gameocr.app.asr

import com.gameocr.app.appcontext.ForegroundAppResolver
import com.gameocr.app.data.*
import com.gameocr.app.glossary.GlossaryTermCategory
import com.gameocr.app.glossary.GlossaryTermEntity
import com.gameocr.app.glossary.TranslationGlossaryRepository
import com.gameocr.app.glossary.normalizeGlossaryTerm
import javax.inject.Inject
import javax.inject.Singleton

internal data class AsrRecognitionHints(
    val prompt: String = "", val hotwords: List<String> = emptyList(),
    val vocabularyId: String = "",
    val mode: AsrHintMode = AsrHintMode.AUTO, val scopePackage: String? = null,
) {
    fun promptWith(budget: Int = 2000): String {
        val terms = if (mode == AsrHintMode.PROMPT_HOTWORDS) hotwords.joinToString(", ") else ""
        return AsrHintBuilder.utf8Prefix(listOf(terms, prompt).filter(String::isNotBlank).joinToString("\n"), budget)
    }
}

internal class AsrHintSource(@Volatile var current: AsrRecognitionHints = AsrRecognitionHints())

internal object AsrHintBuilder {
    const val MAX_TERMS = 50
    fun utf8Prefix(text: String, maxBytes: Int): String {
        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            val token = String(Character.toChars(point))
            val cost = token.toByteArray(Charsets.UTF_8).size
            if (bytes + cost > maxBytes) break
            out.append(token); bytes += cost; index += Character.charCount(point)
        }
        return out.toString()
    }

    fun build(s: Settings, terms: List<GlossaryTermEntity> = emptyList(), scopePackage: String? = null): AsrRecognitionHints {
        val cap = AsrHintPolicy.capabilities(s)
        val cfg = s.asrHints
        val language = AsrLanguagePolicy.effective(s.asrEngine, s.asrLanguage, s.asrCloudModel,
            s.asrCloudProvider, s.asrCloudConnections).code
        val candidates = mutableListOf<String>()
        if (cap.hotwords) {
            candidates += cfg.hotwords.lineSequence().map(String::trim).filter(String::isNotEmpty)
            if (cfg.useGlossary) candidates += terms.asSequence()
                .filter { it.enabled && it.category != GlossaryTermCategory.PRESERVE_SOURCE }
                .filter { it.scopePackage.isEmpty() || it.scopePackage == scopePackage }
                .filter { language == "auto" || it.sourceLang.equals("auto", true) ||
                    it.sourceLang.substringBefore('-').substringBefore('_').equals(language, true) }
                .sortedWith(compareByDescending<GlossaryTermEntity> { it.scopePackage.isNotEmpty() }
                    .thenByDescending { it.updatedAtMs }.thenBy { it.sourceTerm })
                .map { it.sourceTerm.trim() }.toList()
        }
        val hotwords = candidates.asSequence().filter { word -> word.length in 1..80 &&
            word.none { it.isISOControl() || it in "<>" } &&
            (s.asrCloudProvider != AsrCloudProvider.TENCENT || word.length <= 30 &&
                word.none { it.isWhitespace() || it in ",|" } &&
                (word.any { it.code !in 0x3400..0x9fff } || word.length <= 10))
        }.distinctBy { normalizeGlossaryTerm(it, false) }.take(MAX_TERMS).toList()
        return AsrRecognitionHints(if (cap.prompt) utf8Prefix(cfg.prompt.trim(), 2000) else "", hotwords,
            if (cap.vocabulary) cfg.vocabularyIds[s.asrCloudModel.modelId].orEmpty().trim() else "",
            cap.mode, scopePackage)
    }
}

@Singleton
internal class AsrHintResolver @Inject constructor(
    private val glossary: TranslationGlossaryRepository, private val foreground: ForegroundAppResolver,
) {
    suspend fun scope(s: Settings): String? = foreground.resolve(s.foregroundAppDetectionMode)?.packageName
    suspend fun resolve(s: Settings, scopePackage: String? = null): AsrRecognitionHints =
        AsrHintBuilder.build(s, if (s.asrHints.useGlossary && AsrHintPolicy.capabilities(s).hotwords)
            glossary.listEnabled() else emptyList(), scopePackage)
}
