package com.gameocr.app.glossary

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranslationGlossaryRepositoryConflictTest {
    @Test
    fun conflictIdentity_cases() = runBlocking {
        val existing = term(id = 7, source = "Hero", target = "勇者")
        val repository = TranslationGlossaryRepository(FakeGlossaryDao(listOf(existing)))
        data class Case(
            val name: String,
            val candidate: GlossaryTermEntity,
            val expectedConflictId: Long?,
        )
        val cases = listOf(
            Case("new exact duplicate", term(source = "hero", target = "英雄"), 7),
            Case("trimmed duplicate", term(source = "  HERO  ", target = "英雄"), 7),
            Case("same record is not a conflict", existing.copy(targetTerm = "英雄"), null),
            Case("different scope", term(source = "Hero", target = "英雄", scope = "game.app"), null),
            Case("different source language", term(source = "Hero", target = "英雄", sourceLang = "ja"), null),
            Case("different target language", term(source = "Hero", target = "Hero", targetLang = "en"), null),
            Case(
                "different case rule",
                term(source = "Hero", target = "英雄", caseSensitive = true),
                null,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedConflictId,
                repository.findConflict(case.candidate)?.id,
            )
        }
    }

    @Test
    fun confirmedOverwrite_cases() = runBlocking {
        data class Case(
            val name: String,
            val initial: List<GlossaryTermEntity>,
            val pending: GlossaryTermEntity,
            val expectedId: Long,
            val expectedTarget: String,
        )
        val duplicate = term(id = 9, source = "Hero", target = "勇者", createdAt = 100)
        val original = term(id = 3, source = "Saber", target = "剑士", createdAt = 50)
        val cases = listOf(
            Case(
                name = "new entry replaces existing duplicate",
                initial = listOf(duplicate),
                pending = term(source = "hero", target = "英雄"),
                expectedId = 9,
                expectedTarget = "英雄",
            ),
            Case(
                name = "edited entry replaces other duplicate without leaving old row",
                initial = listOf(original, duplicate),
                pending = original.copy(sourceTerm = "Hero", targetTerm = "骑士王"),
                expectedId = 3,
                expectedTarget = "骑士王",
            ),
        )

        cases.forEach { case ->
            val dao = FakeGlossaryDao(case.initial)
            val repository = TranslationGlossaryRepository(dao)
            assertEquals(case.name, case.expectedId, repository.overwriteConflict(case.pending))
            val saved = dao.listAll()
            assertEquals(case.name, 1, saved.size)
            assertEquals(case.name, case.expectedId, saved.single().id)
            assertEquals(case.name, case.expectedTarget, saved.single().targetTerm)
            assertNull(case.name, repository.findConflict(saved.single()))
        }
    }

    private fun term(
        id: Long = 0,
        source: String,
        target: String,
        scope: String = "",
        sourceLang: String = "en",
        targetLang: String = "zh-CN",
        caseSensitive: Boolean = false,
        createdAt: Long = 1,
    ) = GlossaryTermEntity(
        id = id,
        scopePackage = scope,
        sourceLang = sourceLang,
        targetLang = targetLang,
        sourceTerm = source,
        normalizedSourceTerm = normalizeGlossaryTerm(source, caseSensitive),
        targetTerm = target,
        caseSensitive = caseSensitive,
        createdAtMs = createdAt,
        updatedAtMs = createdAt,
    )

    private class FakeGlossaryDao(initial: List<GlossaryTermEntity>) : TranslationGlossaryDao {
        private val state = MutableStateFlow(initial)

        override fun observeAll(): Flow<List<GlossaryTermEntity>> = state

        override suspend fun listAll(): List<GlossaryTermEntity> = state.value

        override suspend fun listEnabled(): List<GlossaryTermEntity> = state.value.filter { it.enabled }

        override suspend fun findTerm(
            scopePackage: String,
            sourceLang: String,
            targetLang: String,
            normalizedSourceTerm: String,
            caseSensitive: Boolean,
        ): GlossaryTermEntity? = state.value.firstOrNull {
            it.scopePackage == scopePackage &&
                it.sourceLang == sourceLang &&
                it.targetLang == targetLang &&
                it.normalizedSourceTerm == normalizedSourceTerm &&
                it.caseSensitive == caseSensitive
        }

        override suspend fun insert(term: GlossaryTermEntity): Long {
            val id = term.id.takeIf { it != 0L } ?: ((state.value.maxOfOrNull { it.id } ?: 0L) + 1L)
            state.value = state.value + term.copy(id = id)
            return id
        }

        override suspend fun update(term: GlossaryTermEntity) {
            state.value = state.value.map { if (it.id == term.id) term else it }
        }

        override suspend fun delete(id: Long) {
            state.value = state.value.filterNot { it.id == id }
        }
    }
}
