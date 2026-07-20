package com.gameocr.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMaxVoiceManagerUiAuditTest {

    @Test
    fun miniMaxVoiceField_exposesSearchCloneDesignPreviewUseAndDeleteActions() {
        val settingsSource = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val managerSource = sourceFile(
            "src/main/java/com/gameocr/app/ui/MiniMaxVoiceManagerDialog.kt"
        ).readText()
        val viewModelSource = sourceFile(
            "src/main/java/com/gameocr/app/ui/SettingsViewModel.kt"
        ).readText()
        val httpTtsSource = sourceFile(
            "src/main/java/com/gameocr/app/tts/HttpTtsEngine.kt"
        ).readText()

        data class Case(val name: String, val marker: String)
        listOf(
            Case("field wiring", "MiniMaxVoiceField("),
            Case("query icon", "Icons.Default.Search"),
            Case("query entry embedded in the field", "trailingIcon = {"),
            Case("full-screen page layer", "DialogProperties(\n            usePlatformDefaultWidth = false"),
            Case("navigation uses a content-level tab bar", "SecondaryTabRow("),
            Case(
                "top app bar divider",
                "HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)",
            ),
            Case("navigation uses Material tabs", "Tab("),
            Case("clone toggles reuse the settings switch row", "SwitchRow("),
            Case("forms expose a visible scroll indicator", "miniMaxVerticalScrollIndicator("),
            Case("focused fields request relocation", "requester.bringIntoView()"),
            Case("page top app bar", "TopAppBar("),
            Case("offline system search", "searchMiniMaxSystemVoices(query)"),
            Case("account voice heading stays pinned", "stickyHeader(key = \"account-title\")"),
            Case("system voice heading stays pinned", "stickyHeader(key = \"system-title\")"),
            Case("pinned heading has an opaque surface", "MiniMaxVoiceSectionHeader("),
            Case("account voice refresh", "onLoadManagedVoices(baseUrl, apiKey)"),
            Case("account voices load when the page opens", "LaunchedEffect(baseUrl, apiKey)"),
            Case("account voice auto-load checks the API key", "shouldLoadMiniMaxManagedVoices(apiKey)"),
            Case("pending created voices outlive the page layer", "var pendingCreatedVoices by remember"),
            Case("pending and server voices are merged", "mergeMiniMaxManagedVoices("),
            Case("created voices update the session cache", "onPendingCreatedVoicesChange(localPendingVoices)"),
            Case("operation feedback uses a snackbar host", "SnackbarHost(snackbarHostState)"),
            Case("operation feedback is shown as a snackbar", "snackbarHostState.showSnackbar(text)"),
            Case("clone page", "MiniMaxVoiceManagerPage.CLONE"),
            Case("design page", "MiniMaxVoiceManagerPage.DESIGN"),
            Case("AI description action", "Icons.Default.AutoAwesome"),
            Case("AI explanation uses a help icon", "SettingHelpTooltip("),
            Case("AI result replaces the description", "prompt = onGenerateDescription(prompt)"),
            Case("creation waits for AI generation", "!generatingDescription"),
            Case("clone source picker", "ActivityResultContracts.OpenDocument()"),
            Case("voice preview action", "Icons.Default.PlayArrow"),
            Case("voice cell starts use action", "Modifier.clickable(enabled = actionsEnabled)"),
            Case("voice use starts confirmation", "pendingUse = MiniMaxPendingVoiceUse("),
            Case("voice use confirmation names the voice", "voice.voiceName"),
            Case("voice use confirmation applies the voice", "onSelectVoice(voice.voiceId)"),
            Case("preview uses language-specific text", "miniMaxVoicePreviewText("),
            Case("preview updates the TTS test text", "onTestTextChange(previewText)"),
            Case("preview invokes TTS synthesis", "onPreviewVoice(voiceId, previewText)"),
            Case("preview cost warning", "settings_tts_minimax_voice_preview_cost_hint"),
            Case("delete confirmation", "settings_tts_minimax_voice_delete_message"),
            Case("created voice selection", "onSelectVoice(result.voice.voiceId)"),
            Case("design response consumes trial audio", "result.previewAudio?.takeIf"),
            Case("clone creation starts confirmation", "MiniMaxPendingCreation.Clone(request)"),
            Case("design creation starts confirmation", "MiniMaxPendingCreation.Design(request)"),
            Case("confirmed creation executes", "createVoice(creation)"),
            Case("returned trial audio is retained", "designTrialAudio = trialAudio"),
            Case("trial audio can be replayed manually", "designTrialAudio?.let(::playDesignTrialAudio)"),
            Case("trial replay control is visible", "settings_tts_minimax_design_trial_play"),
            Case("missing trial audio is reported", "settings_tts_minimax_voice_created_no_trial"),
        ).forEach { case ->
            assertTrue(case.name, managerSource.contains(case.marker))
        }
        assertTrue(
            "Voice search must not use a separate outlined icon button",
            !managerSource.contains("OutlinedIconButton("),
        )
        assertTrue(
            "System and account voice cells must both open the use confirmation on tap",
            Regex("Modifier\\.clickable\\(enabled = actionsEnabled\\)").findAll(managerSource).count() == 2,
        )
        assertTrue(
            "Operation messages must not remain as persistent page-bottom text",
            !managerSource.contains("message?.let"),
        )
        assertTrue(
            "Returned trial audio must not auto-play after creation",
            !managerSource.contains("playDesignTrialAudio(trialAudio)"),
        )
        val autoLoadBlock = managerSource
            .substringAfter("LaunchedEffect(baseUrl, apiKey)")
            .substringBefore("Dialog(")
        assertTrue(
            "Account auto-load must not clear session-pending voices before merging",
            !autoLoadBlock.contains("managedVoices = emptyList()"),
        )
        listOf(
            "copy icon" to "Icons.Default.ContentCopy",
            "copy callback" to "copyMiniMaxVoice(",
            "ambiguous use icon" to "Icons.Default.Check",
            "voice use input icon" to "Icons.AutoMirrored.Filled.Input",
        ).forEach { (name, marker) ->
            assertTrue("Voice manager must not retain $name", !managerSource.contains(marker))
        }
        assertTrue(
            "The page must retain the Dialog default keyboard pan behavior",
            !managerSource.contains("SOFT_INPUT_ADJUST_RESIZE") && !managerSource.contains(".imePadding()"),
        )
        assertTrue(
            "Legacy filter chips must not be used for page navigation",
            !managerSource.contains("FilterChip("),
        )
        assertTrue(
            "The tab bar must use the wider secondary indicator",
            !managerSource.contains("PrimaryTabRow("),
        )
        val pageLayout = managerSource
            .substringAfter("TopAppBar(")
            .substringBefore("pendingDelete?.let")
        assertTrue(
            "The tab bar must sit outside the 20dp content padding",
            pageLayout.indexOf("SecondaryTabRow(") <
                pageLayout.indexOf(".padding(horizontal = 20.dp, vertical = 8.dp)"),
        )
        assertTrue(
            "A neutral divider must separate the top app bar from the tab bar",
            pageLayout.indexOf("HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)") <
                pageLayout.indexOf("SecondaryTabRow("),
        )
        assertTrue(
            "Both clone options must use the shared settings switch row",
            Regex("SwitchRow\\(").findAll(managerSource).count() == 2,
        )
        val designBlock = managerSource
            .substringAfter("private fun MiniMaxVoiceDesignPage(")
            .substringBefore("private fun AudioPickerRow(")
        assertTrue(
            "AI explanation must be inside the help tooltip",
            designBlock.contains(
                "SettingHelpTooltip(\n                text = stringResource(" +
                    "R.string.settings_tts_minimax_design_ai_hint)"
            ),
        )
        assertTrue(
            "AI explanation must not remain as persistent bottom text",
            !designBlock.contains(
                "Text(\n            text = stringResource(" +
                    "R.string.settings_tts_minimax_design_ai_hint)"
            ),
        )
        assertTrue(
            "Clone and design forms must both show an internal scroll indicator",
            Regex("\\.miniMaxVerticalScrollIndicator\\(scrollState").findAll(managerSource).count() == 2,
        )
        assertTrue(
            "All six clone/design text fields must stay visible above the keyboard",
            Regex("\\.then\\(miniMaxBringIntoViewOnFocus\\(\\)\\)").findAll(managerSource).count() == 6,
        )
        assertTrue("SettingsScreen must use the manager field", settingsSource.contains("MiniMaxVoiceField("))
        assertTrue(
            "Create, use, and delete actions must each have a confirmation dialog",
            Regex("AlertDialog\\(").findAll(managerSource).count() == 3,
        )
        assertTrue(
            "SettingsScreen must pass account voice loading",
            settingsSource.contains("onLoadMiniMaxManagedVoices = viewModel::loadMiniMaxManagedVoices"),
        )
        assertTrue(
            "SettingsScreen must wire the MiniMax description generator",
            settingsSource.contains("onGenerateMiniMaxVoiceDescription = { draft ->"),
        )
        data class PlaybackCase(val name: String, val source: String, val marker: String)
        listOf(
            PlaybackCase(
                "Settings wires trial playback",
                settingsSource,
                "onPlayMiniMaxTrialAudio = { audioHex ->",
            ),
            PlaybackCase(
                "ViewModel decodes returned hex",
                viewModelSource,
                "decodeMiniMaxTrialAudio(audioHex)",
            ),
            PlaybackCase(
                "ViewModel uses local HTTP audio playback",
                viewModelSource,
                "httpTtsEngine.playAudio(",
            ),
            PlaybackCase(
                "Player reuses the coordinated MediaPlayer path",
                httpTtsSource,
                "play(payload, generation, token, gainDb)",
            ),
        ).forEach { case ->
            assertTrue(case.name, case.source.contains(case.marker))
        }
    }

    @Test
    fun miniMaxVoiceManagerStrings_areLocalized() {
        val requiredKeys = listOf(
            "settings_tts_minimax_voice_search",
            "settings_tts_minimax_voice_tab_search",
            "settings_tts_minimax_voice_tab_clone",
            "settings_tts_minimax_voice_tab_design",
            "settings_tts_minimax_voice_search_placeholder",
            "settings_tts_minimax_voice_preview",
            "settings_tts_minimax_voice_use",
            "settings_tts_minimax_voice_use_title",
            "settings_tts_minimax_voice_use_message",
            "settings_tts_minimax_voice_created",
            "settings_tts_minimax_voice_created_no_trial",
            "settings_tts_minimax_create_confirm_title",
            "settings_tts_minimax_create_confirm_message",
            "settings_tts_minimax_voice_preview_cost_hint",
            "settings_tts_minimax_clone_source_hint",
            "settings_tts_minimax_clone_prompt_hint",
            "settings_tts_minimax_design_preview_hint",
            "settings_tts_minimax_design_ai_generate",
            "settings_tts_minimax_design_ai_generating",
            "settings_tts_minimax_design_ai_hint",
            "settings_tts_minimax_design_ai_error",
            "settings_tts_minimax_design_trial_play",
            "settings_tts_minimax_design_trial_hint",
            "settings_tts_minimax_design_trial_error",
            "settings_tts_minimax_voice_delete_message",
        )
        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
        ).forEach { path ->
            val strings = stringResources(sourceFile(path))
            requiredKeys.forEach { key ->
                assertTrue("$path missing $key", strings[key].orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun voiceCreatedSnackbarText_isBriefAndDoesNotClaimActivationIsRequired() {
        data class Case(val resourcePath: String, val expected: String)

        listOf(
            Case(
                "src/main/res/values/strings.xml",
                "Voice created and set as the current voice. " +
                    "It will sync to account voices after the first successful speech synthesis.",
            ),
            Case(
                "src/main/res/values-zh-rCN/strings.xml",
                "音色创建成功，已设为当前音色；首次成功朗读后会同步到账号音色。",
            ),
        ).forEach { case ->
            val actual = stringResources(sourceFile(case.resourcePath))
                .getValue("settings_tts_minimax_voice_created")
            assertEquals(case.resourcePath, case.expected, actual)
            listOf("%1\$s", "activate", "激活", "朗读测试").forEach { obsoleteText ->
                assertTrue(
                    "${case.resourcePath} must not contain $obsoleteText",
                    !actual.contains(obsoleteText, ignoreCase = true),
                )
            }
        }
    }

    private fun stringResources(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            repeat(nodes.length) { index ->
                val node = nodes.item(index)
                put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
            }
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path")
}
