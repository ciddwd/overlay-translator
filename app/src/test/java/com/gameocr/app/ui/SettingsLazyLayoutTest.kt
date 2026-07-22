package com.gameocr.app.ui

import com.gameocr.app.data.SettingsFieldPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLazyLayoutTest {

    @Test
    fun searchTargetContainer_stacksMultiControlTargetsVertically() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val functionStart = source.indexOf("private fun SettingsSearchTarget(")
        val functionEnd = source.indexOf("private fun openExternalBrowser", functionStart)
        assertTrue("SettingsSearchTarget function", functionStart >= 0 && functionEnd > functionStart)
        val function = source.substring(functionStart, functionEnd)

        data class Case(val name: String, val marker: String)
        listOf(
            Case("search target uses a vertical container", "Column("),
            Case("search target preserves section spacing", "Arrangement.spacedBy(10.dp)"),
            Case("search target still fills available width", ".fillMaxWidth()"),
            Case("search target keeps precise relocation anchor", ".bringIntoViewRequester(requester)"),
        ).forEach { case ->
            assertTrue(case.name, function.contains(case.marker))
        }
        assertFalse("Box would overlay multiple target controls", function.contains("Box("))
    }

    @Test
    fun sectionIndex_usesStableLazyListOrder() {
        data class Case(val key: String, val expectedIndex: Int)

        listOf(
            Case("app_lang", 0),
            Case("theme_mode", 1),
            Case("presets", 2),
            Case("translate", 3),
            Case("tts", 4),
            Case("ocr", 5),
            Case("text_orientation", 6),
            Case("overlay", 7),
            Case("floating", 8),
            Case("arc_menu", 9),
            Case("word_select", 10),
            Case("trigger", 11),
            Case("developer", 12),
            Case("network", 13),
        ).forEach { case ->
            assertEquals(case.key, case.expectedIndex, settingsSectionIndex(case.key))
        }

        assertNull("unknown search sections must not jump to the first setting", settingsSectionIndex("missing"))
        assertEquals(
            "section keys must stay unique",
            SETTINGS_SECTION_KEYS_IN_ORDER.size,
            SETTINGS_SECTION_KEYS_IN_ORDER.toSet().size,
        )
    }

    @Test
    fun everyGlobalSearchEntry_targetsALazySection() {
        val missing = settingsSearchSectionKeys() - SETTINGS_SECTION_KEYS_IN_ORDER.toSet()
        assertTrue("search entries without a lazy section: $missing", missing.isEmpty())

        val missingChildTargets = settingsSearchTargetResIds() - SETTINGS_SEARCH_TARGET_RES_IDS
        assertTrue(
            "search entries without a child bring-into-view target: $missingChildTargets",
            missingChildTargets.isEmpty(),
        )
        val targetsWithoutEntries = SETTINGS_SEARCH_TARGET_RES_IDS - settingsSearchTargetResIds()
        assertTrue(
            "child bring-into-view targets without a search descriptor: $targetsWithoutEntries",
            targetsWithoutEntries.isEmpty(),
        )
        assertEquals(
            "search entry ids must stay unique",
            settingsSearchEntryCount(),
            settingsSearchEntryIds().size,
        )
        val policyEntriesWithoutDescriptor = SettingsFieldPolicy.searchEntryIds - settingsSearchEntryIds()
        assertTrue(
            "SettingsFieldPolicy search ids without a UI descriptor: $policyEntriesWithoutDescriptor",
            policyEntriesWithoutDescriptor.isEmpty(),
        )
    }

    @Test
    fun searchRanking_prefersLabelsThenCurrentValuesThenOptionsAndKeywords() {
        val label = settingsSearchScore(
            query = "translation layout",
            itemLabel = "Translation layout",
            sectionLabel = "Text orientation",
        )
        val current = settingsSearchScore(
            query = "vertical",
            itemLabel = "Translation layout",
            sectionLabel = "Text orientation",
            currentValue = "Vertical",
        )
        val option = settingsSearchScore(
            query = "right to left",
            itemLabel = "Translation layout",
            sectionLabel = "Text orientation",
            optionLabels = listOf("Left to right", "Right to left"),
        )
        val keyword = settingsSearchScore(
            query = "writing mode",
            itemLabel = "Translation layout",
            sectionLabel = "Text orientation",
            keywords = listOf("writing mode"),
        )

        assertEquals(1_000, label)
        assertEquals(700, current)
        assertEquals(550, option)
        assertEquals(400, keyword)
        assertNull(
            settingsSearchScore(
                query = "missing term",
                itemLabel = "Translation layout",
                sectionLabel = "Text orientation",
            )
        )
    }

    @Test
    fun settingsUsesLazySectionsAndRouteOwnedScrollState() {
        val settings = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val main = sourceFile("src/main/java/com/gameocr/app/ui/MainActivity.kt")
            .readText()
            .replace("\r\n", "\n")
        data class Case(val name: String, val source: String, val marker: String)

        val cases = mutableListOf(
            Case("lazy settings list", settings, "LazyColumn(\n                state = listState"),
            Case("stable content padding", settings, "contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)"),
            Case("search index jump", settings, "settingsSectionIndex(entry.sectionKey)"),
            Case("child target registry", settings, "SettingsSearchTargetRegistry"),
            Case("child bring into view", settings, "requester.bringIntoView()"),
            Case("two phase section jump", settings, "listState.scrollToItem(index)"),
            Case("route owns settings list state", main, "val settingsListState = rememberLazyListState()"),
            Case("route passes settings list state", main, "listState = settingsListState"),
            Case("crash log route remains available", main, "Route.Logs -> LogScreen"),
        )
        SETTINGS_SECTION_KEYS_IN_ORDER.forEach { key ->
            val constant = when (key) {
                "app_lang" -> "APP_LANG"
                "theme_mode" -> "THEME_MODE"
                "presets" -> "PRESETS"
                "translate" -> "TRANSLATE"
                "tts" -> "TTS"
                "ocr" -> "OCR"
                "text_orientation" -> "TEXT_ORIENTATION"
                "overlay" -> "OVERLAY"
                "floating" -> "FLOATING"
                "arc_menu" -> "ARC_MENU"
                "word_select" -> "WORD_SELECT"
                "trigger" -> "TRIGGER"
                "developer" -> "DEVELOPER"
                "network" -> "NETWORK"
                else -> error("Unhandled settings section key: $key")
            }
            cases += Case("lazy item for $key", settings, "item(key = SectionKeys.$constant)")
        }

        cases.forEach { case -> assertTrue(case.name, case.source.contains(case.marker)) }
        assertFalse("the main settings page must not eagerly compose a vertical Column", settings.contains("verticalScroll(scrollState)"))
    }

    @Test
    fun modelDownloadProgress_isGlobalAndNotOwnedByPresetSection() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val topBarStart = source.indexOf("topBar = {")
        val topBarEnd = source.indexOf("floatingActionButton = {", topBarStart)
        val presetStart = source.indexOf("private fun TranslationPresetSection(")
        val presetEnd = source.indexOf("private fun ModelDownloadProgressCard(", presetStart)
        assertTrue("settings top bar block", topBarStart >= 0 && topBarEnd > topBarStart)
        assertTrue("translation preset block", presetStart >= 0 && presetEnd > presetStart)

        val topBar = source.substring(topBarStart, topBarEnd)
        val presetSection = source.substring(presetStart, presetEnd)
        data class Case(val name: String, val source: String, val marker: String, val expected: Boolean)
        listOf(
            Case("global area observes every active or failed download state", topBar, "if (activeModelDownloads.isNotEmpty() || unresolvedModelDownloadFailure != null)", true),
            Case("global area renders every active download", topBar, "activeModelDownloads.forEach { download ->", true),
            Case("global area renders download progress", topBar, "ModelDownloadProgressCard(", true),
            Case("global area retains terminal failure", topBar, "ModelDownloadFailureCard(", true),
            Case("terminal failure exposes retry", topBar, "viewModel.downloadModelsIndependently(", true),
            Case("each active download exposes its own cancellation", topBar, "viewModel.cancelModelDownload(download.id)", true),
            Case("preset section does not render global progress", presetSection, "ModelDownloadProgressCard(", false),
            Case("preset section does not own progress status", presetSection, "activeDownloadStatus", false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.source.contains(case.marker))
        }
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
