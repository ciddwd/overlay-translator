package com.gameocr.app.ui

import com.gameocr.app.translate.TranslationMemoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationMemoryListFilterPolicyTest {
    private val entries = listOf(
        entry(
            id = 1,
            appLabel = "Example Game",
            packageName = "com.example.game",
            observed = "OCR no1se",
            corrected = "OCR noise",
            translation = "识别噪声",
            sourceLang = "en",
            targetLang = "zh-CN",
        ),
        entry(
            id = 2,
            appLabel = "Moon Story",
            packageName = "com.moon.story",
            observed = "おかえり",
            corrected = "おかえり",
            translation = "欢迎回来",
            sourceLang = "ja",
            targetLang = "zh-CN",
        ),
        entry(
            id = 3,
            appLabel = "Example Game",
            packageName = "com.example.game",
            observed = "Save complete",
            corrected = "Save complete",
            translation = "保存完成",
            sourceLang = "en",
            targetLang = "zh-TW",
        ),
    )

    @Test
    fun filter_tableDriven_searchesEveryManagedField() {
        data class Case(
            val name: String,
            val query: String,
            val expectedIds: List<Long>,
        )

        listOf(
            Case("blank query", "", listOf(1, 2, 3)),
            Case("observed OCR source", "no1se", listOf(1)),
            Case("corrected source", "OCR noise", listOf(1)),
            Case("translation", "欢迎", listOf(2)),
            Case("app label ignores case", "EXAMPLE GAME", listOf(1, 3)),
            Case("package name", "moon.story", listOf(2)),
            Case("language direction", "en zh-tw", listOf(3)),
            Case("tokens span fields", "example 保存", listOf(3)),
            Case("missing", "not present", emptyList()),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedIds,
                TranslationMemoryListFilterPolicy.filter(entries, case.query)
                    .map(TranslationMemoryEntity::id),
            )
        }
    }

    private fun entry(
        id: Long,
        appLabel: String,
        packageName: String,
        observed: String,
        corrected: String,
        translation: String,
        sourceLang: String,
        targetLang: String,
    ): TranslationMemoryEntity = TranslationMemoryEntity(
        id = id,
        scopePackage = packageName,
        appLabel = appLabel,
        sourceLang = sourceLang,
        targetLang = targetLang,
        observedSource = observed,
        normalizedObservedSource = observed.lowercase(),
        normalizedObservedLength = observed.length,
        correctedSource = corrected,
        normalizedCorrectedSource = corrected.lowercase(),
        normalizedCorrectedLength = corrected.length,
        correctedTranslation = translation,
        createdAtMs = 1,
        updatedAtMs = 2,
        lastUsedAtMs = 3,
        hitCount = 4,
    )
}
