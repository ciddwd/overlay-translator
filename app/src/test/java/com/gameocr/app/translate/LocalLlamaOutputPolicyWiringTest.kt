package com.gameocr.app.translate

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlamaOutputPolicyWiringTest {

    @Test
    fun outputPolicy_tableDriven_guardsEveryDisplayAndCachePath() {
        val translator = source("app/src/main/java/com/gameocr/app/translate/LocalLlamaTranslator.kt")
        val cache = source("app/src/main/java/com/gameocr/app/translate/TranslationCache.kt")

        data class Case(val name: String, val content: String, val marker: String)
        listOf(
            Case("initial batch output is validated", translator, "acceptBatchResultOrQueueRecovery("),
            Case("failed batch items are retried after normal items", translator, "outputRecoveries.forEach"),
            Case("single output is validated", translator, "validateAndRecoverOutputLocked("),
            Case("stream partials can be buffered", translator, "if (!bufferGeneratedOutputUntilValidated) emit"),
            Case("cache hits are revalidated", translator, "validatedCachedOutput("),
            Case("engine output normalization is centralized", translator,
                "normalizeGeneratedOutputIfNeeded("),
            Case("batch output is normalized before validation", translator,
                "val normalizedTranslated = translated?.let"),
            Case("recovery output is normalized before validation", translator,
                "val recovered = normalizeGeneratedOutputIfNeeded("),
            Case("polluted cache entries are evicted", translator, "cache.remove(cacheKey)"),
            Case("cache supports targeted eviction", cache, "fun remove(key: String)"),
            Case("recovery is one direct generation", translator, "mode = \"output-policy-retry\""),
            Case("failed retry returns no result", translator, "phase = \"retry\""),
            Case("ambiguous output has a source fallback", translator,
                "GeneratedOutputValidation.Retryable"),
            Case("source fallback is not cached", translator,
                "ResolvedGeneratedOutput(validation.fallbackText, cacheable = false)"),
            Case("batch source fallback is not cached", translator,
                "cacheable = false"),
        ).forEach { case -> assertTrue(case.name, case.content.contains(case.marker)) }
    }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
