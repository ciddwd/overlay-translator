package com.gameocr.app.ui

import com.gameocr.app.dictionary.DictionaryPackCatalog
import com.gameocr.app.dictionary.DictionaryPackId
import com.gameocr.app.dictionary.DictionaryPackStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryLibraryWiringTest {
    @Test
    fun visibleOfflinePacks_tableDriven_hidesJapaneseAndKoreanWithoutMutatingCatalog() {
        data class State(val installed: Boolean, val invalidReason: String?)
        for (state in listOf(State(false, null), State(true, null), State(false, "invalid"))) {
            val all = DictionaryPackCatalog.all.reversed().map { spec ->
                DictionaryPackStatus(spec, state.installed, invalidReason = state.invalidReason)
            }
            assertEquals(
                state.toString(),
                all.filter { it.spec.id == DictionaryPackId.ECDICT },
                visibleDictionaryPackStatuses(all),
            )
            assertEquals(3, all.size)
            for (status in all) {
                val expected = if (status.spec.id == DictionaryPackId.ECDICT) listOf(status) else emptyList()
                assertEquals(status.toString(), expected, visibleDictionaryPackStatuses(listOf(status)))
            }
        }
        assertTrue(visibleDictionaryPackStatuses(emptyList()).isEmpty())
        assertEquals(DictionaryPackId.JMDICT, DictionaryPackCatalog.forLanguage("ja")?.id)
        assertEquals(DictionaryPackId.KOREAN_BASIC, DictionaryPackCatalog.forLanguage("ko")?.id)
        val screen = projectFile("app/src/main/java/com/gameocr/app/ui/DictionaryLibraryScreen.kt").readText()
        assertTrue(screen.contains("visibleDictionaryPackStatuses(statuses)"))
        assertTrue(screen.contains("statuses = visibleStatuses"))
    }

    @Test
    fun dictionaryLibrary_tableDriven_keepsNavigationModeAndImportWired() {
        val cases = listOf(
            SourceExpectation(
                "settings entry",
                "app/src/main/java/com/gameocr/app/ui/SettingsScreen.kt",
                listOf("settings_manage_dictionary_library", "onOpenDictionaryLibrary"),
            ),
            SourceExpectation(
                "route",
                "app/src/main/java/com/gameocr/app/ui/MainActivity.kt",
                listOf("Route.DictionaryLibrary", "DictionaryLibraryScreen"),
            ),
            SourceExpectation(
                "mode selector",
                "app/src/main/java/com/gameocr/app/ui/DictionaryLibraryScreen.kt",
                listOf(
                    "DictionaryLookupMode.entries",
                    "dictionary_mode_offline",
                    "dictionary_mode_online",
                    "SwitchRow(",
                    "settings.dictionaryTapLookupEnabled",
                    "viewModel::setTapLookupEnabled",
                ),
            ),
            SourceExpectation(
                "package import",
                "app/src/main/java/com/gameocr/app/ui/DictionaryLibraryScreen.kt",
                listOf("ActivityResultContracts.OpenDocument", "viewModel.importPack"),
            ),
        )
        cases.forEach { case ->
            val source = projectFile(case.path).readText()
            case.required.forEach { marker ->
                assertTrue("${case.name}: missing $marker", marker in source)
            }
        }
    }

    @Test
    fun lookupPipeline_doesNotContainForbiddenCrossModeFallbacks() {
        val source = projectFile(
            "app/src/main/java/com/gameocr/app/translate/FloatingWordLookupCoordinator.kt"
        ).readText()
        assertFalse("dictionary lookup must not call plain translation", "translator.translate(word" in source)
        assertTrue("offline mode must use offline repository", "offlineDictionary.lookup" in source)
        assertTrue("online mode must be capability-gated", "supportsOnlineDictionaryLookup" in source)
    }

    @Test
    fun sourceTapLookupSwitch_tableDriven_gatesEveryInteractiveEntryPoint() {
        val cases = listOf(
            SourceExpectation(
                "floating window tap",
                "app/src/main/java/com/gameocr/app/overlay/OverlayManager.kt",
                listOf("dictionaryTapLookupEnabled", "if (!dictionaryTapLookupEnabled)"),
            ),
            SourceExpectation(
                "translation card tap",
                "app/src/main/java/com/gameocr/app/overlay/TranslationCardOverlay.kt",
                listOf(
                    "setSourceWordLookupEnabled",
                    "if (!sourceWordLookupEnabled)",
                    "settings.dictionaryTapLookupEnabled",
                ),
            ),
            SourceExpectation(
                "service wiring",
                "app/src/main/java/com/gameocr/app/service/CaptureService.kt",
                listOf(
                    "if (settings.dictionaryTapLookupEnabled)",
                    "if (!settings.dictionaryTapLookupEnabled)",
                    "updateDictionaryTapLookupEnabled(settings.dictionaryTapLookupEnabled)",
                    "translationCard?.setSourceWordLookupEnabled",
                ),
            ),
            SourceExpectation(
                "persistent setting",
                "app/src/main/java/com/gameocr/app/data/SettingsRepository.kt",
                listOf("dictionary_tap_lookup_enabled", "next.dictionaryTapLookupEnabled"),
            ),
        )
        cases.forEach { case ->
            val source = projectFile(case.path).readText()
            case.required.forEach { marker ->
                assertTrue("${case.name}: missing $marker", marker in source)
            }
        }
    }

    @Test
    fun dictionaryLibrary_compactLayout_doesNotRenderRedundantSummaryOrLargeCards() {
        val source = projectFile(
            "app/src/main/java/com/gameocr/app/ui/DictionaryLibraryScreen.kt"
        ).readText()
        val forbidden = listOf(
            "dictionary_library_summary",
            "dictionary_pack_import_note",
            "DictionaryModeCard",
            "DictionaryPackCard",
            "SingleChoiceSegmentedButtonRow",
            "dictionary_pack_ready_format",
            "dictionary_pack_source_format",
            "status.entryCount",
            "status.version",
            "status.byteCount",
            "status.spec.sourceName",
            "status.spec.licenseName",
            "formattedSize",
        )
        forbidden.forEach { marker ->
            assertFalse("compact dictionary layout must not contain $marker", marker in source)
        }
        listOf(
            "DictionaryModeGroup",
            "DictionaryPackGroup",
            "DictionaryPackRow",
            "DictionarySectionCard",
            "CardDefaults.cardElevation(defaultElevation = 0.dp)",
            "BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)",
            "status.installed -> stringResource(R.string.dictionary_pack_installed)",
            "status.invalidReason != null -> status.invalidReason",
            "R.string.dictionary_pack_not_installed",
        ).forEach { marker ->
            assertTrue("compact dictionary layout missing $marker", marker in source)
        }
        for ((directory, installed) in listOf("values" to "Installed", "values-zh-rCN" to "已安装")) {
            val strings = projectFile("app/src/main/res/$directory/strings.xml").readText()
            assertTrue(directory, strings.contains("<string name=\"dictionary_pack_installed\">$installed</string>"))
        }
    }

    private data class SourceExpectation(
        val name: String,
        val path: String,
        val required: List<String>,
    )

    private fun projectFile(path: String): File = File(path).takeIf(File::isFile)
        ?: File("..", path)
}
