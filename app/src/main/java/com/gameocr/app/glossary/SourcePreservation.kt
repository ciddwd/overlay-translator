package com.gameocr.app.glossary

import com.gameocr.app.appcontext.ForegroundAppResolver
import com.gameocr.app.data.Settings
import com.gameocr.app.ocr.TextBlock
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

internal data class SourcePreservationPlan(
    val retainedBlocks: List<TextBlock>,
    val retainedIndexes: List<Int>,
    val preservedIndexes: Set<Int>,
)

internal object SourcePreservationMatcher {
    private val safeEdgePunctuation = setOf(
        '。', '！', '？', '!', '?', '…',
        '「', '」', '『', '』', '（', '）', '(', ')',
        '[', ']', '【', '】', '〈', '〉', '《', '》',
    )

    fun preservedIndexes(
        sources: List<String>,
        sourceLang: String,
        packageName: String?,
        terms: List<GlossaryTermEntity>,
        preservationEnabled: Boolean = true,
    ): Set<Int> {
        if (!preservationEnabled || sources.isEmpty()) return emptySet()
        val candidates = terms.asSequence()
            .filter(GlossaryTermEntity::enabled)
            .filter { it.category == GlossaryTermCategory.PRESERVE_SOURCE }
            .filter { it.scopePackage.isEmpty() || it.scopePackage == packageName }
            .filter { languageMatches(it.sourceLang, sourceLang) }
            .toList()
        if (candidates.isEmpty()) return emptySet()

        return sources.indices.filterTo(linkedSetOf()) { index ->
            val source = sources[index]
            candidates.any { term ->
                normalize(source, term.caseSensitive) ==
                    normalize(term.sourceTerm, term.caseSensitive)
            }
        }
    }

    internal fun normalize(value: String, caseSensitive: Boolean): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
            .filterNot(Char::isWhitespace)
            .trim { it in safeEdgePunctuation }
        return if (caseSensitive) normalized else normalized.lowercase(Locale.ROOT)
    }

    private fun languageMatches(termLanguage: String, requestedLanguage: String): Boolean {
        val requested = requestedLanguage.trim()
        if (requested.equals("auto", ignoreCase = true)) return true
        val term = termLanguage.trim()
        return term.equals(requested, ignoreCase = true) ||
            term.substringBefore('-').equals(requested.substringBefore('-'), ignoreCase = true)
    }
}

@Singleton
class SourcePreservationService @Inject constructor(
    private val repository: TranslationGlossaryRepository,
    private val foregroundAppResolver: ForegroundAppResolver,
) {
    /** The OCR-independent inputs: preset seeding, the foreground app, and the enabled terms. */
    internal class Lookup(val packageName: String?, val terms: List<GlossaryTermEntity>)

    /**
     * Loads everything [plan] needs except the OCR text. It reads usage history and the database,
     * so capture starts it beside OCR instead of after it. Failures surface from [plan].
     */
    internal suspend fun lookup(settings: Settings): Lookup? {
        if (!settings.sourcePreservationEnabled) return null
        repository.ensureSourcePreservationPresets()
        val explicitScope = settings.runtimeTranslationScopePackage
        val packageName = explicitScope ?: foregroundAppResolver
            .resolve(settings.foregroundAppDetectionMode)
            ?.packageName
        return Lookup(packageName?.takeIf(String::isNotBlank), repository.listEnabled())
    }

    internal suspend fun plan(blocks: List<TextBlock>, settings: Settings): SourcePreservationPlan =
        plan(blocks, settings) { lookup(settings) }

    internal suspend fun plan(
        blocks: List<TextBlock>,
        settings: Settings,
        lookup: suspend () -> Lookup?,
    ): SourcePreservationPlan {
        if (blocks.isEmpty()) return SourcePreservationPlan(emptyList(), emptyList(), emptySet())
        if (!settings.sourcePreservationEnabled) {
            return SourcePreservationPlan(blocks, blocks.indices.toList(), emptySet())
        }
        return try {
            val resolved = lookup() ?: return SourcePreservationPlan(blocks, blocks.indices.toList(), emptySet())
            val preserved = SourcePreservationMatcher.preservedIndexes(
                sources = blocks.map(TextBlock::text),
                sourceLang = settings.sourceLang,
                packageName = resolved.packageName,
                terms = resolved.terms,
                preservationEnabled = settings.sourcePreservationEnabled,
            )
            val retainedIndexes = blocks.indices.filterNot(preserved::contains)
            SourcePreservationPlan(
                retainedBlocks = retainedIndexes.map(blocks::get),
                retainedIndexes = retainedIndexes,
                preservedIndexes = preserved,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Timber.w(error, "Source preservation lookup failed; translating all OCR blocks")
            SourcePreservationPlan(blocks, blocks.indices.toList(), emptySet())
        }
    }
}
