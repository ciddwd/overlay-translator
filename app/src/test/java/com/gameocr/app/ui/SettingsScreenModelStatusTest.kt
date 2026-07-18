package com.gameocr.app.ui

import com.gameocr.app.R
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayFontEntry
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.data.TranslationPreset
import com.gameocr.app.data.TranslationPresetCatalog
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.llm.LlmModelKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SettingsScreenModelStatusTest {

    @Test
    fun translatorSection_placesLanguageAndContextControlsAfterEngineConfiguration() {
        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val sectionStart = source.indexOf(
            "SectionCard(title = stringResource(R.string.settings_section_translator)"
        )
        val sectionEnd = source.indexOf(
            "title = stringResource(R.string.settings_section_ocr),",
            startIndex = sectionStart,
        )
        assertTrue("translator section start", sectionStart >= 0)
        assertTrue("translator section end", sectionEnd > sectionStart)
        val section = source.substring(sectionStart, sectionEnd)

        data class Case(
            val name: String,
            val earlier: String,
            val later: String,
        )

        listOf(
            Case("engine before source language", "R.string.settings_label_translator_engine", "R.string.settings_source_lang"),
            Case("model before source language", "R.string.settings_model)", "R.string.settings_source_lang"),
            Case("connection test before source language", "R.string.settings_test_connection", "R.string.settings_source_lang"),
            Case("source language before target language", "R.string.settings_source_lang", "R.string.settings_target_lang"),
            Case("target language before translation assistance", "R.string.settings_target_lang", "TranslationAssistanceSettings("),
            Case("translation assistance before prompt", "TranslationAssistanceSettings(", "OpenAiPromptSettings("),
        ).forEach { case ->
            val earlierIndex = section.indexOf(case.earlier)
            val laterIndex = section.indexOf(case.later)
            assertTrue("${case.name}: missing earlier marker", earlierIndex >= 0)
            assertTrue("${case.name}: missing later marker", laterIndex >= 0)
            assertTrue(case.name, earlierIndex < laterIndex)
        }
    }

    @Test
    fun settingsSearchMatches_normalizesMultipleTermsAndPunctuation() {
        data class Case(
            val name: String,
            val query: String,
            val texts: List<String>,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("PP-OCR multi term", "pp ocr v6 online", listOf("PP-OCRv6 Online"), true),
            Case("AI Studio alias", "ai studio token", listOf("Paddle AI Studio Access Token"), true),
            Case("Chinese terms in any order", "预设 导入", listOf("导入 / 导出翻译预设"), true),
            Case("all terms are required", "local missing", listOf("Local LLM model"), false),
            Case("blank query", "   ", listOf("Local LLM model"), false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                settingsSearchMatches(case.query, case.texts),
            )
        }
    }

    @Test
    fun settingsSearchKeywords_coverPortableSettingsAndVisualColorTerms() {
        data class Case(
            val name: String,
            val query: String,
            val searchableTexts: List<String>,
        )

        val cases = listOf(
            Case("Chinese settings export", "设置导出", SETTINGS_SEARCH_TRANSFER_KEYWORDS),
            Case("English font backup", "font backup", SETTINGS_SEARCH_TRANSFER_KEYWORDS),
            Case("Chinese background color", "背景色", SETTINGS_SEARCH_COLOR_KEYWORDS),
            Case("Chinese text color", "文字颜色", SETTINGS_SEARCH_COLOR_KEYWORDS),
            Case("Chinese hue", "色相", SETTINGS_SEARCH_COLOR_KEYWORDS),
            Case("Chinese saturation alias", "饱和度", SETTINGS_SEARCH_COLOR_KEYWORDS),
            Case("English brightness", "brightness", SETTINGS_SEARCH_COLOR_KEYWORDS),
            Case("English opacity", "opacity", SETTINGS_SEARCH_COLOR_KEYWORDS),
            Case("Chinese loop similarity", "循环 相似度", SETTINGS_SEARCH_LOOP_SIMILARITY_KEYWORDS),
            Case("Chinese duplicate skip", "去重 跳过", SETTINGS_SEARCH_LOOP_SIMILARITY_KEYWORDS),
            Case("English frame hash", "frame hash", SETTINGS_SEARCH_LOOP_SIMILARITY_KEYWORDS),
            Case("English similarity threshold", "similarity threshold", SETTINGS_SEARCH_LOOP_SIMILARITY_KEYWORDS),
            Case("Chinese smart wait", "智能等待 文字完成", SETTINGS_SEARCH_LOOP_TRIGGER_KEYWORDS),
            Case("Chinese dialogue stability", "对话 文字稳定", SETTINGS_SEARCH_LOOP_TRIGGER_KEYWORDS),
            Case("English fixed interval", "fixed interval", SETTINGS_SEARCH_LOOP_TRIGGER_KEYWORDS),
            Case("English text stability", "text stability", SETTINGS_SEARCH_LOOP_TRIGGER_KEYWORDS),
            Case("Chinese lower dialogue region", "报幕区域 下半屏", SETTINGS_SEARCH_LOOP_REGION_KEYWORDS),
            Case("Chinese translate region only", "仅翻译 报幕区域", SETTINGS_SEARCH_LOOP_REGION_KEYWORDS),
            Case("English unrestricted region", "dialogue region anywhere", SETTINGS_SEARCH_LOOP_REGION_KEYWORDS),
            Case("English translate all text", "translate all text", SETTINGS_SEARCH_LOOP_REGION_KEYWORDS),
            Case("Chinese empty translation retry", "空译文 自动重试", SETTINGS_SEARCH_EMPTY_TRANSLATION_RETRY_KEYWORDS),
            Case("Chinese empty response", "空响应", SETTINGS_SEARCH_EMPTY_TRANSLATION_RETRY_KEYWORDS),
            Case("English blank response retry", "blank response retry", SETTINGS_SEARCH_EMPTY_TRANSLATION_RETRY_KEYWORDS),
            Case("English empty translation", "empty translation", SETTINGS_SEARCH_EMPTY_TRANSLATION_RETRY_KEYWORDS),
            Case("Chinese long-press selection", "译文块 长按 选择把手", SETTINGS_SEARCH_TRANSLATION_BLOCK_INTERACTION_KEYWORDS),
            Case("Chinese selectable copy panel", "选择文字 复制", SETTINGS_SEARCH_TRANSLATION_BLOCK_INTERACTION_KEYWORDS),
            Case("English long-press selection", "translation block long press selection handles", SETTINGS_SEARCH_TRANSLATION_BLOCK_INTERACTION_KEYWORDS),
            Case("English selectable copy panel", "select text copy panel", SETTINGS_SEARCH_TRANSLATION_BLOCK_INTERACTION_KEYWORDS),
            Case("Chinese OCR red box", "OCR 红框", SETTINGS_SEARCH_DEVELOPER_OCR_KEYWORDS),
            Case("Chinese developer diagnostics", "开发者 诊断", SETTINGS_SEARCH_DEVELOPER_OCR_KEYWORDS),
            Case("English bounding box", "bounding box", SETTINGS_SEARCH_DEVELOPER_OCR_KEYWORDS),
            Case("English source text", "source text", SETTINGS_SEARCH_DEVELOPER_OCR_KEYWORDS),
        )

        cases.forEach { case ->
            assertTrue(
                case.name,
                settingsSearchMatches(case.query, case.searchableTexts),
            )
        }
    }

    @Test
    fun translationDisplayPreview_isFirstAndStickyOnlyInsideItsSection() {
        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        data class Case(val name: String, val expected: Boolean)

        val sectionStart = source.indexOf("title = stringResource(R.string.settings_section_overlay)")
        val preview = source.indexOf("OverlayPreviewCard(", startIndex = sectionStart)
        val colors = source.indexOf("R.string.settings_overlay_theme_label", startIndex = sectionStart)
        val displayMode = source.indexOf("R.string.settings_render_mode_label", startIndex = sectionStart)
        val displayFlow = source.indexOf("FlowRow(", startIndex = displayMode)
        val blocks = source.indexOf("R.string.settings_render_blocks_chip", startIndex = displayMode)
        val floating = source.indexOf("R.string.settings_render_floating_window_chip", startIndex = displayMode)
        val adaptive = source.indexOf("R.string.settings_overlay_style_adaptive", startIndex = displayMode)
        val floatingContent = source.indexOf("R.string.settings_floating_window_content_label", startIndex = displayMode)
        val cases = listOf(
            Case("translation display section exists", sectionStart >= 0),
            Case("preview exists inside section", preview > sectionStart),
            Case("preview precedes color controls", preview in (sectionStart + 1)..<colors),
            Case("display mode follows color controls", displayMode > colors),
            Case("display mode options use a wrapping row", displayFlow in (displayMode + 1)..<blocks),
            Case("block display is the first option", blocks in (displayFlow + 1)..<floating),
            Case("floating window is followed by adaptive switch", adaptive in (floating + 1)..<floatingContent),
            Case("adaptive switch uses the compact labelled control", source.contains("InlineSwitchLabel(")),
            Case(
                "adaptive switch stays visible but is disabled for floating window",
                source.contains("enabled = renderMode == RenderMode.BLOCKS"),
            ),
            Case("translation style mode label is removed", !source.contains("R.string.settings_overlay_style_mode_label")),
            Case("style controls are not dimmed by adaptive mode", !source.contains("manualStyleEnabled")),
            Case("section reports window bounds", source.contains("onBoundsInWindow = { _, bottom -> overlaySectionBottomInWindow = bottom }")),
            Case("sticky state uses section-aware policy", source.contains("StickyOverlayPreviewPolicy.shouldStick(")),
        )

        cases.forEach { case -> assertTrue(case.name, case.expected) }
    }

    @Test
    fun translationPresetSectionLabel_usesSystemPresetPlanText() {
        data class Case(
            val name: String,
            val resourcePath: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "default English label",
                resourcePath = "src/main/res/values/strings.xml",
                expected = "System Preset Plans",
            ),
            Case(
                name = "Simplified Chinese label",
                resourcePath = "src/main/res/values-zh-rCN/strings.xml",
                expected = "系统预设方案",
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                stringResourceValue(case.resourcePath, "settings_section_translation_presets")
            )
        }
    }

    @Test
    fun translationPresetDescription_isSectionHelpInsteadOfInlineBodyText() {
        data class Case(
            val name: String,
            val pattern: Regex,
            val expectedPresent: Boolean,
        )

        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val cases = listOf(
            Case(
                name = "preset section uses help tooltip",
                pattern = Regex(
                    """SectionCard\([\s\S]*?title = stringResource\(R\.string\.settings_section_translation_presets\),[\s\S]*?helpText = stringResource\(R\.string\.settings_translation_preset_desc\)"""
                ),
                expectedPresent = true,
            ),
            Case(
                name = "preset description is not inline body text",
                pattern = Regex(
                    """Text\(\s*stringResource\(R\.string\.settings_translation_preset_desc\)"""
                ),
                expectedPresent = false,
            ),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expectedPresent, case.pattern.containsMatchIn(source))
        }
    }

    @Test
    fun translationPresetSummaryFormat_usesOneReadableFieldPerLine() {
        data class Case(
            val name: String,
            val resourcePath: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "default English summary",
                resourcePath = "src/main/res/values/strings.xml",
                expected = "OCR: %1\$s\\nTranslator: %2\$s\\nLanguages: %3\$s → %4\$s",
            ),
            Case(
                name = "Simplified Chinese summary",
                resourcePath = "src/main/res/values-zh-rCN/strings.xml",
                expected = "OCR：%1\$s\\n翻译：%2\$s\\n语言：%3\$s → %4\$s",
            ),
        )

        cases.forEach { case ->
            val summary = stringResourceValue(case.resourcePath, "preset_quick_summary_format")
            assertEquals(case.name, case.expected, summary)
            assertEquals(case.name, 3, summary.split("\\n").size)
            assertFalse(case.name, summary.contains(" · "))
        }
    }

    @Test
    fun translationPresetOtherDownloadBusyHint_usesExplicitWaitText() {
        data class Case(
            val name: String,
            val resourcePath: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "default English hint",
                resourcePath = "src/main/res/values/strings.xml",
                expected = "Another preset is downloading. Wait for it to finish before downloading this one.",
            ),
            Case(
                name = "Simplified Chinese hint",
                resourcePath = "src/main/res/values-zh-rCN/strings.xml",
                expected = "其他预设正在下载中，等下载完成后再下载。",
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                stringResourceValue(case.resourcePath, "settings_translation_preset_other_download_busy")
            )
        }
    }

    @Test
    fun translationPresetSaveDialogTitle_asksForPresetNameBeforeSaving() {
        data class Case(
            val name: String,
            val resourcePath: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "default English title",
                resourcePath = "src/main/res/values/strings.xml",
                expected = "Save current as preset",
            ),
            Case(
                name = "Simplified Chinese title",
                resourcePath = "src/main/res/values-zh-rCN/strings.xml",
                expected = "保存为预设方案",
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                stringResourceValue(case.resourcePath, "settings_translation_preset_save_dialog_title")
            )
        }
    }

    @Test
    fun translationPresetSaveDialog_blocksDuplicateNames() {
        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        data class Case(
            val name: String,
            val expectedPattern: Regex,
        )

        val cases = listOf(
            Case(
                name = "dialog checks existing preset display names",
                expectedPattern = Regex(
                    """translationPresetNameExists\(pendingSavePresetName, existingPresetNames\)"""
                ),
            ),
            Case(
                name = "duplicate name disables save button",
                expectedPattern = Regex(
                    """val saveNameValid = normalizedTranslationPresetName\(pendingSavePresetName\) != null && !duplicateName"""
                ),
            ),
            Case(
                name = "duplicate name marks text field as error",
                expectedPattern = Regex("""isError = duplicateName"""),
            ),
            Case(
                name = "duplicate name shows helper text",
                expectedPattern = Regex("""settings_translation_preset_name_duplicate"""),
            ),
        )

        cases.forEach { case ->
            assertTrue(case.name, case.expectedPattern.containsMatchIn(source))
        }
    }

    @Test
    fun orientationAutoDetectHelp_mentionsNonHorizontalUseAndStorageTradeoff() {
        data class Case(
            val name: String,
            val resourcePath: String,
            val expectedParts: List<String>,
        )

        val cases = listOf(
            Case(
                name = "default English help",
                resourcePath = "src/main/res/values/strings.xml",
                expectedParts = listOf(
                    "improves non-horizontal text",
                    "vertical layouts",
                    "delete the orientation model package",
                    "improve translation speed",
                ),
            ),
            Case(
                name = "Simplified Chinese help",
                resourcePath = "src/main/res/values-zh-rCN/strings.xml",
                expectedParts = listOf(
                    "横排以外文字",
                    "竖排文字增强",
                    "删除方向模型包",
                    "提升翻译速度",
                ),
            ),
        )

        cases.forEach { case ->
            val summary = stringResourceValue(case.resourcePath, "settings_orient_auto_detect_summary")
            case.expectedParts.forEach { expected ->
                assertTrue("${case.name}: expected '$expected' in '$summary'", summary.contains(expected))
            }
        }
    }

    @Test
    fun settingsSearchIndex_includesOcrAdvancedDbnetThresholds() {
        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        data class SearchCase(
            val name: String,
            val expected: String,
        )

        val cases = listOf(
            SearchCase("search entry label", "settings_search_item_dbnet_advanced"),
            SearchCase("dbnet keyword", "\"dbnet\""),
            SearchCase("probability threshold keyword", "\"prob\""),
            SearchCase("box score keyword", "\"box score\""),
            SearchCase("unclip keyword", "\"unclip\""),
            SearchCase("bubble cluster keyword", "\"cluster\""),
            SearchCase("Chinese threshold keyword", "\"阈值\""),
            SearchCase("Chinese bubble keyword", "\"气泡\""),
        )

        cases.forEach { case ->
            assertTrue(case.name, source.contains(case.expected))
        }

        data class ResourceCase(
            val name: String,
            val resourcePath: String,
            val expected: String,
        )

        listOf(
            ResourceCase(
                name = "default English search label",
                resourcePath = "src/main/res/values/strings.xml",
                expected = "OCR advanced thresholds",
            ),
            ResourceCase(
                name = "Simplified Chinese search label",
                resourcePath = "src/main/res/values-zh-rCN/strings.xml",
                expected = "OCR 高级阈值",
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                stringResourceValue(case.resourcePath, "settings_search_item_dbnet_advanced")
            )
        }
    }

    @Test
    fun checkingPlaceholderIfUnresolved_doesNotOverwriteResolvedStatus() {
        data class Case(
            val name: String,
            val currentStatus: String,
            val expected: String,
        )

        val checking = "Checking..."
        val cases = listOf(
            Case("blank status gets placeholder", "", checking),
            Case("whitespace status gets placeholder", "   ", checking),
            Case("missing status is preserved", "PaddleOCR model missing", "PaddleOCR model missing"),
            Case("ready status is preserved", "Orientation model ready (6600 KB)", "Orientation model ready (6600 KB)"),
            Case("error status is preserved", "Download failed: HTTP 404", "Download failed: HTTP 404"),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                checkingPlaceholderIfUnresolved(case.currentStatus, checking)
            )
        }
    }

    @Test
    fun translationPresetNameExists_isTableDriven() {
        data class Case(
            val name: String,
            val input: String,
            val existingNames: List<String>,
            val expected: Boolean,
        )

        val cases = listOf(
            Case(
                name = "blank name is not treated as duplicate",
                input = "",
                existingNames = listOf("Manga CN"),
                expected = false,
            ),
            Case(
                name = "new name can save",
                input = "Novel CN",
                existingNames = listOf("Manga CN", "Vertical CN"),
                expected = false,
            ),
            Case(
                name = "exact duplicate is blocked",
                input = "Manga CN",
                existingNames = listOf("Manga CN", "Vertical CN"),
                expected = true,
            ),
            Case(
                name = "trimmed duplicate is blocked",
                input = "  Manga CN  ",
                existingNames = listOf("Manga CN"),
                expected = true,
            ),
            Case(
                name = "case-only duplicate is blocked",
                input = "manga cn",
                existingNames = listOf("Manga CN"),
                expected = true,
            ),
            Case(
                name = "trimmed existing display name is blocked",
                input = "Vertical CN",
                existingNames = listOf("  Vertical CN  "),
                expected = true,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                translationPresetNameExists(case.input, case.existingNames)
            )
        }
    }

    @Test
    fun translationPresetUnsavedSlot_keepsSectionHeightStableWhenPresetMatchChanges() {
        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        data class Case(
            val name: String,
            val expectedPattern: Regex,
        )

        val cases = listOf(
            Case(
                name = "preset section always renders the unsaved slot",
                expectedPattern = Regex("""TranslationPresetUnsavedSlot\([\s\S]*?preset = unsavedPreset"""),
            ),
            Case(
                name = "unsaved slot has fixed height",
                expectedPattern = Regex("""private fun TranslationPresetUnsavedSlot\([\s\S]*?\.height\(160\.dp\)"""),
            ),
            Case(
                name = "unsaved row fills the stable slot when present",
                expectedPattern = Regex("""UnsavedTranslationPresetRow\([\s\S]*?modifier = Modifier\.fillMaxSize\(\)"""),
            ),
            Case(
                name = "matched preset placeholder is centered in stable slot",
                expectedPattern = Regex("""contentAlignment = Alignment\.Center[\s\S]*?settings_translation_preset_matched_placeholder"""),
            ),
        )

        cases.forEach { case ->
            assertTrue(case.name, case.expectedPattern.containsMatchIn(source))
        }
    }

    @Test
    fun translationPresetMatchedPlaceholder_hasReadableCenteredStatusText() {
        data class Case(
            val name: String,
            val resourcePath: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "default English placeholder",
                resourcePath = "src/main/res/values/strings.xml",
                expected = "Current settings match an existing preset.",
            ),
            Case(
                name = "Simplified Chinese placeholder",
                resourcePath = "src/main/res/values-zh-rCN/strings.xml",
                expected = "当前设置已匹配现有方案",
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                stringResourceValue(case.resourcePath, "settings_translation_preset_matched_placeholder")
            )
        }
    }

    @Test
    fun namedTranslationPresetOrNull_isTableDriven() {
        data class Case(
            val name: String,
            val input: String,
            val id: String,
            val expectedName: String?,
            val expectedShortName: String?,
        )

        val base = TranslationPreset(
            id = TranslationPresetCatalog.UNSAVED_DRAFT_ID,
            name = "Unsaved preset",
            shortName = "Unsaved",
            model = "base-model",
        )
        val cases = listOf(
            Case(
                name = "blank name cannot save",
                input = "",
                id = "custom_blank",
                expectedName = null,
                expectedShortName = null,
            ),
            Case(
                name = "whitespace name cannot save",
                input = "   ",
                id = "custom_whitespace",
                expectedName = null,
                expectedShortName = null,
            ),
            Case(
                name = "name is trimmed before saving",
                input = "  Manga JP  ",
                id = "custom_manga_jp",
                expectedName = "Manga JP",
                expectedShortName = "Manga JP",
            ),
            Case(
                name = "short name is capped at eight characters",
                input = "LongPresetName",
                id = "custom_long",
                expectedName = "LongPresetName",
                expectedShortName = "LongPres",
            ),
        )

        cases.forEach { case ->
            val renamed = namedTranslationPresetOrNull(base, case.input, case.id)
            if (case.expectedName == null) {
                assertEquals(case.name, null, renamed)
            } else {
                val preset = requireNotNull(renamed) { case.name }
                assertEquals(case.name, case.expectedName, preset.name)
                assertEquals(case.name, case.expectedShortName, preset.shortName)
                assertEquals(case.name, case.id, preset.id)
                assertEquals(case.name, base.model, preset.model)
            }
        }
    }

    @Test
    fun translationPresetVisibleItems_collapsesOnlyWhenMoreThanThree() {
        data class Case(
            val name: String,
            val count: Int,
            val expanded: Boolean,
            val expected: List<Int>,
            val toggleVisible: Boolean,
        )

        val cases = listOf(
            Case("empty list has no toggle", 0, false, emptyList(), false),
            Case("three presets stay fully visible", 3, false, listOf(1, 2, 3), false),
            Case("four presets collapse to first three", 4, false, listOf(1, 2, 3), true),
            Case("four presets expanded show all", 4, true, listOf(1, 2, 3, 4), true),
        )

        cases.forEach { case ->
            val items = (1..case.count).toList()
            assertEquals(
                case.name,
                case.expected,
                translationPresetVisibleItems(items, case.expanded)
            )
            assertEquals(
                case.name,
                case.toggleVisible,
                translationPresetCollapseToggleVisible(items.size)
            )
        }
    }

    @Test
    fun translationPresetCollapseToggle_usesCenteredArrowButton() {
        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        data class Case(
            val name: String,
            val pattern: Regex,
        )

        val cases = listOf(
            Case(
                name = "toggle row is centered",
                pattern = Regex(
                    """if \(translationPresetCollapseToggleVisible\(allPresets\.size\)\) \{[\s\S]*?horizontalArrangement = Arrangement\.Center"""
                ),
            ),
            Case(
                name = "toggle shows expand arrow icon",
                pattern = Regex("""imageVector = Icons\.Default\.ExpandMore"""),
            ),
            Case(
                name = "toggle rotates arrow when expanded",
                pattern = Regex("""rotationZ = if \(presetsExpanded\) 180f else 0f"""),
            ),
        )

        cases.forEach { case ->
            assertTrue(case.name, case.pattern.containsMatchIn(source))
        }
    }

    @Test
    fun overlayFontDeleteTipBeforeImport_onlyShowsForSystemDefaultOnly() {
        val validFont = "${"a".repeat(64)}.ttf"
        data class Case(
            val name: String,
            val currentFileName: String,
            val fonts: List<OverlayFontEntry>,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("system default with no imports shows first-use tip", "", emptyList(), true),
            Case(
                name = "selected imported font does not show first-use tip",
                currentFileName = validFont,
                fonts = listOf(OverlayFontEntry(validFont, "Font.ttf")),
                expected = false,
            ),
            Case(
                name = "unselected imported font still counts as already imported",
                currentFileName = "",
                fonts = listOf(OverlayFontEntry(validFont, "Font.ttf")),
                expected = false,
            ),
            Case(
                name = "invalid stored font list is treated as system default only",
                currentFileName = "",
                fonts = listOf(OverlayFontEntry("bad.ttf", "Bad.ttf")),
                expected = true,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                shouldShowOverlayFontDeleteTipBeforeImport(
                    currentFileName = case.currentFileName,
                    fonts = case.fonts,
                )
            )
        }
    }

    @Test
    fun overlayFontDeleteTipAckLabel_showsCountdownBeforeEnabledText() {
        data class Case(
            val name: String,
            val countdown: Int,
            val expected: String,
        )

        val cases = listOf(
            Case("three seconds remaining", 3, "(3) 我已知晓"),
            Case("two seconds remaining", 2, "(2) 我已知晓"),
            Case("one second remaining", 1, "(1) 我已知晓"),
            Case("countdown done", 0, "我已知晓"),
            Case("negative countdown is treated as done", -1, "我已知晓"),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                overlayFontDeleteTipAckLabel("我已知晓", case.countdown)
            )
        }
    }

    @Test
    fun overlayFontDeleteUi_usesLongPressAndCenteredCountdownAcknowledgement() {
        data class Case(
            val name: String,
            val expectedPattern: Regex,
        )

        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val cases = listOf(
            Case(
                name = "system default font chip uses same component as imported fonts",
                expectedPattern = Regex(
                    """OverlayFontChip\([\s\S]*?selected = overlayFontFileName\.isBlank\(\),[\s\S]*?label = defaultOverlayFontName"""
                ),
            ),
            Case(
                name = "imported font row uses custom chip instead of nested FilterChip gesture",
                expectedPattern = Regex("""overlayFontChipEntries\.forEach \{ font ->[\s\S]*?OverlayFontChip\("""),
            ),
            Case(
                name = "font chip row vertically centers all chips",
                expectedPattern = Regex("""Row\([\s\S]*?verticalAlignment = Alignment\.CenterVertically"""),
            ),
            Case(
                name = "font chips use fixed height",
                expectedPattern = Regex("""private fun OverlayFontChip\([\s\S]*?\.height\(36\.dp\)"""),
            ),
            Case(
                name = "custom font chip routes long press to delete confirmation",
                expectedPattern = Regex("""onLongClick = \{ pendingOverlayFontDelete = font \}"""),
            ),
            Case(
                name = "custom font chip owns tap and long press in one recognizer",
                expectedPattern = Regex(
                    """detectTapGestures\([\s\S]*?onTap = \{ onClick\(\) \},[\s\S]*?onLongPress = \{ onLongClick\(\) \}"""
                ),
            ),
            Case(
                name = "two-line typography preview has fixed height to avoid scroll jumps",
                expectedPattern = Regex("""AndroidView\([\s\S]*?\.height\(80\.dp\)"""),
            ),
            Case(
                name = "font message keeps stable reserved height",
                expectedPattern = Regex("""overlayFontMessage\?\.let[\s\S]*?heightIn\(min = 20\.dp\)|heightIn\(min = 20\.dp\)[\s\S]*?overlayFontMessage\?\.let"""),
            ),
            Case(
                name = "first-use acknowledgement counts down once per second",
                expectedPattern = Regex("""delay\(1000L\)"""),
            ),
            Case(
                name = "ack button is disabled until countdown finishes",
                expectedPattern = Regex("""enabled = overlayFontDeleteTipCountdown == 0"""),
            ),
            Case(
                name = "ack button is centered",
                expectedPattern = Regex(
                    """confirmButton = \{[\s\S]*?modifier = Modifier\.fillMaxWidth\(\),[\s\S]*?horizontalArrangement = Arrangement\.Center"""
                ),
            ),
            Case(
                name = "ack button uses countdown label",
                expectedPattern = Regex("""overlayFontDeleteTipAckLabel\("""),
            ),
        )

        cases.forEach { case ->
            assertTrue(case.name, case.expectedPattern.containsMatchIn(source))
        }
        assertFalse(
            "imported font chip should not layer pointerInput on top of FilterChip",
            source.contains("Modifier.pointerInput(font.fileName)")
        )
    }

    @Test
    fun overlayFontListPersistence_isThreadedThroughSettingsSave() {
        data class Case(
            val name: String,
            val source: String,
            val expectedPattern: Regex,
        )

        val settingsScreen = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val settingsViewModel = File("src/main/java/com/gameocr/app/ui/SettingsViewModel.kt").readText()
        val settingsRepository = File("src/main/java/com/gameocr/app/data/SettingsRepository.kt").readText()
        val cases = listOf(
            Case(
                name = "settings snapshot includes full imported font list",
                source = settingsScreen,
                expectedPattern = Regex("""fun buildSnapshot\(\): Settings = \(initialSettings \?: Settings\(\)\)\.copy\([\s\S]*?overlayFonts = overlayFontEntries"""),
            ),
            Case(
                name = "settings save call passes full imported font list",
                source = settingsScreen,
                expectedPattern = Regex("""viewModel\.save\([\s\S]*?overlayFonts = overlayFontEntries"""),
            ),
            Case(
                name = "view model save accepts full imported font list",
                source = settingsViewModel,
                expectedPattern = Regex("""overlayFonts: List<OverlayFontEntry>"""),
            ),
            Case(
                name = "view model update preserves full imported font list",
                source = settingsViewModel,
                expectedPattern = Regex("""overlayFonts = overlayFonts"""),
            ),
            Case(
                name = "repository has a dedicated json key for imported font list",
                source = settingsRepository,
                expectedPattern = Regex("""OverlayFonts = stringPreferencesKey\("overlay_fonts_json"\)"""),
            ),
            Case(
                name = "repository writes normalized imported font list",
                source = settingsRepository,
                expectedPattern = Regex("""json\.encodeToString\(\s*OverlayFontPolicy\.normalizeImportedFonts\(next\.overlayFonts\)"""),
            ),
            Case(
                name = "repository reads normalized imported font list",
                source = settingsRepository,
                expectedPattern = Regex("""json\.decodeFromString<List<OverlayFontEntry>>\(raw\)"""),
            ),
        )

        cases.forEach { case ->
            assertTrue(case.name, case.expectedPattern.containsMatchIn(case.source))
        }
    }

    @Test
    fun overlayFontDeleteTipMessage_isShortWithoutCountdownExplanation() {
        data class Case(
            val name: String,
            val resourcePath: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "default English tip",
                resourcePath = "src/main/res/values/strings.xml",
                expected = "Long-press an imported font to delete it.",
            ),
            Case(
                name = "Simplified Chinese tip",
                resourcePath = "src/main/res/values-zh-rCN/strings.xml",
                expected = "长按导入的字体可以删除。",
            ),
        )

        cases.forEach { case ->
            val message = stringResourceValue(case.resourcePath, "settings_overlay_font_delete_tip_message")
            assertEquals(case.name, case.expected, message)
            assertFalse(case.name, message.contains("3 秒"))
            assertFalse(case.name, message.contains("3 seconds"))
        }
    }

    @Test
    fun localLlmDownloadEnabled_disablesWhenModelAlreadyReady() {
        data class Case(
            val name: String,
            val downloading: Boolean,
            val deviceCapable: Boolean,
            val modelReady: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("ready device can download missing model", false, true, false, true),
            Case("already ready model disables download", false, true, true, false),
            Case("download in progress disables download", true, true, false, false),
            Case("unsupported device disables download", false, false, false, false),
            Case("unsupported ready model stays disabled", false, false, true, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                localLlmDownloadEnabled(
                    downloading = case.downloading,
                    deviceCapable = case.deviceCapable,
                    modelReady = case.modelReady,
                )
            )
        }
    }

    @Test
    fun downloadableModelDownloadEnabled_disablesWhenModelAlreadyReady() {
        data class Case(
            val name: String,
            val downloading: Boolean,
            val modelReady: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("missing model can download", false, false, true),
            Case("ready model disables download", false, true, false),
            Case("download in progress disables download", true, false, false),
            Case("ready model downloading stays disabled", true, true, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                downloadableModelDownloadEnabled(
                    downloading = case.downloading,
                    modelReady = case.modelReady,
                )
            )
        }
    }

    @Test
    fun downloadableModelImportEnabled_disablesWhenModelAlreadyReady() {
        data class Case(
            val name: String,
            val downloading: Boolean,
            val modelReady: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("missing idle model can import", false, false, true),
            Case("ready model disables import", false, true, false),
            Case("download in progress disables import", true, false, false),
            Case("ready downloading model stays disabled", true, true, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                downloadableModelImportEnabled(case.downloading, case.modelReady),
            )
        }
    }

    @Test
    fun modelDownloadNetworkWarning_isTableDriven() {
        data class Case(
            val name: String,
            val hasWifi: Boolean,
            val hasEthernet: Boolean,
            val hasCellular: Boolean,
            val expectedKind: ModelDownloadNetworkKind,
            val expectedWarning: ModelDownloadNetworkWarning?,
            val expectedMessageRes: Int?,
        )

        val cases = listOf(
            Case(
                name = "wifi is safe to start directly",
                hasWifi = true,
                hasEthernet = false,
                hasCellular = false,
                expectedKind = ModelDownloadNetworkKind.WIFI,
                expectedWarning = null,
                expectedMessageRes = null,
            ),
            Case(
                name = "ethernet is safe to start directly",
                hasWifi = false,
                hasEthernet = true,
                hasCellular = false,
                expectedKind = ModelDownloadNetworkKind.WIFI,
                expectedWarning = null,
                expectedMessageRes = null,
            ),
            Case(
                name = "mobile data requires confirmation",
                hasWifi = false,
                hasEthernet = false,
                hasCellular = true,
                expectedKind = ModelDownloadNetworkKind.CELLULAR,
                expectedWarning = ModelDownloadNetworkWarning.CELLULAR,
                expectedMessageRes = R.string.settings_model_download_network_warning_cellular_format,
            ),
            Case(
                name = "wifi wins when transports are mixed",
                hasWifi = true,
                hasEthernet = false,
                hasCellular = true,
                expectedKind = ModelDownloadNetworkKind.WIFI,
                expectedWarning = null,
                expectedMessageRes = null,
            ),
            Case(
                name = "unknown network is conservative",
                hasWifi = false,
                hasEthernet = false,
                hasCellular = false,
                expectedKind = ModelDownloadNetworkKind.UNKNOWN,
                expectedWarning = ModelDownloadNetworkWarning.UNKNOWN,
                expectedMessageRes = R.string.settings_model_download_network_warning_unknown_format,
            ),
        )

        cases.forEach { case ->
            val kind = classifyModelDownloadNetwork(
                hasWifi = case.hasWifi,
                hasEthernet = case.hasEthernet,
                hasCellular = case.hasCellular,
            )
            assertEquals(case.name, case.expectedKind, kind)
            val warning = modelDownloadNetworkWarningFor(kind)
            assertEquals(case.name, case.expectedWarning, warning)
            assertEquals(
                case.name,
                case.expectedMessageRes,
                warning?.let(::modelDownloadNetworkWarningMessageRes),
            )
        }
    }

    @Test
    fun modelDownloadProgressFraction_isTableDriven() {
        data class Case(
            val name: String,
            val downloaded: Long,
            val total: Long,
            val expected: Float?,
        )

        val cases = listOf(
            Case("unknown total is indeterminate", 12L, -1L, null),
            Case("zero total is indeterminate", 0L, 0L, null),
            Case("download just started", 0L, 100L, 0f),
            Case("download is halfway", 50L, 100L, 0.5f),
            Case("download is complete", 100L, 100L, 1f),
            Case("downloaded bytes are clamped below zero", -5L, 100L, 0f),
            Case("downloaded bytes are clamped above total", 150L, 100L, 1f),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                modelDownloadProgressFraction(case.downloaded, case.total),
            )
        }
    }

    @Test
    fun translationPresetDownloadModelLabels_useActualDownloadableModelNames() {
        data class Case(
            val name: String,
            val issues: List<TranslationPresetModelIssue>,
            val expected: List<String>,
        )

        val paddleLabels = mapOf(
            PaddleModelVersion.V6_TINY to "PP-OCRv6 tiny",
            PaddleModelVersion.V6_SMALL to "PP-OCRv6 small",
            PaddleModelVersion.V6_MEDIUM to "PP-OCRv6 medium",
        )
        val cases = listOf(
            Case(
                name = "custom Hy-MT2 Paddle preset downloads concrete Paddle and orientation models",
                issues = listOf(
                    TranslationPresetModelIssue(
                        kind = TranslationPresetModelIssueKind.LOCAL_LLM_MISSING,
                        llmModelKind = LlmModelKind.HY_MT2_1_8B_Q4_K_M,
                    ),
                    TranslationPresetModelIssue(
                        kind = TranslationPresetModelIssueKind.PADDLE_MISSING,
                        paddleModelVersion = PaddleModelVersion.V6_TINY,
                    ),
                    TranslationPresetModelIssue(TranslationPresetModelIssueKind.ORIENTATION_MISSING),
                ),
                expected = listOf(
                    LlmModelKind.HY_MT2_1_8B_Q4_K_M.displayName,
                    "PP-OCRv6 tiny",
                    "ONNX orientation models",
                ),
            ),
            Case(
                name = "medium Paddle model issue uses concrete medium label",
                issues = listOf(
                    TranslationPresetModelIssue(
                        kind = TranslationPresetModelIssueKind.PADDLE_MISSING,
                        paddleModelVersion = PaddleModelVersion.V6_MEDIUM,
                    ),
                ),
                expected = listOf("PP-OCRv6 medium"),
            ),
            Case(
                name = "manga preset downloads Sakura Manga OCR and one Paddle model",
                issues = listOf(
                    TranslationPresetModelIssue(
                        kind = TranslationPresetModelIssueKind.LOCAL_LLM_MISSING,
                        llmModelKind = LlmModelKind.SAKURA_1_5B_Q4,
                    ),
                    TranslationPresetModelIssue(TranslationPresetModelIssueKind.MANGA_OCR_MISSING),
                    TranslationPresetModelIssue(
                        kind = TranslationPresetModelIssueKind.PADDLE_MISSING,
                        paddleModelVersion = PaddleModelVersion.V6_SMALL,
                    ),
                ),
                expected = listOf(
                    LlmModelKind.SAKURA_1_5B_Q4.displayName,
                    "manga-ocr 2025 (l0wgear)",
                    "PP-OCRv6 small",
                ),
            ),
            Case(
                name = "duplicate issues are listed once",
                issues = listOf(
                    TranslationPresetModelIssue(
                        kind = TranslationPresetModelIssueKind.PADDLE_MISSING,
                        paddleModelVersion = PaddleModelVersion.V6_SMALL,
                    ),
                    TranslationPresetModelIssue(
                        kind = TranslationPresetModelIssueKind.PADDLE_MISSING,
                        paddleModelVersion = PaddleModelVersion.V6_SMALL,
                    ),
                ),
                expected = listOf("PP-OCRv6 small"),
            ),
            Case(
                name = "unsupported device issue is not a downloadable model",
                issues = listOf(
                    TranslationPresetModelIssue(TranslationPresetModelIssueKind.LOCAL_LLM_UNSUPPORTED),
                ),
                expected = emptyList(),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                translationPresetDownloadModelLabels(
                    issues = case.issues,
                    paddleModelLabel = { version -> requireNotNull(paddleLabels[version]) },
                    mangaOcrModelLabel = "manga-ocr 2025 (l0wgear)",
                    orientationModelLabel = "ONNX orientation models",
                )
            )
        }
    }

    @Test
    fun translationPresetDownloadRequest_usesConcreteModelLabelForNetworkWarning() {
        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()

        assertTrue(
            source.contains("val downloadModelLabel = translationPresetDownloadModelLabel(context, issues)")
        )
        assertFalse(
            source.contains(
                "requestModelDownload(context.getString(R.string.settings_translation_preset_download_models))"
            )
        )
    }

    @Test
    fun translationPresetModelDownloadState_isRowScoped() {
        data class Case(
            val name: String,
            val presetId: String,
            val activePresetDownloadId: String?,
            val modelDownloading: Boolean,
            val issues: List<TranslationPresetModelIssue>,
            val expectedState: TranslationPresetModelDownloadState,
            val expectedEnabled: Boolean,
            val expectedBusyHint: Boolean,
        )

        val downloadableIssues = listOf(
            TranslationPresetModelIssue(TranslationPresetModelIssueKind.PADDLE_MISSING)
        )
        val unsupportedIssues = listOf(
            TranslationPresetModelIssue(TranslationPresetModelIssueKind.LOCAL_LLM_UNSUPPORTED)
        )
        val cases = listOf(
            Case(
                name = "idle row with missing downloadable model can download",
                presetId = "manga",
                activePresetDownloadId = null,
                modelDownloading = false,
                issues = downloadableIssues,
                expectedState = TranslationPresetModelDownloadState.IDLE,
                expectedEnabled = true,
                expectedBusyHint = false,
            ),
            Case(
                name = "active preset row shows itself as downloading",
                presetId = "manga",
                activePresetDownloadId = "manga",
                modelDownloading = true,
                issues = downloadableIssues,
                expectedState = TranslationPresetModelDownloadState.CURRENT_PRESET,
                expectedEnabled = false,
                expectedBusyHint = false,
            ),
            Case(
                name = "other preset row is blocked without looking like it is downloading",
                presetId = "vertical",
                activePresetDownloadId = "manga",
                modelDownloading = true,
                issues = downloadableIssues,
                expectedState = TranslationPresetModelDownloadState.BLOCKED_BY_OTHER_DOWNLOAD,
                expectedEnabled = false,
                expectedBusyHint = true,
            ),
            Case(
                name = "manual model download also blocks preset model downloads",
                presetId = "vertical",
                activePresetDownloadId = null,
                modelDownloading = true,
                issues = downloadableIssues,
                expectedState = TranslationPresetModelDownloadState.BLOCKED_BY_OTHER_DOWNLOAD,
                expectedEnabled = false,
                expectedBusyHint = true,
            ),
            Case(
                name = "non-downloadable issue stays disabled without busy hint",
                presetId = "hymt2",
                activePresetDownloadId = "manga",
                modelDownloading = true,
                issues = unsupportedIssues,
                expectedState = TranslationPresetModelDownloadState.BLOCKED_BY_OTHER_DOWNLOAD,
                expectedEnabled = false,
                expectedBusyHint = false,
            ),
        )

        cases.forEach { case ->
            val state = translationPresetModelDownloadState(
                presetId = case.presetId,
                activePresetDownloadId = case.activePresetDownloadId,
                modelDownloading = case.modelDownloading,
            )
            assertEquals(case.name, case.expectedState, state)
            assertEquals(
                case.name,
                case.expectedEnabled,
                translationPresetModelDownloadEnabled(
                    issues = case.issues,
                    downloadState = state,
                )
            )
            assertEquals(
                case.name,
                case.expectedBusyHint,
                translationPresetOtherDownloadHintVisible(
                    issues = case.issues,
                    downloadState = state,
                )
            )
        }
    }

    @Test
    fun translationPresetModelIssues_blocksCustomHyMt2PaddlePresetUntilRequiredModelsReady() {
        val preset = TranslationPreset(
            id = "custom_hymt2_paddle",
            name = "Custom Hy-MT2 Paddle",
            shortName = "Hy-Paddle",
            sourceLang = "zh-TW",
            targetLang = "zh-CN",
            ocrEngine = OcrEngineKind.PADDLE_ONNX,
            paddleModelVersion = PaddleModelVersion.V6_TINY,
            translatorEngine = TranslatorEngine.LOCAL_HY_MT2,
            textOrientationAutoDetect = true,
        )

        val issues = translationPresetModelIssues(
            preset = preset,
            localLlmDeviceCapable = true,
            llmModelReady = { false },
            paddleModelReady = { false },
            mangaOcrModelReady = false,
            orientationModelReady = false,
        )

        assertEquals(
            listOf(
                TranslationPresetModelIssueKind.LOCAL_LLM_MISSING,
                TranslationPresetModelIssueKind.PADDLE_MISSING,
                TranslationPresetModelIssueKind.ORIENTATION_MISSING,
            ),
            issues.map { it.kind }
        )
        assertEquals(
            LlmModelKind.HY_MT2_1_8B_Q4_K_M,
            issues.first { it.kind == TranslationPresetModelIssueKind.LOCAL_LLM_MISSING }.llmModelKind
        )
        assertEquals(
            PaddleModelVersion.V6_TINY,
            issues.first { it.kind == TranslationPresetModelIssueKind.PADDLE_MISSING }.paddleModelVersion
        )
        assertFalse(translationPresetCanApply(issues))
        assertTrue(
            translationPresetModelDownloadEnabled(
                issues = issues,
                downloadState = TranslationPresetModelDownloadState.IDLE,
            )
        )
        assertFalse(
            translationPresetModelDownloadEnabled(
                issues = issues,
                downloadState = TranslationPresetModelDownloadState.CURRENT_PRESET,
            )
        )
    }

    @Test
    fun translationPresetModelIssues_allowsCustomHyMt2PaddlePresetWhenModelsReady() {
        val preset = TranslationPreset(
            id = "custom_hymt2_paddle",
            name = "Custom Hy-MT2 Paddle",
            shortName = "Hy-Paddle",
            sourceLang = "zh-TW",
            targetLang = "zh-CN",
            ocrEngine = OcrEngineKind.PADDLE_ONNX,
            paddleModelVersion = PaddleModelVersion.V6_TINY,
            translatorEngine = TranslatorEngine.LOCAL_HY_MT2,
            textOrientationAutoDetect = true,
        )

        val issues = translationPresetModelIssues(
            preset = preset,
            localLlmDeviceCapable = true,
            llmModelReady = { it == LlmModelKind.HY_MT2_1_8B_Q4_K_M },
            paddleModelReady = { it == PaddleModelVersion.V6_TINY },
            mangaOcrModelReady = false,
            orientationModelReady = true,
        )

        assertTrue(issues.isEmpty())
        assertTrue(translationPresetCanApply(issues))
        assertFalse(
            translationPresetModelDownloadEnabled(
                issues = issues,
                downloadState = TranslationPresetModelDownloadState.IDLE,
            )
        )
    }

    @Test
    fun translationPresetModelIssues_blocksLocalPresetOnUnsupportedDevice() {
        val preset = TranslationPreset(
            id = "custom_hymt2",
            name = "Custom Hy-MT2",
            shortName = "Hy-MT2",
            translatorEngine = TranslatorEngine.LOCAL_HY_MT2
        )

        val issues = translationPresetModelIssues(
            preset = preset,
            localLlmDeviceCapable = false,
            llmModelReady = { false },
            paddleModelReady = { true },
            mangaOcrModelReady = true,
            orientationModelReady = true,
        )

        assertEquals(
            listOf(TranslationPresetModelIssueKind.LOCAL_LLM_UNSUPPORTED),
            issues.map { it.kind }
        )
        assertFalse(translationPresetCanApply(issues))
        assertFalse(
            translationPresetModelDownloadEnabled(
                issues = issues,
                downloadState = TranslationPresetModelDownloadState.IDLE,
            )
        )
    }

    @Test
    fun translationPresetModelIssues_mangaPresetRequiresSakuraMangaAndPaddleDetector() {
        val preset = requireNotNull(
            TranslationPresetCatalog.find(
                custom = emptyList(),
                id = TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH
            )
        )

        val issues = translationPresetModelIssues(
            preset = preset,
            localLlmDeviceCapable = true,
            llmModelReady = { false },
            paddleModelReady = { false },
            mangaOcrModelReady = false,
            orientationModelReady = true,
        )

        assertEquals(
            listOf(
                TranslationPresetModelIssueKind.LOCAL_LLM_MISSING,
                TranslationPresetModelIssueKind.MANGA_OCR_MISSING,
                TranslationPresetModelIssueKind.PADDLE_MISSING,
            ),
            issues.map { it.kind }
        )
        assertEquals(
            LlmModelKind.SAKURA_1_5B_Q4,
            issues.first { it.kind == TranslationPresetModelIssueKind.LOCAL_LLM_MISSING }.llmModelKind
        )
        assertEquals(
            PaddleModelVersion.V5_MOBILE,
            issues.first { it.kind == TranslationPresetModelIssueKind.PADDLE_MISSING }.paddleModelVersion
        )
    }

    @Test
    fun cloudOcrUpscaleWarningVisible_onlyWarnsForCloudEnginesWhenUpscaleIsEnabled() {
        data class Case(
            val name: String,
            val engine: OcrEngineKind,
            val upscale2x: Boolean,
            val expected: Boolean,
        )

        val cases = listOf(
            Case("Baidu cloud OCR warns when upscale is enabled", OcrEngineKind.BAIDU, true, true),
            Case("Tencent cloud OCR warns when upscale is enabled", OcrEngineKind.TENCENT, true, true),
            Case("Youdao cloud OCR warns when upscale is enabled", OcrEngineKind.YOUDAO, true, true),
            Case("PaddleOCR AI Studio cloud OCR warns when upscale is enabled", OcrEngineKind.PADDLE_AI_STUDIO, true, true),
            Case("Umi local HTTP OCR is not cloud cost warning", OcrEngineKind.UMI_OCR, true, false),
            Case("Luna local HTTP OCR is not cloud cost warning", OcrEngineKind.LUNA_OCR, true, false),
            Case("Baidu cloud OCR does not warn when upscale is off", OcrEngineKind.BAIDU, false, false),
            Case("ML Kit auto is on-device", OcrEngineKind.ML_KIT_AUTO, true, false),
            Case("PaddleOCR is on-device", OcrEngineKind.PADDLE_ONNX, true, false),
            Case("Manga OCR is on-device", OcrEngineKind.MANGA_OCR_JA, true, false),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                cloudOcrUpscaleWarningVisible(
                    engine = case.engine,
                    upscale2x = case.upscale2x,
                )
            )
        }
    }

    @Test
    fun cleartextHostsWithLocalOcrUrls_appendsHttpHostsAndDedupes() {
        data class Case(
            val name: String,
            val hosts: List<String>,
            val umiUrl: String,
            val lunaUrl: String,
            val expected: List<String>,
        )

        val cases = listOf(
            Case(
                name = "umi http hostname is appended",
                hosts = emptyList(),
                umiUrl = "http://pc-name:1224/api/ocr",
                lunaUrl = "",
                expected = listOf("pc-name"),
            ),
            Case(
                name = "umi http ip is appended",
                hosts = listOf("nas.local"),
                umiUrl = "http://192.168.0.2:1224",
                lunaUrl = "",
                expected = listOf("nas.local", "192.168.0.2"),
            ),
            Case(
                name = "luna http hostname is appended",
                hosts = emptyList(),
                umiUrl = "",
                lunaUrl = "http://luna-pc:3456/api/ocr",
                expected = listOf("luna-pc"),
            ),
            Case(
                name = "both local OCR hosts are appended",
                hosts = listOf("nas.local"),
                umiUrl = "http://umi-pc:1224/api/ocr",
                lunaUrl = "http://luna-pc:3456/api/ocr",
                expected = listOf("nas.local", "umi-pc", "luna-pc"),
            ),
            Case(
                name = "duplicate hosts are deduped case insensitively",
                hosts = listOf("PC-NAME"),
                umiUrl = "http://pc-name:1224/api/ocr",
                lunaUrl = "http://pc-name:3456/api/ocr",
                expected = listOf("PC-NAME"),
            ),
            Case(
                name = "https host is not cleartext",
                hosts = listOf("pc-name"),
                umiUrl = "https://ocr.example.com/api/ocr",
                lunaUrl = "https://luna.example.com/api/ocr",
                expected = listOf("pc-name"),
            ),
            Case(
                name = "invalid url keeps explicit hosts only",
                hosts = listOf("pc-name", " "),
                umiUrl = "not a url",
                lunaUrl = "also not a url",
                expected = listOf("pc-name"),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                cleartextHostsWithLocalOcrUrls(case.hosts, case.umiUrl, case.lunaUrl)
            )
        }
    }

    @Test
    fun retiredMangaAdvancedSettings_areHiddenAndForcedToZeroAcrossLayers() {
        data class Case(
            val name: String,
            val sourcePath: String,
            val marker: String,
            val expectedPresent: Boolean,
        )

        val cases = listOf(
            Case(
                "bubble gap control is hidden",
                "src/main/java/com/gameocr/app/ui/SettingsScreen.kt",
                "R.string.settings_dbnet_gap_label",
                false,
            ),
            Case(
                "crop padding control is hidden",
                "src/main/java/com/gameocr/app/ui/SettingsScreen.kt",
                "R.string.settings_manga_crop_padding_label",
                false,
            ),
            Case(
                "view model saves bubble gap as zero",
                "src/main/java/com/gameocr/app/ui/SettingsViewModel.kt",
                "bubbleClusterGap = MangaOcrAdvancedSettingsPolicy.BUBBLE_CLUSTER_GAP",
                true,
            ),
            Case(
                "view model saves crop padding as zero",
                "src/main/java/com/gameocr/app/ui/SettingsViewModel.kt",
                "mangaOcrCropPaddingPx = MangaOcrAdvancedSettingsPolicy.CROP_PADDING_PX",
                true,
            ),
            Case(
                "Manga OCR runtime ignores stored bubble gap",
                "src/main/java/com/gameocr/app/ocr/MangaOcrEngine.kt",
                "MangaOcrAdvancedSettingsPolicy.effectiveBubbleClusterGap(",
                true,
            ),
            Case(
                "Manga OCR runtime ignores stored crop padding",
                "src/main/java/com/gameocr/app/ocr/MangaOcrEngine.kt",
                "MangaOcrAdvancedSettingsPolicy.effectiveCropPaddingPx(",
                true,
            ),
            Case(
                "legacy DataStore values have a startup migration",
                "src/main/java/com/gameocr/app/data/SettingsRepository.kt",
                "migrateRetiredMangaOcrAdvancedSettingsIfNeeded",
                true,
            ),
        )

        cases.forEach { case ->
            val source = listOf(
                File(case.sourcePath),
                File("app", case.sourcePath),
            ).firstOrNull(File::isFile)?.readText()
                ?: error("Source file not found: ${case.sourcePath}")
            assertEquals(case.name, case.expectedPresent, source.contains(case.marker))
        }
    }

    private fun stringResourceValue(resourcePath: String, resourceName: String): String {
        val file = listOf(
            File(resourcePath),
            File("app", resourcePath),
        ).firstOrNull { it.isFile }
            ?: error("Resource file not found: $resourcePath")
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        for (i in 0 until nodes.length) {
            val element = nodes.item(i)
            if (element.attributes.getNamedItem("name")?.nodeValue == resourceName) {
                return element.textContent
            }
        }
        error("String resource not found: $resourceName in ${file.path}")
    }
}
