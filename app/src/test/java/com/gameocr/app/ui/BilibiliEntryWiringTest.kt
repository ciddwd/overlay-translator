package com.gameocr.app.ui

import com.gameocr.app.R
import com.gameocr.app.data.OpenAiProvider
import com.gameocr.app.data.TranslatorEngine
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliEntryWiringTest {
    @Test
    fun labelSelection_tableDriven_keepsOpenAiAndBilibiliDistinct() {
        listOf(
            Triple(TranslatorEngine.OPENAI, OpenAiProvider.CUSTOM, R.string.settings_engine_openai_llm),
            Triple(TranslatorEngine.OPENAI, OpenAiProvider.BILIBILI, R.string.settings_engine_bilibili_index),
            Triple(TranslatorEngine.ANTHROPIC, OpenAiProvider.BILIBILI, R.string.settings_engine_anthropic_llm),
        ).forEach { (engine, provider, label) ->
            assertEquals(label, translatorEngineLabelRes(engine, provider))
        }
    }

    @Test
    fun allOpenAiRoutingEntrypointsResolveOnlyRequestSnapshots() {
        val routing = source("translate/RoutingTranslator.kt")
        assertEquals(7, Regex("settings\\.resolveOpenAiProvider\\(\\)").findAll(routing).count())
        assertEquals(7, Regex("localModelSnapshot\\(settings\\.resolveOpenAiProvider\\(\\)\\)").findAll(routing).count())
        assertTrue(routing.contains("TranslatorEngine.OPENAI -> openAi"))
        assertFalse(routing.contains("BilibiliIndexTranslator"))
        assertFalse(routing.contains("TranslatorEngine.BILIBILI_INDEX"))
        assertFalse(sourceFile("src/main/java/com/gameocr/app/translate/BilibiliIndexTranslator.kt").exists())
    }

    @Test
    fun settingsUiSeparatesSelectionWithoutChangingCustomConnectionFields() {
        val ui = source("ui/SettingsScreen.kt")
        assertTrue(ui.contains("selected = bilibiliSelected"))
        assertTrue(ui.contains("selected = translatorEngine == TranslatorEngine.OPENAI && !bilibiliSelected"))
        assertTrue(ui.contains("translatorEngine == TranslatorEngine.OPENAI && !bilibiliSelected"))
        assertTrue(ui.contains("openAiProvider = OpenAiProvider.BILIBILI"))
        assertTrue(ui.contains("openAiProvider = OpenAiProvider.CUSTOM"))
        assertFalse(ui.contains("index-translate.bilibili.com"))
        assertTrue(ui.contains("else if (bilibiliSelected)"))
        assertTrue(ui.contains("stringResource(R.string.settings_bilibili_index_hint)"))
        val testSection = ui.substringAfter("// —— 测试连接 ——").substringBefore("TranslatorConnectionTestPanel(")
        assertTrue(testSection.contains("translatorEngine != TranslatorEngine.GOOGLE_ML_KIT"))
        assertFalse("Bilibili must expose the shared connection test", testSection.contains("!bilibiliSelected"))
        val testCall = ui.substringAfter("viewModel.testTranslator(").substringBefore("onModels =")
        assertTrue(testCall.contains("openAiProvider = openAiProvider,"))
        val testMethod = source("ui/SettingsViewModel.kt").substringAfter("suspend fun testTranslator(")
        assertTrue(testMethod.contains("openAiProvider: OpenAiProvider"))
        assertTrue(testMethod.contains("openAiProvider = openAiProvider,"))
        assertFalse(testMethod.contains("openAiProvider = OpenAiProvider.CUSTOM,"))
        assertTrue(source("data/SettingsRepository.kt").contains("next.openAiProvider.name"))
        assertTrue(source("ui/SettingsViewModel.kt").contains("openAiProvider = openAiProvider"))
    }

    @Test
    fun settingsMerge_tableDriven_preservesProviderAndLocalModelSelection() {
        listOf(
            "ui/SettingsScreen.kt" to listOf(
                "openAiProvider = s.openAiProvider",
                "localModelId = s.localModelId",
                "openAiProvider = openAiProvider,",
                "localModelId = localModelId,",
            ),
            "ui/SettingsViewModel.kt" to listOf(
                "openAiProvider = openAiProvider,",
                "localModelId = localModelId.takeIf { translatorEngine == TranslatorEngine.LOCAL_QWEN3 }",
            ),
        ).forEach { (path, markers) ->
            val text = source(path)
            markers.forEach { assertTrue("$path: missing $it", text.contains(it)) }
            assertFalse("$path: legacy local model section must not return", text.contains("                LocalLlmSection("))
        }
    }

    @Test
    fun readyToUseHint_tableDriven_preservesOriginalChineseAndEnglishCopy() {
        listOf(
            "values" to "Ready to use — base URL, API key and model are built in. No configuration required.",
            "values-zh-rCN" to "开箱即用：接口地址、API Key、模型均已内置，无需任何配置。",
        ).forEach { (directory, text) ->
            val resources = sourceFile("src/main/res/$directory/strings.xml").readText()
            assertTrue(resources.contains("<string name=\"settings_bilibili_index_hint\">$text</string>"))
        }
    }

    private fun source(path: String): String =
        sourceFile("src/main/java/com/gameocr/app/$path").readText()

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app/$path")).first { it.exists() || it.parentFile?.exists() == true }
}
