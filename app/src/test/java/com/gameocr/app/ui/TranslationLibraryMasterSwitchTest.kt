package com.gameocr.app.ui

import com.gameocr.app.data.Settings
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TranslationLibraryMasterSwitchTest {
    private fun source(path: String) = listOf(File("src/main/java/com/gameocr/app/$path"),
        File("app/src/main/java/com/gameocr/app/$path")).first(File::isFile).readText()

    @Test fun independentSettings_roundTrip_tableDriven() {
        for (terms in listOf(false, true)) for (memory in listOf(false, true)) {
            val settings = Settings(translationGlossaryEnabled = terms, translationMemoryEnabled = memory)
            val restored = Json.decodeFromString<Settings>(Json.encodeToString(settings))
            assertEquals(terms, restored.translationGlossaryEnabled)
            assertEquals(memory, restored.translationMemoryEnabled)
            assertTrue(restored.sourcePreservationEnabled)
        }
        assertTrue(Settings().translationMemoryEnabled)
    }

    @Test fun masterSwitches_shareStyleAndPersistWithoutDeletingRecords() {
        val page = source("ui/GlossaryScreen.kt")
        val vm = source("ui/GlossaryViewModel.kt")
        val memory = source("translate/TranslationMemory.kt")
        val repository = source("data/SettingsRepository.kt")
        listOf(
            "terms toggles inverse enabled state" to page.contains("viewModel.setGlossaryEnabled(!it)"),
            "memory toggles inverse enabled state" to page.contains("viewModel.setMemoryEnabled(!it)"),
            "terms persistence" to vm.contains("it.copy(translationGlossaryEnabled = enabled)"),
            "memory persistence" to vm.contains("it.copy(translationMemoryEnabled = enabled)"),
            "stored memory flag" to repository.contains("prefs[Keys.TranslationMemoryEnabled] = next.translationMemoryEnabled"),
            "read memory flag" to repository.contains("translationMemoryEnabled = this[Keys.TranslationMemoryEnabled]"),
            "single recall skips disabled" to memory.contains("if (!settings.translationMemoryEnabled || !isTranslationMemoryRecallEligible(source)) return null"),
            "batch preserves input positions while disabled" to memory.contains("if (!settings.translationMemoryEnabled) return List(sources.size) { null }"),
            "terms existing common gate" to source("glossary/TranslationContextResolver.kt").contains("if (settings.translationGlossaryEnabled)"),
            "memory list keeps master visible when empty" to source("ui/TranslationMemoryPane.kt").contains("item(key = \"memory-master\") { masterSwitch() }"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }
}
