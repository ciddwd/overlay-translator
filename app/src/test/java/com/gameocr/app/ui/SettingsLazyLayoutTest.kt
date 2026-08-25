package com.gameocr.app.ui

import com.gameocr.app.R
import com.gameocr.app.data.SettingsFieldPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLazyLayoutTest {

    @Test
    fun llmRequestOptions_areSearchableByEveryVisibleControl_tableDriven() {
        data class Case(val name: String, val query: String)

        listOf(
            Case("section title", "LLM 请求参数"),
            Case("user message template", "用户消息模板"),
            Case("Base64", "base64"),
            Case("Unicode", "unicode"),
            Case("system suffix", "系统提示词后缀"),
            Case("temperature", "temperature"),
            Case("top p", "top_p"),
            Case("max tokens", "max_tokens"),
        ).forEach { case ->
            val score = settingsSearchScore(
                query = case.query,
                itemLabel = "LLM 请求参数",
                sectionLabel = "翻译",
                keywords = SETTINGS_SEARCH_LLM_REQUEST_OPTION_KEYWORDS,
            )
            assertTrue(case.name, score != null)
        }
    }

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
            Case("general", 0),
            Case("presets", 1),
            Case("translate", 2),
            Case("tts", 3),
            Case("ocr", 4),
            Case("overlay", 5),
            Case("word_select", 6),
            Case("capture_region", 7),
            Case("trigger", 8),
            Case("floating", 9),
            Case("arc_menu", 10),
            Case("developer", 11),
            Case("network", 12),
        ).forEach { case ->
            assertEquals(case.key, case.expectedIndex, settingsSectionIndex(case.key))
        }

        assertNull("unknown search sections must not jump to the first setting", settingsSectionIndex("missing"))
        assertNull("preprocess is nested inside OCR", settingsSectionIndex("preprocess"))
        assertNull("text orientation is nested inside OCR", settingsSectionIndex("text_orientation"))
        assertNull("application language is nested inside general", settingsSectionIndex("app_lang"))
        assertNull("theme mode is nested inside general", settingsSectionIndex("theme_mode"))
        assertEquals(
            "section keys must stay unique",
            SETTINGS_SECTION_KEYS_IN_ORDER.size,
            SETTINGS_SECTION_KEYS_IN_ORDER.toSet().size,
        )
    }

    @Test
    fun sectionOrder_tableDriven_placesCaptureRegionImmediatelyBeforeLoopTrigger() {
        data class Case(
            val name: String,
            val before: String,
            val after: String,
        )

        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        listOf(
            Case("general before presets", "GENERAL", "PRESETS"),
            Case("OCR before overlay", "OCR", "OVERLAY"),
            Case("word select before capture region", "WORD_SELECT", "CAPTURE_REGION"),
            Case("capture region before loop trigger", "CAPTURE_REGION", "TRIGGER"),
            Case("loop trigger before floating", "TRIGGER", "FLOATING"),
            Case("floating before arc menu", "FLOATING", "ARC_MENU"),
        ).forEach { case ->
            val beforeIndex = source.indexOf("item(key = SectionKeys.${case.before})")
            val afterIndex = source.indexOf("item(key = SectionKeys.${case.after})")
            assertTrue(
                "${case.name}: before=$beforeIndex after=$afterIndex",
                beforeIndex >= 0 && afterIndex > beforeIndex,
            )
        }
    }

    @Test
    fun generalCard_combinesLanguageAndThemeAsSecondaryHeadings() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val generalCard = source
            .substringAfter("item(key = SectionKeys.GENERAL)")
            .substringBefore("item(key = SectionKeys.PRESETS)")

        data class Case(val name: String, val marker: String)
        listOf(
            Case("one titleless outer card", "SectionCard(title = null)"),
            Case("application language search target", "*SEARCH_TARGET_APP_LANGUAGE"),
            Case("application language heading", "R.string.settings_section_app_lang"),
            Case("application language control", "AppLanguageSelector()"),
            Case("group divider", "HorizontalDivider()"),
            Case("theme search target", "*SEARCH_TARGET_THEME_MODE"),
            Case("theme heading", "R.string.settings_section_theme_mode"),
            Case("theme control", "ThemeModeSelector()"),
        ).forEach { case -> assertTrue(case.name, generalCard.contains(case.marker)) }

        assertEquals(
            "both former card titles use the secondary title style",
            2,
            generalCard.split("style = MaterialTheme.typography.titleSmall").size - 1,
        )
        assertFalse("language must not remain a standalone card", source.contains("SectionKeys.APP_LANG"))
        assertFalse("theme must not remain a standalone card", source.contains("SectionKeys.THEME_MODE"))
        assertTrue(
            "language search routes to general card",
            source.contains("SearchEntry(SectionKeys.GENERAL, R.string.settings_section_app_lang"),
        )
        assertTrue(
            "theme search routes to general card",
            source.contains("SearchEntry(SectionKeys.GENERAL, R.string.settings_section_theme_mode"),
        )
    }

    @Test
    fun overlayRenderingPage_movesOnlyAppearanceControlsAndRoutesSearch_tableDriven() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val renderingPage = source
            .substringAfter("val overlayRenderingContent: @Composable () -> Unit = {")
            .substringBefore("\n\n    Scaffold(")
        val mainOverlayCard = source
            .substringAfter("item(key = SectionKeys.OVERLAY)")
            .substringBefore("item(key = SectionKeys.WORD_SELECT)")

        data class MarkerCase(
            val name: String,
            val scope: String,
            val marker: String,
            val expected: Boolean,
        )
        listOf(
            MarkerCase("secondary page owns preview", renderingPage, "OverlayPreviewCard(", true),
            MarkerCase("secondary page does not own display mode", renderingPage, "*SEARCH_TARGET_OVERLAY_MODE", false),
            MarkerCase("secondary page owns theme", renderingPage, "*SEARCH_TARGET_OVERLAY_THEME", true),
            MarkerCase("secondary page owns typography", renderingPage, "*SEARCH_TARGET_OVERLAY_TEXT", true),
            MarkerCase("main card links to secondary page", mainOverlayCard, "R.string.settings_overlay_rendering_title", true),
            MarkerCase("main card owns display mode and floating content", mainOverlayCard, "*SEARCH_TARGET_OVERLAY_MODE", true),
            MarkerCase("main card keeps behavior controls", mainOverlayCard, "SEARCH_TARGET_OVERLAY_DISPLAY + SEARCH_TARGET_OVERLAY_WINDOW + SEARCH_TARGET_OVERLAY_LAYOUT", true),
            MarkerCase("main card no longer renders preview", mainOverlayCard, "OverlayPreviewCard(", false),
            MarkerCase("main card no longer owns theme", mainOverlayCard, "*SEARCH_TARGET_OVERLAY_THEME", false),
            MarkerCase("main card no longer owns typography", mainOverlayCard, "*SEARCH_TARGET_OVERLAY_TEXT", false),
            MarkerCase("search opens secondary page", source, "overlayRenderingOpen = true\n                                            return@clickable", true),
            MarkerCase("search preserves precise target", source, "pendingOverlayRenderingSearchTarget = entry.targetId", true),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.scope.contains(case.marker))
        }

        data class TargetCase(val name: String, val targetId: Int, val expectedMoved: Boolean)
        listOf(
            TargetCase("render mode stays on main page", R.string.settings_search_item_render_mode, false),
            TargetCase("floating content stays on main page", R.string.settings_search_item_floating_window_content, false),
            TargetCase("theme", R.string.settings_search_item_overlay_theme, true),
            TargetCase("border style", R.string.settings_search_item_border_style, true),
            TargetCase("text size", R.string.settings_search_item_text_size, true),
            TargetCase("text style", R.string.settings_search_item_text_style, true),
            TargetCase("font", R.string.settings_search_item_overlay_font, true),
            TargetCase("alpha", R.string.settings_search_item_alpha, true),
            TargetCase("placement stays on main page", R.string.settings_search_item_placement, false),
            TargetCase("floating lock stays on main page", R.string.settings_search_item_floating_window_locked, false),
            TargetCase("collision stays on main page", R.string.settings_search_item_avoid_collision, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedMoved,
                case.targetId in OVERLAY_RENDERING_SEARCH_TARGET_RES_IDS,
            )
        }
    }

    @Test
    fun overlayRenderingTitle_matchesApprovedCopy_tableDriven() {
        data class CopyCase(val name: String, val path: String, val expected: String)

        listOf(
            CopyCase("English", "src/main/res/values/strings.xml", "Display &amp; rendering"),
            CopyCase("Simplified Chinese", "src/main/res/values-zh-rCN/strings.xml", "显示与渲染"),
        ).forEach { case ->
            val strings = sourceFile(case.path).readText()
            assertTrue(
                case.name,
                strings.contains(
                    "<string name=\"settings_overlay_rendering_title\">${case.expected}</string>"
                ),
            )
        }
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
                "general" -> "GENERAL"
                "presets" -> "PRESETS"
                "translate" -> "TRANSLATE"
                "tts" -> "TTS"
                "ocr" -> "OCR"
                "capture_region" -> "CAPTURE_REGION"
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
    fun preprocessControls_areNestedAndSearchableInsideOcrSection() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val ocrSection = source
            .substringAfter("item(key = SectionKeys.OCR)")
            .substringBefore("item(key = SectionKeys.OVERLAY)")
        val ocrSearchTargets = source
            .substringAfter("private val SEARCH_TARGET_OCR_ENGINE")
            .substringBefore("private val SEARCH_TARGET_ORIENTATION_DETECTION")

        data class Case(
            val name: String,
            val settingRes: String,
            val searchRes: String,
        )
        listOf(
            Case(
                "upscale",
                "R.string.settings_preprocess_upscale",
                "R.string.settings_search_item_upscale",
            ),
            Case(
                "invert",
                "R.string.settings_preprocess_invert",
                "R.string.settings_search_item_invert",
            ),
            Case(
                "binarize",
                "R.string.settings_preprocess_binarize",
                "R.string.settings_search_item_binarize",
            ),
        ).forEach { case ->
            assertTrue("${case.name} control is not inside OCR", ocrSection.contains(case.settingRes))
            assertTrue(
                "${case.name} is missing from the OCR child search target",
                ocrSearchTargets.contains(case.searchRes),
            )
            assertTrue(
                "${case.name} search entry must route to OCR",
                source.contains(
                    "SearchEntry(SectionKeys.OCR, R.string.settings_section_ocr, " +
                        "${case.searchRes},"
                ),
            )
        }

        data class Marker(val name: String, val value: String)
        listOf(
            Marker(
                "collapsed state",
                "var preprocessExpanded by remember { mutableStateOf(false) }",
            ),
            Marker(
                "expand action",
                ".clickable { preprocessExpanded = !preprocessExpanded }",
            ),
            Marker("expanded content", "if (preprocessExpanded)"),
            Marker(
                "cloud upscale warning",
                "cloudOcrUpscaleWarningVisible(ocrEngine, preUpscale)",
            ),
        ).forEach { marker ->
            assertTrue(marker.name, source.contains(marker.value))
        }

        listOf(
            "const val PREPROCESS",
            "item(key = SectionKeys.PREPROCESS)",
            "SEARCH_TARGET_PREPROCESS",
            "SearchEntry(SectionKeys.PREPROCESS",
        ).forEach { removed ->
            assertFalse("legacy preprocess section remains: $removed", source.contains(removed))
        }
    }

    @Test
    fun textOrientationControls_areCollapsedAfterPreprocessInsideOcr_tableDriven() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val ocrSection = source
            .substringAfter("item(key = SectionKeys.OCR)")
            .substringBefore("item(key = SectionKeys.OVERLAY)")
        val preprocessHeader = ocrSection.indexOf("R.string.settings_section_preprocess")
        val orientationHeader = ocrSection.indexOf("R.string.settings_text_orientation_section_title")
        assertTrue(
            "orientation fold must follow preprocessing",
            preprocessHeader >= 0 && orientationHeader > preprocessHeader,
        )

        data class MarkerCase(val name: String, val marker: String, val expected: Boolean = true)
        listOf(
            MarkerCase("collapsed state defaults closed", "var textOrientationExpanded by remember { mutableStateOf(false) }"),
            MarkerCase("header toggles expansion", ".clickable { textOrientationExpanded = !textOrientationExpanded }"),
            MarkerCase("content is conditionally composed", "if (textOrientationExpanded)"),
            MarkerCase("orientation content is inside OCR", "textOrientationContent()"),
            MarkerCase("standalone orientation item is removed", "item(key = SectionKeys.TEXT_ORIENTATION)", false),
            MarkerCase("legacy orientation key is removed", "const val TEXT_ORIENTATION", false),
        ).forEach { case ->
            assertEquals(case.name, case.expected, source.contains(case.marker))
        }

        data class SearchCase(val name: String, val target: String)
        listOf(
            SearchCase("auto detection", "R.string.settings_orient_auto_detect_title"),
            SearchCase("manual orientation", "R.string.settings_search_item_manual_orientation"),
            SearchCase("translation follow", "R.string.settings_translation_output_follow_title"),
            SearchCase("translation layout", "R.string.settings_translation_output_layout_label"),
        ).forEach { case ->
            assertTrue(
                "${case.name} search must route to OCR",
                Regex(
                    """SearchEntry\(\s*SectionKeys\.OCR,\s*R\.string\.settings_section_ocr,\s*${Regex.escape(case.target)},"""
                ).containsMatchIn(source),
            )
        }
        assertTrue(
            "orientation search expands the collapsed group",
            source.contains("if (entry.targetId in ORIENTATION_SEARCH_TARGET_RES_IDS)") &&
                source.contains("textOrientationExpanded = true"),
        )
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
