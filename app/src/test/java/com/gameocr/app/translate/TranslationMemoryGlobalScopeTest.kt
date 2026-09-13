package com.gameocr.app.translate

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TranslationMemoryGlobalScopeTest {
    @Test
    fun scopeResolution_tableDriven_usesGlobalWithoutInventingApplicationIds() {
        data class Case(val packageName: String?, val label: String?, val expected: TranslationMemoryScope)
        listOf(
            Case(null, null, TranslationMemoryScope.GLOBAL),
            Case("", "Previous game", TranslationMemoryScope.GLOBAL),
            Case("  ", "Stale label", TranslationMemoryScope.GLOBAL),
            Case("app.a", "Game A", TranslationMemoryScope("app.a", "Game A")),
            Case("app.a", "", TranslationMemoryScope("app.a", "app.a")),
            Case(" app.a ", null, TranslationMemoryScope("app.a", "app.a")),
        ).forEach { case ->
            assertEquals(case.toString(), case.expected, translationMemoryScope(case.packageName, case.label))
        }
    }

    @Test
    fun rememberAndRecall_tableDriven_roundTripsGlobalAndApplicationCorrections() = runBlocking {
        listOf("", "  ", "app.a").forEach { scope ->
            val dao = FakeDao()
            val repository = TranslationMemoryRepository(dao)
            val id = repository.remember("  HELL0  ", " Hello ", "  你好  ", "en", "zh", scope, "Game A")
            val saved = dao.findById(id)!!
            assertEquals(scope.trim(), saved.scopePackage)
            assertEquals(if (scope.isBlank()) "" else "Game A", saved.appLabel)
            assertEquals("你好", saved.correctedTranslation)
            listOf("hell0", "Hello").forEach { source ->
                val match = repository.recall(source, "en", "zh", scope)!!
                assertEquals(TranslationMemoryMatchKind.EXACT, match.kind)
                assertEquals("你好", match.correctedTranslation)
            }
            assertEquals(2L, dao.findById(id)!!.hitCount)
            assertEquals(listOf(scope.trim()), dao.trimmedScopes)
        }
    }

    @Test
    fun recall_tableDriven_preservesApplicationPriorityAndIsolation() = runBlocking {
        data class Case(val name: String, val queryScope: String, val sourceLang: String,
                        val targetLang: String, val savedScopes: List<String>, val expected: String?)
        listOf(
            Case("no app reads global", "", "en", "zh", listOf(""), "global"),
            Case("known app reads global", "app.a", "en", "zh", listOf(""), "global"),
            Case("app overrides global", "app.a", "en", "zh", listOf("app.a", ""), "app.a"),
            Case("other app does not leak", "app.b", "en", "zh", listOf("app.a"), null),
            Case("unidentified does not borrow app", "", "en", "zh", listOf("app.a"), null),
            Case("other app may use global", "app.b", "en", "zh", listOf("app.a", ""), "global"),
            Case("source language isolated", "app.a", "ja", "zh", listOf("app.a", ""), null),
            Case("target language isolated", "app.a", "en", "ko", listOf("app.a", ""), null),
        ).forEach { case ->
            val dao = FakeDao()
            val repository = TranslationMemoryRepository(dao)
            case.savedScopes.forEach { scope ->
                repository.remember("Hello", "Hello", scope.ifEmpty { "global" }, "en", "zh", scope, scope)
            }
            val match = repository.recall("Hello", case.sourceLang, case.targetLang, case.queryScope)
            assertEquals(case.name, case.expected, match?.correctedTranslation)
            assertTrue(case.name, dao.queriedScopes.all { it == case.queryScope || it == "" })
        }
    }

    @Test
    fun fuzzyRecall_tableDriven_supportsGlobalAndPreservesExistingAppPriority() = runBlocking {
        listOf("", "app.a").forEach { scope ->
            val repository = TranslationMemoryRepository(FakeDao())
            repository.remember("Welcome back, commander!", "Welcome back, commander!", "欢迎", "en", "zh", "", "")
            val match = repository.recall("Welcome back, commander.", "en", "zh", scope)!!
            assertEquals(TranslationMemoryMatchKind.FUZZY, match.kind)
            assertEquals("欢迎", match.correctedTranslation)
        }
        val repository = TranslationMemoryRepository(FakeDao())
        repository.remember("Welcome back, commander!", "Welcome back, commander!", "应用校对", "en", "zh", "app.a", "A")
        repository.remember("Welcome back, commander.", "Welcome back, commander.", "全局校对", "en", "zh", "", "")
        assertEquals("应用校对", repository.recall("Welcome back, commander.", "en", "zh", "app.a")!!.correctedTranslation)
    }

    @Test
    fun update_tableDriven_doesNotOverwriteOtherScopesOrLanguages() = runBlocking {
        val dao = FakeDao()
        val repository = TranslationMemoryRepository(dao)
        val globalId = repository.remember("Hello", "Hello", "global", "en", "zh", "", "")
        repository.remember("Hello", "Hello", "app", "en", "zh", "app.a", "A")
        repository.remember("Hello", "Hello", "korean", "en", "ko", "", "")
        assertEquals(globalId, repository.remember("HELLO", "Hello!", "updated", "en", "zh", "", "Stale app"))
        assertEquals(3, dao.state.value.size)
        listOf(Triple("", "zh", "updated"), Triple("app.a", "zh", "app"), Triple("", "ko", "korean")).forEach { (scope, target, value) ->
            assertEquals(value, repository.recall("Hello", "en", target, scope)!!.correctedTranslation)
        }
        assertTrue(repository.updateCorrection(globalId, "Hello!!", "edited"))
        assertEquals("edited", repository.recall("Hello!!", "en", "zh", "app.b")!!.correctedTranslation)
        repository.delete(globalId)
        assertNull(repository.recall("Hello", "en", "zh", "app.b"))
        assertEquals("app", repository.recall("Hello", "en", "zh", "app.a")!!.correctedTranslation)
    }

    @Test
    fun emptyAndInvalidInput_tableDriven_doesNotWriteOrDuplicateGlobalQueries() = runBlocking {
        val dao = FakeDao()
        val repository = TranslationMemoryRepository(dao)
        assertNull(repository.recall(" \n ", "en", "zh", ""))
        assertTrue(dao.queriedScopes.isEmpty())
        assertNull(repository.recall("Hi", "en", "zh", ""))
        assertEquals(listOf(""), dao.queriedScopes)
        listOf(Triple("", "Hello", "你好"), Triple("Hello", " ", "你好"), Triple("Hello", "Hello", "")).forEach { (observed, corrected, translation) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { repository.remember(observed, corrected, translation, "en", "zh", "", "") }
            }
        }
        assertTrue(dao.state.value.isEmpty())
    }

    @Test
    fun storageError_isNotMistakenForMissingApplicationOrSuccessfulSave() {
        val dao = FakeDao().apply { failWrites = true }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { TranslationMemoryRepository(dao).remember("Hi", "Hi", "你好", "en", "zh", "", "") }
        }
        assertTrue(dao.state.value.isEmpty())
    }

    @Test
    fun sharedPipelines_tableDriven_keepGlobalScopeAndHonorUncheckedOption() {
        val service = source("service/CaptureService.kt").substringAfter("private fun persistTranslationCorrection(")
            .substringBefore("private fun resetLoopCaptureState()")
        val overlay = source("overlay/TranslationCorrectionOverlay.kt")
        val memory = source("translate/TranslationMemory.kt")
        val router = source("translate/RoutingTranslator.kt")
        val pane = source("ui/TranslationMemoryPane.kt")
        listOf(
            "checkbox is usable" to (overlay.contains("checked = true") && !overlay.contains("isEnabled = scope != null")),
            "choice reaches save" to overlay.contains("rememberTranslation = remember.isChecked,"),
            "unchecked skips persistence" to service.contains("if (draft.rememberTranslation)"),
            "no missing-app rejection" to !service.contains("memoryScope != null"),
            "global glossary stays global" to overlay.contains("scope?.takeIf { it.packageName.isNotBlank() }"),
            "no obsolete warning" to !overlay.contains("R.string.translation_correction_no_game"),
            "global list label" to pane.contains("R.string.glossary_scope_global"),
            "global saved message" to service.contains("getString(R.string.glossary_scope_global)"),
            "single and stream recall" to (router.split("translationMemory.recall(source, settings)").size >= 3),
            "batch recall" to router.contains("translationMemory.recallBatch(sources, settings)"),
            "explicit global does not consult foreground" to memory.contains("translationMemoryScope(explicitScope, settings.runtimeTranslationScopeLabel)"),
            "scope is no longer nullable" to memory.contains("currentScope(settings: Settings): TranslationMemoryScope {"),
        ).forEach { (case, passed) -> assertTrue(case, passed) }
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/gameocr/app", path), File("app/src/main/java/com/gameocr/app", path),
    ).first(File::isFile).readText()

    private class FakeDao : TranslationMemoryDao {
        val state = MutableStateFlow<List<TranslationMemoryEntity>>(emptyList())
        val queriedScopes = mutableListOf<String>()
        val trimmedScopes = mutableListOf<String>()
        var failWrites = false
        override fun observeAll() = state
        override suspend fun findById(id: Long) = state.value.find { it.id == id }
        private fun entries(scope: String, source: String, target: String) = state.value.filter {
            it.scopePackage == scope && it.sourceLang == source && it.targetLang == target
        }
        override suspend fun findExact(scopePackage: String, sourceLang: String, targetLang: String, normalizedSource: String): TranslationMemoryEntity? {
            queriedScopes += scopePackage
            return entries(scopePackage, sourceLang, targetLang).filter {
                it.normalizedObservedSource == normalizedSource || it.normalizedCorrectedSource == normalizedSource
            }.sortedWith(compareBy<TranslationMemoryEntity> { if (it.normalizedObservedSource == normalizedSource) 0 else 1 }
                .thenByDescending { it.updatedAtMs }).firstOrNull()
        }
        override suspend fun findObserved(scopePackage: String, sourceLang: String, targetLang: String, normalizedSource: String) =
            entries(scopePackage, sourceLang, targetLang).find { it.normalizedObservedSource == normalizedSource }
        override suspend fun fuzzyCandidates(scopePackage: String, sourceLang: String, targetLang: String, minLength: Int, maxLength: Int, limit: Int) =
            entries(scopePackage, sourceLang, targetLang).filter {
                it.normalizedObservedLength in minLength..maxLength || it.normalizedCorrectedLength in minLength..maxLength
            }.sortedByDescending { it.updatedAtMs }.take(limit)
        override suspend fun insert(entry: TranslationMemoryEntity): Long {
            check(!failWrites) { "Storage unavailable" }
            val id = (state.value.maxOfOrNull { it.id } ?: 0L) + 1
            state.value += entry.copy(id = id)
            return id
        }
        override suspend fun update(entry: TranslationMemoryEntity) {
            state.value = state.value.map { if (it.id == entry.id) entry else it }
        }
        override suspend fun delete(id: Long) { state.value = state.value.filterNot { it.id == id } }
        override suspend fun recordHit(id: Long, usedAtMs: Long) {
            findById(id)?.let { update(it.copy(hitCount = it.hitCount + 1, lastUsedAtMs = usedAtMs)) }
        }
        override suspend fun trimScope(scopePackage: String, sourceLang: String, targetLang: String, keepCount: Int) {
            trimmedScopes += scopePackage
            val removed = entries(scopePackage, sourceLang, targetLang).sortedByDescending { it.updatedAtMs }.drop(keepCount).map { it.id }.toSet()
            state.value = state.value.filterNot { it.id in removed }
        }
    }
}
