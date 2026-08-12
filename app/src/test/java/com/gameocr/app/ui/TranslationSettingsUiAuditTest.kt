package com.gameocr.app.ui

import com.gameocr.app.data.RenderMode
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
    fun promptEditors_areInsideCollapsedAdvancedSection() {
        val advancedGate = source.indexOf("if (!advancedExpanded) return")
        listOf(
            "R.string.settings_prompt_label",
            "R.string.settings_dictionary_prompt_title",
            "R.string.settings_dictionary_prompt_desc",
        ).forEach { marker ->
            assertTrue("missing $marker", source.contains(marker))
            assertTrue("$marker must follow the advanced gate", advancedGate in 0 until source.indexOf(marker))
        }
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
