package com.gameocr.app.data

import java.io.ByteArrayInputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPresetTransferTest {

    @Test
    fun encryptedExportRoundTripsWithoutPlainPresetContent() {
        val settings = Settings(
            model = "sync-model",
            targetLang = "zh-TW",
            dictionaryPrompt = "private dictionary prompt",
            translatorEngine = TranslatorEngine.OPENAI,
        )
        val preset = preset(
            id = "custom_sync",
            name = "Manga Sync",
            settings = settings,
        )

        val exported = TranslationPresetTransfer.encodeEncrypted(listOf(preset))

        assertTrue(exported.contains("ciphertext"))
        assertFalse(exported.contains("Manga Sync"))
        assertFalse(exported.contains("sync-model"))
        assertFalse(exported.contains("private dictionary prompt"))

        val decoded = TranslationPresetTransfer.decodeEncrypted(exported)
        assertEquals(1, decoded.size)
        assertEquals("Manga Sync", decoded.single().name)
        assertTrue(TranslationPresetCatalog.matchesSettings(decoded.single(), settings))
    }

    @Test
    fun planImportReportsDuplicateNamesBeforeSaving() {
        val existing = listOf(
            preset(id = "custom_manga", name = "Manga"),
            preset(id = "custom_game", name = "Game"),
        )
        val imported = listOf(
            preset(id = "import_manga", name = "manga"),
            preset(id = "import_novel", name = "Novel"),
        )

        val plan = TranslationPresetTransfer.planImport(existing, imported)

        assertEquals(2, plan.importedCount)
        assertEquals(listOf("Manga"), plan.overwrittenNames)
        assertEquals(listOf("Novel"), plan.addedNames)
    }

    @Test
    fun mergeImportedPresetsOverwritesDuplicateNamesCaseInsensitively() {
        data class Case(
            val name: String,
            val existingName: String,
            val importedName: String,
        )

        val cases = listOf(
            Case("exact name match", "Manga", "Manga"),
            Case("case-insensitive name match", "Manga", "manga"),
            Case("trimmed name match", "Manga", "  Manga  "),
        )

        cases.forEach { case ->
            val existing = preset(
                id = "custom_existing",
                name = case.existingName,
                settings = Settings(model = "old-model")
            )
            val imported = preset(
                id = "custom_imported",
                name = case.importedName,
                settings = Settings(model = "new-model")
            )

            val result = TranslationPresetTransfer.mergeImportedPresets(
                existing = listOf(existing),
                imported = listOf(imported),
            )

            assertEquals(case.name, 1, result.presets.size)
            assertEquals(case.name, listOf(case.existingName), result.overwrittenNames)
            assertEquals(case.name, "custom_existing", result.presets.single().id)
            assertEquals(case.name, case.existingName, result.presets.single().name)
            assertEquals(case.name, "new-model", result.presets.single().model)
        }
    }

    @Test
    fun mergeImportedPresets_usesLocaleIndependentNameMatching() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val cases = listOf(
                "INDIGO" to "indigo",
                "  INDIGO  " to "indigo",
                "漫画" to "漫画",
            )
            cases.forEachIndexed { index, (existingName, importedName) ->
                val result = TranslationPresetTransfer.mergeImportedPresets(
                    existing = listOf(preset("existing_$index", existingName)),
                    imported = listOf(preset("imported_$index", importedName)),
                )
                assertEquals("case $index", 1, result.presets.size)
                assertEquals("case $index", listOf(existingName), result.overwrittenNames)
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun mergeImportedPresetsAddsNewNamesAndAvoidsIdCollisions() {
        val existing = preset(id = "custom_same_id", name = "Manga")
        val imported = preset(id = "custom_same_id", name = "Novel")

        val result = TranslationPresetTransfer.mergeImportedPresets(
            existing = listOf(existing),
            imported = listOf(imported),
        )

        assertEquals(2, result.presets.size)
        assertEquals(listOf("Novel"), result.addedNames)
        assertEquals("custom_same_id", result.presets.first().id)
        assertNotEquals("custom_same_id", result.presets.last().id)
        assertTrue(result.presets.last().id.startsWith("custom_imported_"))
    }

    @Test
    fun importPreparationSkipsBuiltInIdsAndBlankNames() {
        val imported = listOf(
            TranslationPreset(id = TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH, name = "Shadow"),
            TranslationPreset(id = "custom_blank", name = "   "),
        )

        val plan = TranslationPresetTransfer.planImport(
            existing = emptyList(),
            imported = imported,
        )

        assertEquals(0, plan.importedCount)
        assertTrue(plan.importedPresets.isEmpty())
    }

    @Test
    fun transferLimitsRejectOversizedStreamsAndPresetLists() {
        val utf8 = "你好".toByteArray(Charsets.UTF_8)
        assertEquals(
            "你好",
            TranslationPresetTransfer.readUtf8Limited(
                ByteArrayInputStream(utf8),
                maxBytes = utf8.size,
            )
        )
        assertTrue(
            runCatching {
                TranslationPresetTransfer.readUtf8Limited(
                    ByteArrayInputStream(utf8),
                    maxBytes = utf8.size - 1,
                )
            }.isFailure
        )

        val tooMany = (0..TranslationPresetTransfer.MAX_PRESET_COUNT).map { index ->
            preset(id = "custom_$index", name = "Preset $index")
        }
        assertTrue(runCatching { TranslationPresetTransfer.encodeEncrypted(tooMany) }.isFailure)
    }

    private fun preset(
        id: String,
        name: String,
        settings: Settings = Settings(),
    ): TranslationPreset = TranslationPresetCatalog.fromSettings(
        id = id,
        name = name,
        shortName = name.trim().take(8),
        settings = settings,
    )
}
