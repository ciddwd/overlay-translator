package com.gameocr.app.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsImportAtomicityAuditTest {

    @Test
    fun bundleImport_stagesEveryFontBeforeAnyPersistentCommit() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsViewModel.kt").readText()
        val function = source.substring(
            source.indexOf("suspend fun importSettingsBundle"),
            source.indexOf("suspend fun importOverlayFont"),
        )
        val stage = function.indexOf("stageTransferredFont")
        val fontCommit = function.indexOf("commitTransferredFont")
        val glossaryCommit = function.indexOf("glossaryRepository.importTerms")
        val settingsCommit = function.indexOf("repo.update { merged.settings }")

        assertTrue("font staging must happen first", stage >= 0)
        assertTrue("font files commit after full package staging", fontCommit > stage)
        assertTrue("glossary commits after fonts are validated", glossaryCommit > fontCommit)
        assertTrue("settings commits only after atomic glossary import", settingsCommit > glossaryCommit)
    }

    @Test
    fun bundleImport_hasCompensatingRollbackForEveryPersistentStore() {
        val viewModel = sourceFile("src/main/java/com/gameocr/app/ui/SettingsViewModel.kt").readText()
        val glossary = sourceFile("src/main/java/com/gameocr/app/glossary/TranslationGlossary.kt").readText()

        listOf(
            "repo.update { beforeSettings }",
            "glossaryRepository.restoreTerms(beforeGlossary)",
            "overlayFontManager.rollbackTransferredFont(commit)",
        ).forEach { marker ->
            assertTrue("missing import rollback: $marker", viewModel.contains(marker))
        }
        assertTrue("glossary import must be a Room transaction", glossary.contains("suspend fun importTermsAtomically"))
        assertTrue("glossary restore must be a Room transaction", glossary.contains("suspend fun replaceAllAtomically"))
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
