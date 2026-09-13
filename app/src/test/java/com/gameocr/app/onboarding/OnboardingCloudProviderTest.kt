package com.gameocr.app.onboarding

import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCloudProviderTest {
    private data class RegionCase(
        val provider: CloudProvider,
        val mainland: String,
        val international: String,
    )

    private val regions = listOf(
        RegionCase(CloudProvider.AI_302, "https://api.302ai.cn/v1/", "https://api.302.ai/v1/"),
        RegionCase(CloudProvider.KIMI, "https://api.moonshot.cn/v1/", "https://api.moonshot.ai/v1/"),
        RegionCase(CloudProvider.MINIMAX, "https://api.minimaxi.com/v1/", "https://api.minimax.io/v1/"),
        RegionCase(CloudProvider.GLM, "https://open.bigmodel.cn/api/paas/v4/", "https://api.z.ai/api/paas/v4/"),
    )

    @Test
    fun providerOrder_isStableByBrandName_customLastAndScnetOnlyOnce() {
        val expected = listOf(
            CloudProvider.AI_302, CloudProvider.AIHUBMIX, CloudProvider.CLAUDE,
            CloudProvider.DEEPSEEK, CloudProvider.GEMINI, CloudProvider.GLM,
            CloudProvider.KIMI, CloudProvider.MIMO, CloudProvider.MINIMAX,
            CloudProvider.MODELSCOPE, CloudProvider.OPENAI, CloudProvider.OPENROUTER,
            CloudProvider.SCNET, CloudProvider.CUSTOM,
        )
        assertEquals(expected, CloudProvider.sortedChoices)
        CloudProvider.sortedChoices.filterNot { it == CloudProvider.CUSTOM }.zipWithNext().forEach { (a, b) ->
            assertTrue("${a.sortName} before ${b.sortName}", a.sortName.compareTo(b.sortName, ignoreCase = true) <= 0)
        }
        assertEquals(CloudProvider.entries.toSet(), CloudProvider.sortedChoices.toSet())
        assertEquals(1, CloudProvider.sortedChoices.count { it == CloudProvider.SCNET })
    }

    @Test
    fun regionalChoices_areLimitedToTheFourVerifiedProviders() {
        CloudProvider.entries.forEach { provider ->
            val expected = regions.firstOrNull { it.provider == provider }
            assertEquals(provider.name, expected != null, provider.supportsRegions)
            assertEquals(provider.name, expected?.mainland, provider.baseUrlFor(CloudApiRegion.MAINLAND_CHINA))
            assertEquals(provider.name, expected?.international, provider.baseUrlFor(CloudApiRegion.INTERNATIONAL))
            if (expected == null) {
                val draft = OnboardingDraft(cloudProvider = provider, cloudApiKey = "test-key")
                CloudApiRegion.entries.forEach { region ->
                    assertSame(provider.name, draft, OnboardingPolicy.selectCloudApiRegion(draft, region))
                }
                assertNull(provider.name, OnboardingPolicy.cloudApiRegion(draft))
            }
        }
    }

    @Test
    fun regionSwitch_changesOnlyDraftUrlAndClearsUnverifiedCredentials_notModelOrOtherSettings() {
        regions.forEach { case ->
            CloudApiRegion.entries.forEach { region ->
                val otherRegion = if (region == CloudApiRegion.MAINLAND_CHINA) {
                    CloudApiRegion.INTERNATIONAL
                } else CloudApiRegion.MAINLAND_CHINA
                val draft = OnboardingDraft(
                    cloudProvider = case.provider,
                    cloudBaseUrl = case.provider.baseUrlFor(otherRegion)!!,
                    cloudApiKey = "other-region-key",
                    cloudModel = "user-selected-model",
                )
                val selected = OnboardingPolicy.selectCloudApiRegion(draft, region)
                assertEquals(case.provider.name, draft.copy(
                    cloudBaseUrl = case.provider.baseUrlFor(region)!!,
                    cloudApiKey = "",
                ), selected)
                val withKey = selected.copy(cloudApiKey = "matching-region-key")
                assertSame(withKey, OnboardingPolicy.selectCloudApiRegion(withKey, region))
                assertSame(withKey, OnboardingPolicy.selectCloudProvider(withKey, case.provider))
            }
        }
    }

    @Test
    fun setupSmoke_allVerifiedRegionalEndpoints_applyAndReopenWithoutLosingRegionModelOrKey() {
        regions.forEach { case ->
            CloudApiRegion.entries.forEach { region ->
                val endpoint = case.provider.baseUrlFor(region)!!
                val draft = OnboardingPolicy.selectCloudProvider(
                    OnboardingDraft(translationMethod = OnboardingTranslationMethod.CLOUD_LLM),
                    case.provider,
                ).let { OnboardingPolicy.selectCloudApiRegion(it, region) }.copy(
                    cloudModel = "chosen-model", cloudApiKey = "local-test-key",
                )
                assertNull(case.provider.name, OnboardingPolicy.cloudConfigError(draft))
                val settings = OnboardingPolicy.apply(Settings(), draft)
                assertEquals(TranslatorEngine.OPENAI, settings.translatorEngine)
                assertEquals(TranslationContextMode.PAGE_CONTEXT, settings.translationContextMode)
                assertEquals(endpoint, settings.baseUrl)
                assertEquals("chosen-model", settings.model)
                assertEquals("local-test-key", settings.apiKey)
                val restored = OnboardingPolicy.fromSettings(settings)
                assertEquals(case.provider, restored.cloudProvider)
                assertEquals(region, OnboardingPolicy.cloudApiRegion(restored))
                assertEquals(endpoint, restored.cloudBaseUrl)
                assertEquals(settings.model, restored.cloudModel)
                assertEquals(settings.apiKey, restored.cloudApiKey)
            }
        }
    }

    @Test
    fun manualUrls_areNotOverwrittenOrMisidentifiedByHostSuffixPathProtocolOrQuery() {
        data class Case(val url: String, val region: CloudApiRegion?)
        val cases = listOf(
            Case(" https://API.MOONSHOT.AI:443/v1/// ", CloudApiRegion.INTERNATIONAL),
            Case("https://api.moonshot.cn/v1", CloudApiRegion.MAINLAND_CHINA),
            Case("https://api.moonshot.ai/anthropic", null),
            Case("https://api.moonshot.ai/V1", null),
            Case("http://api.moonshot.ai/v1", null),
            Case("https://api.moonshot.ai:8443/v1", null),
            Case("https://api.moonshot.ai.evil.example/v1", null),
            Case("https://api.moonshot.ai@evil.example/v1", null),
            Case("https://user@api.moonshot.ai/v1", null),
            Case("https://api.moonshot.ai/v1?route=other", null),
            Case("https://api.moonshot.ai/v1#other", null),
            Case("https://gateway.example/custom/v1", null),
            Case("", null),
            Case("not a URL", null),
        )
        cases.forEach { case ->
            val draft = OnboardingDraft(
                cloudProvider = CloudProvider.KIMI, cloudBaseUrl = case.url,
                cloudModel = "custom-model", cloudApiKey = "custom-key",
            )
            assertEquals(case.url, case.region, OnboardingPolicy.cloudApiRegion(draft))
            assertSame(case.url, draft, OnboardingPolicy.selectCloudProvider(draft, CloudProvider.KIMI))
            val restored = OnboardingPolicy.fromSettings(Settings(
                translatorEngine = TranslatorEngine.OPENAI, baseUrl = case.url,
                apiKey = draft.cloudApiKey, model = draft.cloudModel,
            ))
            assertEquals(case.url, if (case.region == null) CloudProvider.CUSTOM else CloudProvider.KIMI,
                restored.cloudProvider)
            assertEquals(case.url, restored.cloudBaseUrl)
            assertEquals(draft.cloudApiKey, restored.cloudApiKey)
            assertEquals(draft.cloudModel, restored.cloudModel)
        }
    }

    @Test
    fun switchingProviders_doesNotCarryOverCredentialsOrModels_andSameSelectionIsANoOp() {
        CloudProvider.entries.forEach { from ->
            CloudProvider.entries.forEach { to ->
                val draft = OnboardingDraft(
                    cloudProvider = from, cloudBaseUrl = "https://manual.example/v1/",
                    cloudApiKey = "old-provider-key", cloudModel = "manual-model",
                )
                val selected = OnboardingPolicy.selectCloudProvider(draft, to)
                if (from == to) assertSame(draft, selected) else {
                    assertEquals("$from -> $to", "", selected.cloudApiKey)
                    assertEquals(to.baseUrl, selected.cloudBaseUrl)
                    assertEquals("$from -> $to", "", selected.cloudModel)
                    assertEquals(to, selected.cloudProvider)
                }
            }
        }
    }

    @Test
    fun modelDefaults_firstUseAndSwitchingAreEmpty_savedOrSelectedModelsArePreserved() {
        assertEquals("", OnboardingDraft().cloudModel)
        CloudProvider.entries.forEach { provider ->
            val initial = OnboardingPolicy.selectCloudProvider(
                OnboardingDraft(translationMethod = OnboardingTranslationMethod.CLOUD_LLM), provider,
            ).copy(cloudBaseUrl = provider.baseUrl.ifBlank { "https://custom.example/v1/" }, cloudApiKey = "test-key")
            assertEquals(provider.name, "", initial.cloudModel)
            assertEquals(provider.name, CloudConfigError.MODEL_REQUIRED, OnboardingPolicy.cloudConfigError(initial))
            val configured = initial.copy(cloudModel = "user-chosen-model")
            assertNull(OnboardingPolicy.cloudConfigError(configured))
            assertSame(configured, OnboardingPolicy.selectCloudProvider(configured, provider))
            val persisted = OnboardingPolicy.apply(Settings(), configured)
            assertEquals(provider.name, configured.cloudModel, OnboardingPolicy.fromSettings(persisted).cloudModel)
        }
    }

    @Test
    fun aggregatorSetupSmoke_requiresUserModelThenUsesTheExistingOpenAiPipeline() {
        listOf(CloudProvider.MODELSCOPE, CloudProvider.OPENROUTER, CloudProvider.AIHUBMIX,
            CloudProvider.AI_302, CloudProvider.SCNET).forEach { provider ->
            val draft = OnboardingPolicy.selectCloudProvider(
                OnboardingDraft(translationMethod = OnboardingTranslationMethod.CLOUD_LLM), provider,
            ).copy(cloudApiKey = "test-key")
            assertEquals(provider.name, CloudConfigError.MODEL_REQUIRED, OnboardingPolicy.cloudConfigError(draft))
            val configured = draft.copy(cloudModel = "account/model-id")
            assertNull(OnboardingPolicy.cloudConfigError(configured))
            val settings = OnboardingPolicy.apply(Settings(), configured)
            assertEquals(TranslatorEngine.OPENAI, settings.translatorEngine)
            assertEquals(provider.baseUrl, settings.baseUrl)
            assertEquals(configured.cloudModel, settings.model)
            assertEquals(provider, OnboardingPolicy.fromSettings(settings).cloudProvider)
        }
    }

    @Test
    fun localizedCopyAndUiWiring_reuseSettingsDropdown_keepEditableUrlAndSummaryLabels() {
        val expected = mapOf(
            "values" to listOf("API region", "Mainland China", "International", "ModelScope", "SCNet"),
            "values-zh-rCN" to listOf("接口区域", "中国大陆", "国际", "魔搭 ModelScope", "国家超算互联网（SCNet）"),
        )
        val names = listOf("onboarding_cloud_region", "onboarding_cloud_region_mainland",
            "onboarding_cloud_region_international", "onboarding_cloud_provider_modelscope",
            "onboarding_cloud_provider_scnet")
        expected.forEach { (locale, values) ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(sourceFile("src/main/res/$locale/strings.xml")).getElementsByTagName("string")
            val strings = (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
            names.zip(values).forEach { (name, value) -> assertEquals("$locale/$name", value, strings[name]) }
        }
        val source = sourceFile("src/main/java/com/gameocr/app/onboarding/OnboardingScreen.kt").readText()
        val page = source.substringAfter("private fun CloudConfigPage(").substringBefore("private fun cloudProviderLabel(")
        listOf("CloudProvider.sortedChoices.chunked(2)", "if (draft.cloudProvider.supportsRegions)",
            "SettingsOptionDropdown<CloudApiRegion?>", "OnboardingPolicy.cloudApiRegion(draft)",
            "OnboardingPolicy.selectCloudApiRegion(draft, region)",
            "onDraftChange(draft.copy(cloudBaseUrl = it))", "cloudProviderLabel(provider)").forEach {
            assertTrue(it, page.contains(it))
        }
        assertTrue(page.indexOf("onboarding_cloud_region)") < page.indexOf("onboarding_cloud_url)"))
        assertTrue(source.contains("cloudProviderLabel(draft.cloudProvider)"))
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile } ?: error("Missing source: $path")
}
