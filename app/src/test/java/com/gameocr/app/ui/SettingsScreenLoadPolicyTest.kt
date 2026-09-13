package com.gameocr.app.ui

import com.gameocr.app.data.Settings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsScreenLoadPolicyTest {
    @Test fun readsOnceAndOnlyMigratesKnownDefaults_tableDriven() = runBlocking {
        data class Case(val prompt: String, val expected: String, val writes: Int)
        listOf(
            Case("en-default", "en-default", 0),
            Case("zh-default", "en-default", 1),
            Case("custom prompt", "custom prompt", 0),
            Case("", "", 0),
        ).forEach { case ->
            var storage = Settings(promptTemplate = case.prompt, floatingButtonAlpha = 0.4f)
            var reads = 0
            var writes = 0
            val loaded = loadSettingsForScreen(
                read = { reads++; storage },
                update = { transform -> writes++; storage = transform(storage) },
                currentDefault = "en-default",
                knownDefaults = { listOf("en-default", "zh-default") },
            )
            assertEquals(case.prompt, 1, reads)
            assertEquals(case.prompt, case.writes, writes)
            assertEquals(case.expected, loaded.promptTemplate)
            assertEquals(0.4f, loaded.floatingButtonAlpha, 0f)
            assertEquals(storage, loaded)
        }
    }

    @Test fun concurrentPromptEditIsNotOverwritten_tableDriven() = runBlocking {
        for (concurrentPrompt in listOf("my edited prompt", "en-default", "zh-default")) {
            var storage = Settings(promptTemplate = "zh-default")
            val loaded = loadSettingsForScreen(
                read = { storage },
                update = { transform ->
                    storage = transform(storage.copy(promptTemplate = concurrentPrompt, floatingButtonAlpha = 0.6f))
                },
                currentDefault = "en-default",
                knownDefaults = { listOf("en-default", "zh-default") },
            )
            assertEquals(if (concurrentPrompt == "zh-default") "en-default" else concurrentPrompt, loaded.promptTemplate)
            assertEquals(0.6f, loaded.floatingButtonAlpha, 0f)
            assertEquals(storage, loaded)
        }
    }

    @Test fun noLocaleEnumerationWhenPromptAlreadyMatches() = runBlocking {
        loadSettingsForScreen(
            read = { Settings(promptTemplate = "en-default") },
            update = { fail("No migration required") },
            currentDefault = "en-default",
            knownDefaults = { error("Should not enumerate locales") },
        )
        Unit
    }

    @Test fun settingsMappingRunsOffCollectorAndUiLoadsOneSnapshot() {
        val repository = File("src/main/java/com/gameocr/app/data/SettingsRepository.kt").readText()
        val flow = repository.substringAfter("val settings: Flow<Settings>").substringBefore("suspend fun get()")
        assertTrue(flow.indexOf("prefs.toSettings()") < flow.indexOf(".flowOn(Dispatchers.IO)"))
        val screen = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        assertEquals(1, Regex("viewModel.loadForScreen\\(context\\)").findAll(screen).count())
        assertFalse(screen.contains("migrateDefaultPromptIfStale"))
        assertFalse(screen.contains("viewModel.load()"))
        assertTrue(screen.contains("val migratedPrompt = s.promptTemplate"))
    }
}
