package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitModelDownloadWiringTest {
    @Test
    fun pipeline_tableDriven_settingsQuickDownloadAndWelcomeShareSessionAndFeedback() {
        val screen = source("ui/SettingsScreen.kt")
        val onboarding = source("onboarding/OnboardingScreen.kt")
        listOf(
            "settings session" to screen.contains("rememberMlKitModelDownloadSession"),
            "welcome session" to onboarding.contains("rememberMlKitModelDownloadSession"),
            "settings live timeout" to screen.contains("val timeoutSeconds = apiTimeoutSec.toInt()"),
            "settings forwards timeout" to screen.contains("downloadMlKitLanguagePair(pair.first, pair.second, timeoutSeconds)"),
            "welcome saved timeout" to source("onboarding/OnboardingViewModel.kt").contains("sourceLang, targetLang, settingsRepository.get().apiTimeoutSeconds"),
            "shared routing" to source("translate/RoutingTranslator.kt").contains("googleMlKit.ensureLanguagePairModelsDownloaded(sourceLang, targetLang, timeoutSeconds)"),
            "settings and quick feedback" to (Regex("MlKitModelDownloadFeedback\\(").findAll(screen).count() == 2),
            "settings and quick actions" to (Regex("MlKitModelDownloadActions\\(").findAll(screen).count() == 2),
            "welcome feedback" to onboarding.contains("MlKitModelDownloadFeedback(state.download)"),
            "welcome actions" to onboarding.contains("MlKitModelDownloadActions(true"),
            "no independent settings job" to !screen.contains("mlKitModelDownloadJob"),
            "no independent welcome job" to !onboarding.contains("mlKitDownloadJob"),
            "quick dialog stays open" to screen.contains("startMlKitModelDownload(prompt.pair)\n                        mlKitMissingModelsPrompt = prompt"),
            "shared timeout classification" to source("translate/MlKitModelDownloadSession.kt").contains("catch (error: TimeoutCancellationException)"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

    @Test
    fun actions_tableDriven_cancelBesideDownloadAndBrowserOnlyAfterTimeout() {
        val ui = source("ui/MlKitModelDownloadUi.kt")
        listOf(
            "horizontal actions" to ui.contains("Row(verticalAlignment = Alignment.CenterVertically"),
            "reserve cancel width" to ui.contains("Modifier.weight(1f, fill = false)"),
            "no duplicate download" to ui.contains("enabled = !running"),
            "cancel only while running" to ui.contains("if (running) {\n                ModelDownloadCancelButton("),
            "cancel scoped to request" to ui.contains("requestKey = requestId.toString()"),
            "shared timeout copy" to ui.contains("MlKitDownloadPhase.TIMED_OUT -> stringResource(R.string.settings_mlkit_download_timeout_help)"),
            "browser shares the URL button" to ui.contains("ExternalBrowserLinkButton("),
            "only verified model link" to ui.contains("state.browserUrl?.let"),
            "cleared on leaving page" to ui.contains("onDispose { session.reset() }"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

    @Test
    fun readyAndDelete_tableDriven_useExistingActionsAndSourceOnlyConfirmation() {
        val ui = source("ui/MlKitModelDownloadUi.kt")
        val screen = source("ui/SettingsScreen.kt")
        val translator = source("translate/MlKitOnDeviceTranslator.kt")
        listOf(
            "ready text in original button" to screen.contains("downloadLabel = if (currentPairReady) stringResource(R.string.settings_mlkit_model_ready)"),
            "no separate ready text" to !screen.contains("currentPairReady -> Text("),
            "ready and deletion disable download" to screen.contains("downloadEnabled = !currentPairReady && !mlKitModelDeleteRunning"),
            "shared button honors enabled" to ui.contains("enabled = !running && downloadEnabled"),
            "actions wrap to available space" to ui.contains("FlowRow("),
            "delete placed in wrapping actions" to ui.contains("trailingAction()"),
            "existing dialog style" to ui.contains("CatalystAlertDialog("),
            "no default dialog style" to !ui.contains("material3.AlertDialog"),
            "existing deletion message" to ui.contains("settings_model_delete_confirm_message"),
            "confirmation identifies source" to ui.contains("Text(sourceLanguageName)"),
            "selection change dismisses old confirmation" to ui.contains("remember(pair) { mutableStateOf(false) }"),
            "confirmation consumed before callback" to ui.contains("showConfirm = false\n                            onDelete(pair)"),
            "only installed source offered" to screen.contains("MlKitSourceModelPolicy.deletableSource("),
            "download and delete excluded" to screen.contains("enabled = !mlKitModelDownloadRunning && !mlKitModelDeleteRunning"),
            "late callback checks current pair" to screen.contains("confirmedPair != (sourceLang to targetLang)"),
            "download guard during deletion" to screen.contains("if (mlKitModelDeleteRunning) return"),
            "deletion refreshes actual inventory" to screen.contains("mlKitModelRefreshVersion++"),
            "only confirmed pair passed" to screen.contains("deleteMlKitSourceLanguageModel(confirmedPair.first, confirmedPair.second)"),
            "single source deletion" to translator.contains("modelDeleter.deleteDownloadedLanguage(source)"),
            "official model deletion API" to translator.contains("RemoteModelManager.getInstance().deleteDownloadedModel(model).await()"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

    @Test
    fun browserStyle_tableDriven_matchesExistingPaddleLinkWithoutChangingDestination() {
        val button = source("ui/ExternalBrowserLinkButton.kt")
        val feedback = source("ui/MlKitModelDownloadUi.kt")
        val screen = source("ui/SettingsScreen.kt")
        listOf(
            "outlined button" to button.contains("OutlinedButton("),
            "full width" to button.contains("modifier.fillMaxWidth()"),
            "external link icon" to button.contains("Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)"),
            "actual URL label" to button.contains("text = url"),
            "unchanged browser destination" to button.contains("onClick = { openExternalBrowser(context, url) }"),
            "existing icon gap" to button.contains("Modifier.padding(start = 8.dp)"),
            "URL remains one line" to button.contains("maxLines = 1"),
            "URL does not wrap" to button.contains("softWrap = false"),
            "long URL stays within button" to button.contains("overflow = TextOverflow.Ellipsis"),
            "existing accessibility copy" to button.contains("contentDescription = actionLabel"),
            "model URL forwarded without replacement" to feedback.contains("url = url"),
            "old visible label removed" to !feedback.contains("Text(stringResource(R.string.settings_mlkit_download_open_browser))"),
            "Paddle reference uses same button" to screen.contains("ExternalBrowserLinkButton(\n                        url = PADDLE_AI_STUDIO_PAGE_URL"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/gameocr/app/$path"),
        File("app/src/main/java/com/gameocr/app/$path"),
    ).first(File::isFile).readText().replace("\r\n", "\n")
}
