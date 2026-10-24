package com.gameocr.app.ui

import java.io.File
import com.gameocr.app.data.AutoOcrRoutingPolicy
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OcrEngineCatalog
import com.gameocr.app.data.PaddleModelVersion
import org.junit.Assert.*
import org.junit.Test

class AutoOcrUiWiringTest {
    @Test fun ocrMoreIsRemovedWithoutChangingTranslationMoreOrAutomaticDownloads_tableDriven() {
        val screen = source("ui/SettingsScreen.kt")
        val ocr = screen.substringAfter("// Manual chips and automatic routing share the same names, groups and order.")
            .substringBefore("if (ocrEngine == OcrEngineKind.BAIDU)")
        val automatic = screen.substringAfter("if (showAutoOcrSettings) {").substringBefore("\n        return\n    }")
        listOf(
            "removed OCR more state and sheet" to !screen.contains("showMoreOcr"),
            "removed obsolete picker policy" to !source("ui/AutoOcrOptionPolicy.kt").contains("mlKitOcrPickerState"),
            "no more chip in OCR row" to !ocr.contains("MoreOptionsChip("),
            "existing OCR chips still use shared catalog" to ocr.contains("OcrEngineCatalog.quickOptions(group, availableOcrModules.orEmpty()).forEach"),
            "automatic gear stays available" to (ocr.contains("onSettingsClick = if (option.engine == OcrEngineKind.ML_KIT_AUTO)") &&
                ocr.contains("{ showAutoOcrSettings = true }")),
            "module readiness still refreshes after download and resume" to
                screen.contains("LaunchedEffect(mlKitOcrDownloadState, ocrModuleRefresh)"),
            "automatic settings still request dynamic download with configured timeout" to
                automatic.contains("mlKitOcrDownload.start(apiTimeoutSec.toInt())"),
            "automatic settings still expose cancellation" to
                automatic.contains("mlKitOcrDownload.cancel(mlKitOcrDownloadState.requestId)"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
        val shared = screen.substringAfter("internal fun MoreOptionsChip(")
            .substringBefore("internal fun <T> EngineChip(")
        listOf("FilterChip(", "selected = false", "enabled = enabled", "onClick = onClick",
            "label = { Text(label) }").forEach {
            assertTrue(it, shared.contains(it))
        }
        val translationEntry = screen.substringBefore("showMlKitMoreLanguages = true").substringAfterLast("MoreOptionsChip(")
        assertTrue(translationEntry.contains("onClick = {"))
        assertFalse(translationEntry.contains("OutlinedButton("))
        for (downloaded in listOf(emptySet(), OcrEngineCatalog.downloadable)) {
            val entries = OcrEngineCatalog.quickOptions(com.gameocr.app.data.OcrEngineGroup.ON_DEVICE, downloaded)
                .map { it.engine }
            assertEquals(OcrEngineKind.ML_KIT_AUTO, entries.first())
            assertEquals(OcrEngineKind.ML_KIT_DEVANAGARI in downloaded, OcrEngineKind.ML_KIT_DEVANAGARI in entries)
            listOf(OcrEngineKind.ML_KIT_JAPANESE, OcrEngineKind.ML_KIT_KOREAN, OcrEngineKind.ML_KIT_CHINESE,
                OcrEngineKind.ML_KIT_LATIN, OcrEngineKind.PADDLE_ONNX, OcrEngineKind.MANGA_OCR_JA).forEach {
                assertTrue("$it remains visible", it in entries)
            }
        }
        assertTrue("translation language shortcut keeps its explicit label",
            screen.contains("R.string.settings_on_device_more_languages"))
    }

    @Test fun devanagariRemainsAnOptionalGooglePlayModuleRatherThanABundledModel_tableDriven() {
        val build = projectSource("app/build.gradle.kts")
        val versions = projectSource("gradle/libs.versions.toml")
        val manifest = projectSource("app/src/main/AndroidManifest.xml")
        val manager = source("ocr/MlKitOcrModuleManager.kt")
        val recognizer = source("ocr/MlKitOcrEngine.kt")
        listOf(
            "unbundled dependency" to build.contains("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:"),
            "no direct bundled dependency" to !build.contains("com.google.mlkit:text-recognition-devanagari:"),
            "no bundled catalog dependency" to !versions.contains("name = \"text-recognition-devanagari\""),
            "no install-time download request" to !manifest.contains("ocr_devanagari"),
            "real availability check" to manager.contains("client.areModulesAvailable(recognizer)"),
            "explicit module download" to manager.contains("client.installModules(ModuleInstallRequest.newBuilder()"),
            "recognition checks downloaded availability first" to recognizer.contains("check(optionalModule.available())"),
            "recognition uses the downloaded recognizer" to recognizer.contains("runRecognizer(optionalModule.recognizer, bitmap"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

    @Test fun dropdownEngineOrderMatchesTheOuterSettingsChips() {
        val screen = source("ui/SettingsScreen.kt")
        val section = screen.substringAfter("OcrEngineGroup.entries.forEach { group ->")
            .substringBefore("if (ocrEngine == OcrEngineKind.BAIDU)")
        assertTrue(section.contains("OcrEngineCatalog.quickOptions(group, availableOcrModules.orEmpty()).forEach"))
        assertTrue(section.contains("ocrEngine, option.engine, stringResource(option.labelRes)"))
        val outerOrder = OcrEngineCatalog.options.map { it.engine }.filterNot { it == OcrEngineKind.ML_KIT_AUTO }
        assertEquals(outerOrder, AutoOcrRoutingPolicy.candidates.map { it.engine }.distinct())
        assertTrue(source("data/AutoOcrSettings.kt").contains("OcrEngineCatalog.automaticRoutes()"))
        assertTrue(source("ui/AutoOcrSettingsScreen.kt").contains("val options = AutoOcrRoutingPolicy.candidates"))
        assertFalse(source("ui/AutoOcrSettingsScreen.kt").contains("AutoOcrRoutingPolicy.options("))
        assertEquals(PaddleModelVersion.entries.toList(), AutoOcrRoutingPolicy.candidates
            .filter { it.engine == OcrEngineKind.PADDLE_ONNX }.map { it.paddleVersion })
    }

    @Test fun addLanguageUsesSharedPickerAndLanguageCards_tableDriven() {
        val dialog = source("ui/AutoOcrSettingsScreen.kt")
        val tiles = source("ui/AutoOcrLanguageTile.kt")
        val picker = dialog.substringAfter("if (showLanguagePicker) {")
        val dropdown = source("ui/SettingsScreen.kt")
            .substringAfter("internal fun <T> SettingsOptionDropdown(")
            .substringBefore("private fun TranslationContextModeSelector(")
        listOf(
            "two column language grid" to dialog.contains("columns = GridCells.Fixed(2)"),
            "description spans both columns" to dialog.contains("span = { GridItemSpan(maxLineSpan) }"),
            "add is the last grid item" to dialog.contains("item(key = \"add-language\")"),
            "no floating add button obscures tiles" to !dialog.contains("FloatingActionButton("),
            "add tile has a visible accessible label" to tiles.contains("Text(stringResource(R.string.settings_auto_ocr_add_language)"),
            "add opens picker" to dialog.contains("onClick = { showLanguagePicker = true }"),
            "shared searchable picker" to picker.contains("LanguagePickerSheet("),
            "only addable languages" to picker.contains("allowedLanguageCodes = addableCodes"),
            "auto is not an extra language" to picker.contains("allowAuto = false"),
            "catalog order without pinned languages" to picker.contains("pinned = emptyList()"),
            "selection saves against latest state" to picker.contains("onChange { latest -> AutoOcrLanguageListPolicy.add(latest, code) }"),
            "dismiss picker keeps settings open" to picker.contains("onDismiss = { showLanguagePicker = false }"),
            "scroll to added language after description" to dialog.contains("gridState.animateScrollToItem(index + 1)"),
            "extra language removal requests confirmation" to dialog.contains("pendingDeleteLanguage = language.code"),
            "default tiles cannot be removed" to dialog.contains("removable = !AutoOcrLanguageListPolicy.isDefault(language.code)"),
            "all tiles use the same theme surface" to tiles.contains("color = colors.surface,"),
            "no default or custom status decoration" to (!tiles.contains("customized") &&
                !tiles.contains("settings_auto_ocr_default")),
            "language and engine remain visible" to tiles.contains("Text(autoOcrRouteLabel(route)"),
            "long labels and large fonts can grow tiles" to tiles.contains("heightIn(min = 150.dp)"),
            "glyph is decoration rather than a second language name" to tiles.contains(".clearAndSetSemantics {}"),
            "other dropdowns keep existing appearance" to dropdown.contains("compactRow: Boolean = false"),
            "original text field still available" to dropdown.contains("OutlinedTextField("),
            "list respects system insets" to dialog.contains("Modifier.fillMaxSize().padding(padding)"),
            "last row keeps bottom spacing" to dialog.contains("bottom = 24.dp"),
            "switch saves against latest state" to dialog.contains("latest.copy(routes = if (selected == null) latest.routes - key else latest.routes + (key to selected))"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
        assertFalse("no deferred save action", dialog.contains("onSave("))
        assertFalse("no separate modal draft", dialog.contains("var draft"))
        val compact = dropdown.substringAfter("if (compactRow) {").substringBefore("} else {")
        assertTrue(compact.contains("menuAnchor(MenuAnchorType.PrimaryNotEditable)"))
        assertFalse("compact row is not another input box", compact.contains("OutlinedTextField("))
    }

    @Test fun oneChipSeparateActionsAndFullSettingsPage_tableDriven() {
        val screen = source("ui/SettingsScreen.kt")
        val chip = screen.substringAfter("internal fun <T> EngineChip(").substringBefore("/** OCR 引擎")
        val dialog = source("ui/AutoOcrSettingsScreen.kt")
        listOf(
            "one shared chip" to chip.contains("FilterChip("),
            "body selects" to chip.contains("onClick = { onSelect(target) }"),
            "gear is inside chip" to chip.contains("trailingIcon = onSettingsClick"),
            "gear has separate action" to chip.contains("IconButton(onClick = open, enabled = enabled"),
            "only auto has gear" to screen.contains("onSettingsClick = if (option.engine == OcrEngineKind.ML_KIT_AUTO)"),
            "gear only opens settings" to screen.contains("{ showAutoOcrSettings = true }"),
            "full page follows existing settings" to dialog.contains("Scaffold("),
            "top app bar" to dialog.contains("TopAppBar("),
            "back returns without confirmation" to dialog.contains("IconButton(onClick = onBack)"),
            "sheets and removal confirmation handle their own back" to dialog.contains("BackHandler(enabled = !showLanguagePicker && selectedLanguage == null && pendingDeleteLanguage == null, onBack = onBack)"),
            "parent retains draft on return" to screen.contains("onBack = { showAutoOcrSettings = false }"),
            "grouped engine sheet" to dialog.contains("AutoOcrEngineSheet("),
            "complete shared catalog" to dialog.contains("AutoOcrRoutingPolicy.candidates"),
            "capability controls selection not visibility" to dialog.contains("autoOcrCanSelect(settings, code, route, state)"),
            "full settings save includes mapping" to screen.contains("autoOcr = autoOcr"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
        assertFalse(chip.contains("onClick = { onSelect(target);"))
        listOf("common_save", "onSave(").forEach {
            assertFalse(it, dialog.contains(it))
        }
    }

    @Test fun languageRemovalRequiresConfirmation_tableDriven() {
        val page = source("ui/AutoOcrSettingsScreen.kt")
        val tiles = source("ui/AutoOcrLanguageTile.kt")
        val removeAction = page.substringAfter("onRemove = {").substringBefore("},")
        val dialog = page.substringAfter("pendingDeleteLanguage?.let { code ->")
            .substringBefore("selectedLanguage?.let { code ->")
        val dismiss = dialog.substringAfter("dismissButton = {").substringBefore("confirmButton = {")
        val confirm = dialog.substringAfter("confirmButton = {")
        listOf(
            "click only selects the target" to removeAction.contains("pendingDeleteLanguage = language.code"),
            "removal button is limited to extra languages" to tiles.contains("if (removable) {"),
            "removal click is separate from opening engine selection" to tiles.contains("IconButton(onClick = onRemove"),
            "click never saves or removes" to (!removeAction.contains("onChange") && !removeAction.contains(".remove(")),
            "confirmation survives recreation" to page.contains("var pendingDeleteLanguage by rememberSaveable"),
            "same shared confirmation style" to dialog.contains("CatalystAlertDialog("),
            "asks to confirm deletion of the selected language" to dialog.contains("R.string.settings_auto_ocr_delete_language_confirm, stringResource(language.nameRes)"),
            "outside tap and system back only dismiss" to dialog.contains("onDismissRequest = { pendingDeleteLanguage = null }"),
            "cancel only dismisses" to (dismiss.contains("pendingDeleteLanguage = null") && !dismiss.contains("onChange")),
            "cancel uses existing label" to dismiss.contains("R.string.settings_model_delete_confirm_no"),
            "confirm uses existing label" to confirm.contains("R.string.model_download_cancel_confirm_ok"),
            "stale or repeated confirm is ignored" to confirm.contains("if (pendingDeleteLanguage == code)"),
            "clear request before saving" to (confirm.indexOf("pendingDeleteLanguage = null") < confirm.indexOf("onChange")),
            "save only the confirmed language against latest settings" to confirm.contains("onChange { latest -> AutoOcrLanguageListPolicy.remove(latest, code) }"),
            "default languages stay protected" to dialog.contains("if (AutoOcrLanguageListPolicy.isDefault(code)) return@let"),
            "removed target cannot leave a stale dialog" to page.contains("languages.none { it.code == code }"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
        assertEquals("only one removal path, inside confirmation", 1,
            Regex("AutoOcrLanguageListPolicy\\.remove\\(").findAll(page).count())
    }

    @Test fun readinessAndDownloads_tableDriven_useRealStateAndSharedPipeline() {
        val page = source("ui/AutoOcrSettingsScreen.kt")
        val sheet = source("ui/AutoOcrEngineSheet.kt")
        val settings = source("ui/SettingsScreen.kt")
        val entry = settings.substringAfter("if (showAutoOcrSettings) {").substringBefore("return")
        listOf(
            "same sheet as language selector" to sheet.contains("rememberModalBottomSheetState(skipPartiallyExpanded = true)"),
            "scrollable engines" to sheet.contains("LazyColumn(modifier = Modifier.selectableGroup()"),
            "category order" to sheet.contains("AutoOcrOptionGroup.entries.forEach"),
            "no suffix on model names" to !page.contains("onboarding_model_not_downloaded"),
            "available has no status text" to sheet.contains("AutoOcrOptionState.READY -> Unit"),
            "download is separate from selection" to sheet.contains("onClick = { route?.let(onDownload) }"),
            "unavailable is not selectable" to sheet.contains("val selectable = canSelect(route, optionState)"),
            "radio and row share enabled state" to (sheet.contains("RadioButton(selected = selected, enabled = selectable") &&
                sheet.contains(".selectable(selected = selected, enabled = selectable")),
            "labels reuse outer resource only" to sheet.contains("stringResource(autoOcrRouteLabelRes(route))"),
            "no added ML Kit prefix" to !sheet.contains("R.string.settings_translation_service_mlkit"),
            "approved not configured label" to sheet.contains("R.string.settings_auto_ocr_unconfigured"),
            "approved unsupported label" to sheet.contains("R.string.settings_auto_ocr_unsupported"),
            "unsupported uses status text not a download button" to sheet.contains("AutoOcrOptionState.UNSUPPORTED, AutoOcrOptionState.UNCONFIGURED -> Text("),
            "status uses sheet language" to page.contains("autoOcrOptionState(settings, code, route, available, activeModels, activeModules)"),
            "download callback rechecks eligibility" to page.contains("autoOcrOptionState(settings, code, route, available, activeModels, activeModules) == AutoOcrOptionState.DOWNLOAD"),
            "real readiness refreshed on completed work" to entry.contains("Triple(modelDownloadWorkInfos.map { it.id to it.state }, autoOcrModelRefresh, mlKitOcrDownloadState)"),
            "current model readiness loaded on reopen" to page.contains("availabilityRevision, selectedLanguage"),
            "common permission and metered network confirmation" to entry.contains("requestModelDownload(modelDownloadSpecDisplayName(context, spec))"),
            "same background queue and dependency expansion" to entry.contains("viewModel.downloadModels(listOf(spec), onProgress = {})"),
            "download does not change OCR selection" to !entry.contains("autoSaveAutoOcrSettings("),
            "refresh on success failure or cancellation" to entry.contains("autoOcrModelRefresh++"),
            "compact progress only on this page" to entry.contains("modelDownloadFeedback(true)"),
            "completion only on visible page after successful await" to entry.contains("viewModel.downloadModels(listOf(spec), onProgress = {})\n                                autoOcrDownloadComplete = showAutoOcrSettings"),
            "leaving page clears completion" to entry.contains("onDispose {\n                autoOcrDownloadComplete = false\n                mlKitOcrDownload.clearFinished()"),
            "clear completion before a new attempt" to entry.contains("autoOcrDownloadPending = true\n                        autoOcrDownloadComplete = false"),
            "hide completion while downloading" to entry.contains("autoOcrDownloadComplete && activeModelDownloads.isEmpty() && !autoOcrDownloadPending"),
            "other settings keep existing presentation" to settings.contains("modelDownloadFeedback(false)"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

    @Test fun allOcrBackendsReceiveTheEffectiveSettingsSnapshot_tableDriven() {
        val routing = source("ocr/RoutingOcrEngine.kt")
        listOf("baidu", "tencent", "youdao", "umi", "luna", "paddleAiStudio", "paddle", "manga", "mlKit").forEach {
            assertTrue(it, routing.contains("$it.recognize(bitmap, kind, settings)"))
        }
        assertTrue(routing.contains("if (kind == OcrEngineKind.ML_KIT_AUTO)"))
        assertTrue(routing.contains("automatic.recognize(bitmap, settings, ::recognizeRaw)"))
        val capture = source("service/CaptureService.kt")
        listOf("preEngine", "newEngine").forEach {
            val condition = capture.substringAfter("val $it = if (")
                .substringBefore(") null else OrientationRouting.resolveEngine(")
            assertTrue("$it skips orientation routing for automatic OCR",
                condition.contains("effectiveEngine == OcrEngineKind.ML_KIT_AUTO"))
        }
    }

    private fun source(path: String): String = listOf(
        File("src/main/java/com/gameocr/app/$path"), File("app/src/main/java/com/gameocr/app/$path"),
    ).first(File::isFile).readText().replace("\r\n", "\n")

    private fun projectSource(path: String): String = listOf(File(path), File("../$path"))
        .first(File::isFile).readText().replace("\r\n", "\n")
}
