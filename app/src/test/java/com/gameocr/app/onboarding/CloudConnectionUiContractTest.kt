package com.gameocr.app.onboarding

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslatorEngine
import java.io.File
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class CloudConnectionUiContractTest {
    @Test fun documentationTable_allNamedProvidersHaveOfficialPages_customDoesNotInventOne() {
        val hosts = mapOf(
            CloudProvider.DEEPSEEK to "api-docs.deepseek.com", CloudProvider.KIMI to "platform.kimi.com",
            CloudProvider.MINIMAX to "platform.minimax.cn", CloudProvider.GLM to "docs.bigmodel.cn",
            CloudProvider.MIMO to "mimo.mi.com", CloudProvider.OPENAI to "developers.openai.com",
            CloudProvider.CLAUDE to "platform.claude.com", CloudProvider.GEMINI to "ai.google.dev",
            CloudProvider.MODELSCOPE to "modelscope.cn", CloudProvider.OPENROUTER to "openrouter.ai",
            CloudProvider.AIHUBMIX to "docs.aihubmix.com", CloudProvider.AI_302 to "doc.302.ai",
            CloudProvider.SCNET to "www.scnet.cn",
        )
        assertEquals(CloudProvider.entries.toSet() - CloudProvider.CUSTOM, hosts.keys)
        hosts.forEach { (provider, host) ->
            val url = URI(provider.documentationUrl!!)
            assertEquals(provider.name, "https", url.scheme)
            assertEquals(provider.name, host, url.host)
            assertNull(url.userInfo)
        }
        assertNull(CloudProvider.CUSTOM.documentationUrl)
    }

    @Test fun draftTable_testsUseUnsavedFieldsAndExistingTimeoutForEitherProtocol() {
        CloudProvider.entries.forEach { provider ->
            val draft = OnboardingPolicy.selectCloudProvider(OnboardingDraft(
                translationMethod = OnboardingTranslationMethod.CLOUD_LLM,
            ), provider).copy(cloudApiKey = "fake-key", cloudBaseUrl = "https://gateway.example/v1/", cloudModel = "unsaved-model")
            val original = Settings(apiTimeoutSeconds = 57)
            val settings = OnboardingPolicy.apply(original, draft)
            assertEquals(57, settings.apiTimeoutSeconds)
            if (provider.protocol == CloudApiProtocol.ANTHROPIC) {
                assertEquals(TranslatorEngine.ANTHROPIC, settings.translatorEngine)
                assertEquals(draft.cloudApiKey, settings.anthropicApiKey)
                assertEquals(draft.cloudBaseUrl, settings.anthropicBaseUrl)
                assertEquals(draft.cloudModel, settings.anthropicModel)
            } else {
                assertEquals(TranslatorEngine.OPENAI, settings.translatorEngine)
                assertEquals(draft.cloudApiKey, settings.apiKey)
                assertEquals(draft.cloudBaseUrl, settings.baseUrl)
                assertEquals(draft.cloudModel, settings.model)
            }
            assertNotEquals("fake-key", original.apiKey)
        }
    }

    @Test fun uiSmokeTable_reusesPipelineLinkStyleAndClearsStaleResults() {
        val setup = source("onboarding/OnboardingScreen.kt")
        val cloud = setup.substringAfter("private fun CloudConfigPage(").substringBefore("private fun cloudProviderLabel")
        val shared = source("ui/TranslatorConnectionTestPanel.kt")
        val link = source("ui/ExternalBrowserLinkButton.kt")
        val viewModel = source("onboarding/OnboardingViewModel.kt")
        val testMethod = viewModel.substringAfter("suspend fun testCloudConnection").substringBefore("suspend fun save")
        listOf(
            "link before optional region selector" to (cloud.indexOf("ExternalBrowserLinkButton(") in 0 until cloud.indexOf("if (draft.cloudProvider.supportsRegions)")),
            "link before Base URL" to (cloud.indexOf("ExternalBrowserLinkButton(") in 0 until cloud.indexOf("value = draft.cloudBaseUrl")),
            "provider controls doc" to cloud.contains("draft.cloudProvider.documentationUrl?.let"),
            "single line" to link.contains("maxLines = 1"),
            "no wrap" to link.contains("softWrap = false"),
            "ellipsis" to link.contains("overflow = TextOverflow.Ellipsis"),
            "button after model" to (cloud.indexOf("TranslatorConnectionTestPanel(") > cloud.indexOf("value = draft.cloudModel")),
            "both layouts wired" to (Regex("onTestCloudConnection = viewModel::testCloudConnection").findAll(setup).count() == 2),
            "settings shared panel" to source("ui/SettingsScreen.kt").contains("TranslatorConnectionTestPanel("),
            "settings shared tester" to source("ui/SettingsViewModel.kt").contains("return connectionTester.test(temp)"),
            "setup shared tester" to testMethod.contains("connectionTester.test(OnboardingPolicy.apply(settingsRepository.get(), draft))"),
            "testing does not save" to !testMethod.contains("settingsRepository.update"),
            "shared model picker" to cloud.contains("TranslatorModelPicker(fetchedModels)"),
            "test uses current draft" to cloud.contains("onTestConnection(draft)"),
            "results scoped to inputs" to shared.contains("key(inputKey)"),
            "late response suppressed" to shared.contains("ensureActive()\n                                result = tested"),
            "repeat disabled" to shared.contains("enabled = !running"),
            "balance has own result" to shared.contains("when (val balance = tested.balance)"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

    @Test fun localizedCopy_containsOnlyApprovedBalanceMessagesAndPlainHelp() {
        listOf("values", "values-zh-rCN").forEach { folder ->
            val file = projectFile("src/main/res/$folder/strings.xml")
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            fun value(name: String): String = (0 until nodes.length).map { nodes.item(it) }
                .single { it.attributes.getNamedItem("name").nodeValue == name }.textContent
            assertTrue(value("settings_test_balance_format").contains("%1\$s"))
            assertTrue(value("settings_test_balance_failed").isNotBlank())
            assertTrue(value("onboarding_cloud_body").contains("API Key"))
            assertFalse(value("onboarding_cloud_body").contains("订阅专线"))
            assertTrue(value("onboarding_cloud_body").contains(
                if (folder == "values-zh-rCN") "服务商按名称排序，顺序不代表推荐或排名。"
                else "Providers are sorted by name. The order does not indicate recommendations or rankings.",
            ))
        }
    }

    private fun source(path: String) = projectFile("src/main/java/com/gameocr/app/$path").readText().replace("\r\n", "\n")
    private fun projectFile(path: String): File = listOf(File(path), File("app/$path")).first { it.exists() }
}
