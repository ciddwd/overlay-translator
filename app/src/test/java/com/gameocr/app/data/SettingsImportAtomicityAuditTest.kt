package com.gameocr.app.data

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Commit order and rollback are behavior-tested in [SettingsBundleImportTransactionTest]. This only
 * checks what a JVM test cannot run: the ViewModel's real stores and Room transactions.
 */
class SettingsImportAtomicityAuditTest {

    @Test
    fun bundleImport_wiresEveryPersistentStoreIntoTheTransaction() {
        val viewModel = sourceFile("src/main/java/com/gameocr/app/ui/SettingsViewModel.kt").readText()
        val glossary = sourceFile("src/main/java/com/gameocr/app/glossary/TranslationGlossary.kt").readText()
        val function = viewModel.substring(
            viewModel.indexOf("suspend fun importSettingsBundle"),
            viewModel.indexOf("suspend fun importOverlayFont"),
        )
        listOf(
            "SettingsBundleImportTransaction(",
            "commitFont = overlayFontManager::commitTransferredFont",
            "importGlossary = { glossaryRepository.importTerms(preview.glossaryTerms) }",
            "restoreSettings = { repo.update { beforeSettings } }",
            "restoreGlossary = { glossaryRepository.restoreTerms(beforeGlossary) }",
            "rollbackFont = overlayFontManager::rollbackTransferredFont",
        ).forEach { marker ->
            assertTrue("missing import step: $marker", function.contains(marker))
        }
        assertTrue("glossary import must be a Room transaction", glossary.contains("suspend fun importTermsAtomically"))
        assertTrue("glossary restore must be a Room transaction", glossary.contains("suspend fun replaceAllAtomically"))
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
