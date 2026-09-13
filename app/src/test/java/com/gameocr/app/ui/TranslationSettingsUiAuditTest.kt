package com.gameocr.app.ui

import com.gameocr.app.data.RenderMode
import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.RemoteImageDetail
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSettingsUiAuditTest {
    private val source by lazy {
        sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun adjacentBoxMerge_isAvailableInEveryRenderMode_tableDriven() {
        data class Case(val name: String, val renderMode: RenderMode)

        listOf(
            Case("overlay blocks", RenderMode.BLOCKS),
            Case("floating window", RenderMode.FLOATING_WINDOW),
        ).forEach { case ->
            assertTrue(case.name, adjacentBoxMergeAvailableIn(case.renderMode))
        }
    }

    @Test
    fun persistedRequestOptions_areHydratedBeforeSettingsBecomeLoaded() {
        val loadStart = source.indexOf("LaunchedEffect(Unit) {")
        val loadEnd = source.indexOf("initialSettings = s", startIndex = loadStart)
        assertTrue("settings load block", loadStart >= 0 && loadEnd > loadStart)

        val loadBlock = source.substring(loadStart, loadEnd)
        assertTrue(
            "request options must be restored from persisted settings",
            loadBlock.contains("openAiRequestOptions = s.openAiRequestOptions"),
        )
    }

    @Test
    fun mergeSwitch_isTheOnlyTextGroupingControl_tableDriven() {
        data class Case(val name: String, val marker: String)

        listOf(
            Case(
                "merge switch starts from the Settings default",
                "mutableStateOf(Settings().mergeAdjacentBlocks)",
            ),
            Case(
                "merge switch is not rendered before persisted settings load",
                "if (settingsLoaded && adjacentBoxMergeAvailableIn(renderMode)) {\n                        SwitchRow(stringResource(R.string.settings_merge_adjacent)",
            ),
        ).forEach { case -> assertTrue(case.name, source.contains(case.marker)) }
        listOf(
            "crossLineContextTranslation",
            "settings_cross_line_context",
            "disableCrossLineContextTranslation",
        ).forEach { removed -> assertFalse("removed grouping control: $removed", source.contains(removed)) }
    }

    @Test
    fun compatibleRequestDefaultsReset_requiresExplicitConfirmation_tableDriven() {
        val promptStart = source.indexOf("private fun OpenAiPromptSettings(")
        val promptEnd = source.indexOf("private fun ", startIndex = promptStart + 1)
        assertTrue("prompt settings start", promptStart >= 0)
        assertTrue("prompt settings end", promptEnd > promptStart)
        val promptSection = source.substring(promptStart, promptEnd)

        data class Case(val name: String, val marker: String)
        listOf(
            Case("button opens confirmation", "onClick = { showResetRequestOptionsDialog = true }"),
            Case("dismiss keeps current values", "onDismissRequest = { showResetRequestOptionsDialog = false }"),
            Case("dialog has dedicated title", "settings_openai_request_options_reset_confirm_title"),
            Case("dialog explains reset scope", "settings_openai_request_options_reset_confirm_message"),
            Case("confirm performs reset", "onRequestOptionsChange(OpenAiRequestOptions())"),
            Case("cancel closes confirmation", "TextButton(onClick = { showResetRequestOptionsDialog = false })"),
        ).forEach { case ->
            assertTrue(case.name, promptSection.contains(case.marker))
        }

        assertFalse(
            "reset button must not mutate values before confirmation",
            promptSection.contains(
                "TextButton(onClick = { onRequestOptionsChange(OpenAiRequestOptions()) })"
            ),
        )

        data class ResourceCase(val locale: String, val path: String)
        listOf(
            ResourceCase("English", "src/main/res/values/strings.xml"),
            ResourceCase("Simplified Chinese", "src/main/res/values-zh-rCN/strings.xml"),
        ).forEach { case ->
            val xml = sourceFile(case.path).readText()
            assertTrue(
                "${case.locale}: reset confirmation title",
                xml.contains("settings_openai_request_options_reset_confirm_title"),
            )
            assertTrue(
                "${case.locale}: reset confirmation message",
                xml.contains("settings_openai_request_options_reset_confirm_message"),
            )
        }
    }

    @Test
    fun translationControls_followRequestedOrderAfterTargetLanguage() {
        data class Case(val name: String, val before: String, val after: String)

        listOf(
            Case("target before translation mode", "R.string.settings_target_lang", "R.string.settings_translation_mode_fast"),
            Case("translation mode before streaming", "R.string.settings_translation_mode_fast", "R.string.settings_streaming"),
            Case("streaming before thinking", "R.string.settings_streaming", "R.string.settings_thinking_mode"),
            Case("thinking before failed retry", "R.string.settings_thinking_mode", "R.string.settings_retry_failed_translation_label"),
            Case("failed retry before terminology consistency", "R.string.settings_retry_failed_translation_label", "R.string.settings_glossary_enabled"),
            Case("consistency before sending app name", "R.string.settings_glossary_enabled", "R.string.settings_send_app_name"),
            Case("send app name before app detection", "R.string.settings_send_app_name", "R.string.settings_foreground_app_detection"),
            Case("app detection before usage access", "R.string.settings_foreground_app_detection", "R.string.settings_grant_usage_access"),
            Case("usage access before terminology cell", "R.string.settings_grant_usage_access", "R.string.settings_manage_glossary"),
        ).forEach { case ->
            val beforeIndex = source.indexOf(case.before)
            val afterIndex = source.indexOf(case.after)
            assertTrue("${case.name}: missing ${case.before}", beforeIndex >= 0)
            assertTrue("${case.name}: missing ${case.after}", afterIndex >= 0)
            assertTrue(case.name, beforeIndex < afterIndex)
        }
    }

    @Test
    fun mergeAllOption_tableDriven_isVisibleOnlyForFloatingWindow() {
        data class Case(
            val name: String,
            val renderMode: RenderMode,
            val expectedOptions: List<MergeStrength>,
            val expectedDisplayedWhenStoredAll: MergeStrength,
        )
        listOf(
            Case(
                "Blocks keeps three geometric strengths",
                RenderMode.BLOCKS,
                listOf(MergeStrength.CONSERVATIVE, MergeStrength.STANDARD, MergeStrength.AGGRESSIVE),
                MergeStrength.STANDARD,
            ),
            Case(
                "floating window exposes all",
                RenderMode.FLOATING_WINDOW,
                listOf(
                    MergeStrength.CONSERVATIVE,
                    MergeStrength.STANDARD,
                    MergeStrength.AGGRESSIVE,
                    MergeStrength.ALL,
                ),
                MergeStrength.ALL,
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expectedOptions, mergeStrengthOptionsFor(case.renderMode))
            assertEquals(
                case.name,
                case.expectedDisplayedWhenStoredAll,
                displayedMergeStrength(case.renderMode, MergeStrength.ALL),
            )
        }
    }

    @Test
    fun reasoningControls_keepCommonChoicesVisibleAndCompatibilityFieldsAdvanced_tableDriven() {
        val assistanceStart = source.indexOf("private fun TranslationAssistanceSettings(")
        val assistanceEnd = source.indexOf("private fun ", startIndex = assistanceStart + 1)
        val assistance = source.substring(assistanceStart, assistanceEnd)
        val promptStart = source.indexOf("private fun OpenAiPromptSettings(")
        val promptEnd = source.indexOf("private fun ", startIndex = promptStart + 1)
        val prompt = source.substring(promptStart, promptEnd)
        val advancedGate = prompt.indexOf("if (!advancedExpanded) return")

        data class SourceCase(val name: String, val section: String, val marker: String)
        listOf(
            SourceCase(
                "effort is shown only while thinking is enabled",
                assistance,
                "if (requestOptions.thinkingModeEnabled)",
            ),
            SourceCase("effort uses a stepped slider", assistance, "steps = reasoningEffortSliderSteps"),
            SourceCase(
                "effort shows the actual wire value",
                assistance,
                "R.string.settings_reasoning_effort_value_with_wire",
            ),
            SourceCase(
                "custom effort input is available",
                assistance,
                "requestOptions.reasoningEffort == RemoteReasoningEffort.CUSTOM",
            ),
            SourceCase(
                "custom effort guidance is an input placeholder",
                assistance,
                "placeholder = {\n                                Text(stringResource(R.string.settings_reasoning_effort_custom_hint))",
            ),
            SourceCase(
                "parameter format is in prompt advanced settings",
                prompt,
                "R.string.settings_thinking_parameter_format",
            ),
            SourceCase(
                "enabled custom JSON is available",
                prompt,
                "requestOptions.customThinkingEnabledJson",
            ),
            SourceCase(
                "disabled custom JSON is available",
                prompt,
                "requestOptions.customThinkingDisabledJson",
            ),
        ).forEach { case -> assertTrue(case.name, case.section.contains(case.marker)) }

        assertFalse(
            "custom effort guidance must not occupy a supporting-text row",
            assistance.contains(
                "supportingText = {\n                                Text(stringResource(R.string.settings_reasoning_effort_custom_hint))"
            ),
        )

        listOf(
            "R.string.settings_thinking_parameter_format",
            "requestOptions.customThinkingEnabledJson",
            "requestOptions.customThinkingDisabledJson",
        ).forEach { marker ->
            assertTrue("$marker must follow the advanced gate", prompt.indexOf(marker) > advancedGate)
        }

        data class ResourceCase(val locale: String, val path: String)
        listOf(
            ResourceCase("English", "src/main/res/values/strings.xml"),
            ResourceCase("Simplified Chinese", "src/main/res/values-zh-rCN/strings.xml"),
        ).forEach { case ->
            val xml = sourceFile(case.path).readText()
            listOf(
                "settings_reasoning_effort",
                "settings_thinking_parameter_format",
                "settings_thinking_custom_enabled_json",
                "settings_thinking_custom_disabled_json",
            ).forEach { resource ->
                assertTrue("${case.locale}: $resource", xml.contains(resource))
            }
        }
    }

    @Test
    fun reasoningEffortSlider_roundTripsEveryStepAndClampsOutOfRange_tableDriven() {
        com.gameocr.app.data.RemoteReasoningEffort.entries.forEachIndexed { index, effort ->
            assertEquals("step for $effort", index.toFloat(), reasoningEffortStep(effort))
            assertEquals("round trip for $effort", effort, reasoningEffortAtStep(index.toFloat()))
        }

        data class ClampCase(
            val name: String,
            val step: Float,
            val expected: com.gameocr.app.data.RemoteReasoningEffort,
        )
        listOf(
            ClampCase("below minimum", -10f, com.gameocr.app.data.RemoteReasoningEffort.AUTO),
            ClampCase(
                "above maximum",
                100f,
                com.gameocr.app.data.RemoteReasoningEffort.CUSTOM,
            ),
            ClampCase("rounds down", 1.49f, com.gameocr.app.data.RemoteReasoningEffort.LOW),
            ClampCase("rounds up", 1.51f, com.gameocr.app.data.RemoteReasoningEffort.MEDIUM),
        ).forEach { case ->
            assertEquals(case.name, case.expected, reasoningEffortAtStep(case.step))
        }

        assertEquals(
            "one tick per enum value",
            com.gameocr.app.data.RemoteReasoningEffort.entries.size - 2,
            reasoningEffortSliderSteps,
        )
    }

    @Test
    fun imageDetailSlider_roundTripsEveryStepAndKeepsCustomProviderValue_tableDriven() {
        RemoteImageDetail.entries.forEachIndexed { index, detail ->
            assertEquals("step for $detail", index.toFloat(), imageDetailStep(detail))
            assertEquals("round trip for $detail", detail, imageDetailAtStep(index.toFloat()))
        }

        data class ClampCase(val name: String, val step: Float, val expected: RemoteImageDetail)
        listOf(
            ClampCase("below minimum", -10f, RemoteImageDetail.OMIT),
            ClampCase("above maximum", 100f, RemoteImageDetail.CUSTOM),
            ClampCase("rounds down", 1.49f, RemoteImageDetail.LOW),
            ClampCase("rounds up", 1.51f, RemoteImageDetail.AUTO),
        ).forEach { case ->
            assertEquals(case.name, case.expected, imageDetailAtStep(case.step))
        }
        assertEquals(RemoteImageDetail.entries.size - 2, imageDetailSliderSteps)

        val assistanceStart = source.indexOf("private fun TranslationAssistanceSettings(")
        val assistanceEnd = source.indexOf("private fun ", startIndex = assistanceStart + 1)
        val assistance = source.substring(assistanceStart, assistanceEnd)
        listOf(
            "requestOptions.sendScreenImage && translatorEngine == TranslatorEngine.OPENAI",
            "steps = imageDetailSliderSteps",
            "requestOptions.imageDetail == RemoteImageDetail.CUSTOM",
            "requestOptions.customImageDetail",
            "R.string.settings_image_detail_custom_hint",
        ).forEach { marker -> assertTrue(marker, assistance.contains(marker)) }

        data class ResourceCase(val locale: String, val path: String)
        listOf(
            ResourceCase("English", "src/main/res/values/strings.xml"),
            ResourceCase("Simplified Chinese", "src/main/res/values-zh-rCN/strings.xml"),
        ).forEach { case ->
            val xml = sourceFile(case.path).readText()
            listOf(
                "settings_image_detail_value",
                "settings_image_detail_omit",
                "settings_image_detail_low",
                "settings_image_detail_auto",
                "settings_image_detail_high",
                "settings_image_detail_custom",
            ).forEach { resource ->
                assertTrue("${case.locale}: $resource", xml.contains(resource))
            }
        }
    }

    @Test
    fun promptEditor_isInsideCollapsedAdvancedSectionAndDictionaryProtocolIsNotEditable() {
        val advancedGate = source.indexOf("if (!advancedExpanded) return")
        val marker = "R.string.settings_prompt_label"
        assertTrue("missing $marker", source.contains(marker))
        assertTrue("$marker must follow the advanced gate", advancedGate in 0 until source.indexOf(marker))
        assertFalse(source.contains("R.string.settings_dictionary_prompt_title"))
        assertFalse(source.contains("onDictionaryPromptChange"))
    }

    @Test
    fun promptSettings_useStandardTopPSwitchAndAccurateCompatibilityCopy() {
        val promptStart = source.indexOf("private fun OpenAiPromptSettings(")
        val promptEnd = source.indexOf("private fun ", startIndex = promptStart + 1)
        assertTrue("prompt settings start", promptStart >= 0)
        assertTrue("prompt settings end", promptEnd > promptStart)
        val promptSection = source.substring(promptStart, promptEnd)

        val switchRowStart = source.indexOf("internal fun SwitchRow(")
        val switchRowEnd = source.indexOf("private fun ", startIndex = switchRowStart + 1)
        assertTrue("shared switch row start", switchRowStart >= 0)
        assertTrue("shared switch row end", switchRowEnd > switchRowStart)
        val switchRowSection = source.substring(switchRowStart, switchRowEnd)

        data class OrderCase(
            val name: String,
            val section: String,
            val earlier: String,
            val later: String,
        )
        listOf(
            OrderCase(
                "top_p uses the shared settings switch",
                promptSection,
                "SwitchRow(",
                "R.string.settings_openai_top_p",
            ),
            OrderCase(
                "shared setting switch appears before its description",
                switchRowSection,
                "Switch(",
                "Text(",
            ),
        ).forEach { case ->
            val earlierIndex = case.section.indexOf(case.earlier)
            val laterIndex = case.section.indexOf(case.later)
            assertTrue("${case.name}: missing ${case.earlier}", earlierIndex >= 0)
            assertTrue("${case.name}: missing ${case.later}", laterIndex >= 0)
            assertTrue(case.name, earlierIndex < laterIndex)
        }

        assertFalse(
            "redundant request-options description must not be rendered",
            promptSection.contains("settings_openai_request_options_desc"),
        )

        data class ResourceCase(val locale: String, val path: String)
        listOf(
            ResourceCase("English", "src/main/res/values/strings.xml"),
            ResourceCase("Simplified Chinese", "src/main/res/values-zh-rCN/strings.xml"),
        ).forEach { case ->
            val xml = sourceFile(case.path).readText()
            assertTrue("${case.locale}: OpenAI compatibility", xml.contains("OpenAI / Anthropic"))
            assertFalse(
                "${case.locale}: obsolete request-options description",
                xml.contains("settings_openai_request_options_desc"),
            )
        }
    }

    @Test
    fun remoteLlmTextEncoding_isMutuallyExclusiveAndClearlyScoped_tableDriven() {
        val promptStart = source.indexOf("private fun OpenAiPromptSettings(")
        val promptEnd = source.indexOf("private fun ", startIndex = promptStart + 1)
        assertTrue("prompt settings start", promptStart >= 0)
        assertTrue("prompt settings end", promptEnd > promptStart)
        val promptSection = source.substring(promptStart, promptEnd)

        data class Case(val name: String, val marker: String)
        listOf(
            Case("Base64 switch", "R.string.settings_llm_encode_text_base64"),
            Case("Unicode switch", "R.string.settings_llm_encode_text_unicode"),
            Case(
                "enabling Base64 clears Unicode",
                "encodeUserTextUnicode = if (enabled) false else requestOptions.encodeUserTextUnicode",
            ),
            Case(
                "enabling Unicode clears Base64",
                "encodeUserTextBase64 = if (enabled) false else requestOptions.encodeUserTextBase64",
            ),
        ).forEach { case -> assertTrue(case.name, promptSection.contains(case.marker)) }

        data class ResourceCase(val locale: String, val path: String, val scopeText: String)
        listOf(
            ResourceCase(
                "English",
                "src/main/res/values/strings.xml",
                "OCR source text and on-screen text stay unchanged",
            ),
            ResourceCase(
                "Simplified Chinese",
                "src/main/res/values-zh-rCN/strings.xml",
                "OCR 原文和界面显示保持不变",
            ),
        ).forEach { case ->
            val xml = sourceFile(case.path).readText()
            assertTrue("${case.locale}: generic title", xml.contains("LLM request parameters") || xml.contains("LLM 请求参数"))
            assertTrue("${case.locale}: encoding scope", xml.contains(case.scopeText))
        }
    }

    @Test
    fun usageAndTerminologyEntries_useLinkCellsWithPermissionStatus() {
        data class Case(val name: String, val marker: String)

        listOf(
            Case("cell component", "private fun SettingsLinkCell"),
            Case("standard list cell", "ListItem("),
            Case("section-matching transparent background", "ListItemDefaults.colors(containerColor = Color.Transparent)"),
            Case("granted status", "R.string.settings_permission_granted"),
            Case("not granted status", "R.string.settings_permission_not_granted"),
            Case("resume refresh", "Lifecycle.Event.ON_RESUME"),
        ).forEach { case -> assertTrue(case.name, source.contains(case.marker)) }
    }

    @Test
    fun usageAccessIntent_targetsTheCurrentPackageAndKeepsGenericFallback() {
        data class UriCase(val packageName: String, val expected: String)

        listOf(
            UriCase("com.gameocr.app", "package:com.gameocr.app"),
            UriCase("com.gameocr.app.debug", "package:com.gameocr.app.debug"),
            UriCase("example.variant", "package:example.variant"),
        ).forEach { case ->
            assertEquals(case.packageName, case.expected, usageAccessPackageUri(case.packageName))
        }

        data class SourceCase(val name: String, val marker: String)

        listOf(
            SourceCase("current package URI", "usageAccessPackageUri(context.packageName)"),
            SourceCase("package-specific intent", "context.startActivity(packageIntent)"),
            SourceCase(
                "generic OEM fallback",
                "Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS)",
            ),
            SourceCase("resume refresh", "Lifecycle.Event.ON_RESUME"),
        ).forEach { case ->
            assertTrue("${case.name}: missing ${case.marker}", source.contains(case.marker))
        }
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
