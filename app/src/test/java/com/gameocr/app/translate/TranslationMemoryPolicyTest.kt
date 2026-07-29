package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationMemoryPolicyTest {
    @Test
    fun normalization_tableDriven_handlesWhitespaceCaseAndUnicodeComposition() {
        data class Case(
            val name: String,
            val input: String,
            val expected: String,
        )

        listOf(
            Case("trims and folds case", "  HELLO  ", "hello"),
            Case("collapses mixed whitespace", "one \n\t two", "one two"),
            Case("normalizes composed accents", "Cafe\u0301", "café"),
            Case("keeps punctuation for exact keys", "Ready!", "ready!"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, normalizeTranslationMemorySource(case.input))
        }
    }

    @Test
    fun fuzzySimilarity_tableDriven_isConservativeAroundShortAndSemanticChanges() {
        data class Case(
            val name: String,
            val query: String,
            val candidate: String,
            val expectedMatch: Boolean,
        )

        listOf(
            Case(
                "single OCR character error in a long Japanese line",
                "この世界で生きていく",
                "この世界で生きてゆく",
                true,
            ),
            Case(
                "trailing punctuation OCR difference",
                "Welcome back, commander!",
                "Welcome back, commander.",
                true,
            ),
            Case(
                "short phrases never fuzzy match",
                "はい",
                "はい!",
                false,
            ),
            Case(
                "meaningful word change stays below edit threshold",
                "I will go north now",
                "I will go south now",
                false,
            ),
            Case(
                "large length difference is rejected",
                "Please open the inventory menu",
                "Open inventory",
                false,
            ),
        ).forEach { case ->
            val actual = TranslationMemoryMatcher.similarity(case.query, case.candidate)
            if (case.expectedMatch) {
                assertNotNull(case.name, actual)
                assertTrue(case.name, actual!! in 0.0..1.0)
            } else {
                assertNull(case.name, actual)
            }
        }
    }

    @Test
    fun recallEligibility_tableDriven_preservesNumericPassthrough() {
        data class Case(
            val name: String,
            val source: String,
            val expected: Boolean,
        )

        listOf(
            Case("normal dialogue", "Welcome back", true),
            Case("short term still supports exact recall", "HP", true),
            Case("blank OCR", " \n ", false),
            Case("integer", "189", false),
            Case("formatted numeric value", "-12.5%", false),
            Case("mixed dialogue and number", "Level 12", true),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                isTranslationMemoryRecallEligible(case.source),
            )
        }
    }

    @Test
    fun bestMatch_checksObservedAndCorrectedAliases() {
        val olderObservedMatch = entity(
            id = 1,
            observed = "この世界で生きてゆく",
            corrected = "この世界で生きてゆく",
            translation = "我要在这个世界活下去",
            updatedAtMs = 1,
        )
        val newerCorrectedAliasMatch = entity(
            id = 2,
            observed = "OCR noise that does not match",
            corrected = "この世界で生きていく",
            translation = "我会在这个世界生存下去",
            updatedAtMs = 2,
        )

        val match = TranslationMemoryMatcher.bestMatch(
            normalizedSource = normalizeTranslationMemorySource("この世界で生きていく"),
            candidates = listOf(olderObservedMatch, newerCorrectedAliasMatch),
        )

        assertEquals(2L, match?.entry?.id)
        assertEquals(1.0, match?.similarity ?: 0.0, 0.0001)
    }

    @Test
    fun editedCorrection_tableDriven_normalizesManagedFieldsAndPreservesIdentity() {
        data class Case(
            val name: String,
            val source: String,
            val translation: String,
            val expectedSource: String,
            val expectedNormalizedSource: String,
            val expectedTranslation: String,
        )

        listOf(
            Case(
                name = "trims both values",
                source = "  Welcome back  ",
                translation = "  欢迎回来  ",
                expectedSource = "Welcome back",
                expectedNormalizedSource = "welcome back",
                expectedTranslation = "欢迎回来",
            ),
            Case(
                name = "collapses whitespace only in the match key",
                source = "Line one \n  line two",
                translation = "第一行\n第二行",
                expectedSource = "Line one \n  line two",
                expectedNormalizedSource = "line one line two",
                expectedTranslation = "第一行\n第二行",
            ),
            Case(
                name = "normalizes unicode composition in the match key",
                source = "Cafe\u0301",
                translation = "咖啡馆",
                expectedSource = "Cafe\u0301",
                expectedNormalizedSource = "café",
                expectedTranslation = "咖啡馆",
            ),
        ).forEach { case ->
            val original = entity(
                id = 7,
                observed = "OCR alias",
                corrected = "Old source",
                translation = "Old translation",
                updatedAtMs = 10,
            ).copy(hitCount = 9, lastUsedAtMs = 11)

            val actual = original.withEditedCorrection(
                correctedSource = case.source,
                correctedTranslation = case.translation,
                updatedAtMs = 20,
            )

            assertEquals(case.name, case.expectedSource, actual.correctedSource)
            assertEquals(case.name, case.expectedNormalizedSource, actual.normalizedCorrectedSource)
            assertEquals(case.name, case.expectedTranslation, actual.correctedTranslation)
            assertEquals(case.name, 20L, actual.updatedAtMs)
            assertEquals(case.name, original.observedSource, actual.observedSource)
            assertEquals(case.name, original.hitCount, actual.hitCount)
            assertEquals(case.name, original.lastUsedAtMs, actual.lastUsedAtMs)
        }
    }

    private fun entity(
        id: Long,
        observed: String,
        corrected: String,
        translation: String,
        updatedAtMs: Long,
    ): TranslationMemoryEntity {
        val normalizedObserved = normalizeTranslationMemorySource(observed)
        val normalizedCorrected = normalizeTranslationMemorySource(corrected)
        return TranslationMemoryEntity(
            id = id,
            scopePackage = "game.package",
            appLabel = "Game",
            sourceLang = "ja",
            targetLang = "zh-CN",
            observedSource = observed,
            normalizedObservedSource = normalizedObserved,
            normalizedObservedLength = normalizedObserved.codePointCount(0, normalizedObserved.length),
            correctedSource = corrected,
            normalizedCorrectedSource = normalizedCorrected,
            normalizedCorrectedLength = normalizedCorrected.codePointCount(0, normalizedCorrected.length),
            correctedTranslation = translation,
            createdAtMs = updatedAtMs,
            updatedAtMs = updatedAtMs,
            lastUsedAtMs = updatedAtMs,
        )
    }
}
