package com.gameocr.app.onboarding

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingContentContractTest {
    @Test
    fun mangaEntranceCopy_isUserFacingAcrossSupportedLocales() {
        data class Case(
            val path: String,
            val expectedDescription: String,
        )

        listOf(
            Case(
                path = "src/main/res/values/strings.xml",
                expectedDescription = "Best for manga, webtoons, and graphic novels, including " +
                    "speech bubbles, vertical text, and independent text in the artwork.",
            ),
            Case(
                path = "src/main/res/values-zh-rCN/strings.xml",
                expectedDescription = "适合漫画、条漫和图像小说，兼顾对话气泡、竖排文字及画面中的独立文字。",
            ),
        ).forEach { case ->
            val strings = stringResources(sourceFile(case.path))
            assertEquals(case.path, case.expectedDescription, strings["onboarding_usage_manga_desc"])
            assertTrue(
                "${case.path}: selected Manga hint resource must be removed",
                "onboarding_usage_manga_applied" !in strings,
            )
        }
        val screenSource = sourceFile(
            "src/main/java/com/gameocr/app/onboarding/OnboardingScreen.kt"
        ).readText()
        assertTrue(
            "Manga selection must not render an additional hint",
            !screenSource.contains("onboarding_usage_manga_applied"),
        )
    }

    @Test
    fun japaneseMangaOfflineSummary_showsOnlyUserLevelModelsAcrossLocales() {
        data class Case(
            val path: String,
            val expected: String,
        )

        listOf(
            Case(
                path = "src/main/res/values/strings.xml",
                expected = "Manga OCR + Sakura",
            ),
            Case(
                path = "src/main/res/values-zh-rCN/strings.xml",
                expected = "日漫 OCR + Sakura",
            ),
        ).forEach { case ->
            val actual = stringResources(sourceFile(case.path))["onboarding_summary_manga_offline"].orEmpty()
            assertEquals(case.path, case.expected, actual)
            assertTrue("${case.path}: internal detector must remain hidden", !actual.contains("PP-OCRv6"))
        }
    }

    @Test
    fun mangaSummaryCopy_isResultOrientedAcrossSupportedLocales() {
        data class Case(
            val path: String,
            val expected: String,
            val forbiddenTerms: List<String>,
        )

        listOf(
            Case(
                path = "src/main/res/values/strings.xml",
                expected = "Manga recommendations are on: adaptive image styling and separate " +
                    "translation for each detected text region.",
                forbiddenTerms = listOf("box", "merge", "merging"),
            ),
            Case(
                path = "src/main/res/values-zh-rCN/strings.xml",
                expected = "已启用漫画推荐设置：自动适配画面，并按识别到的文字区域分别翻译。",
                forbiddenTerms = listOf("box", "Box", "合并"),
            ),
        ).forEach { case ->
            val actual = stringResources(sourceFile(case.path))["onboarding_summary_manga_defaults"]
            assertEquals(case.path, case.expected, actual)
            case.forbiddenTerms.forEach { term ->
                assertTrue("${case.path}: must not expose $term", !actual.orEmpty().contains(term))
            }
        }
    }

    @Test
    fun welcomeCopy_isTableDrivenAcrossSupportedLocales() {
        data class Case(
            val path: String,
            val expectedTitle: String,
            val expectedBody: String,
            val expectedOpenSourceTitle: String,
            val expectedOpenSourceBody: String,
            val expectedServiceNote: String,
            val expectedAction: String,
        )

        val cases = listOf(
            Case(
                path = "src/main/res/values/strings.xml",
                expectedTitle = "Welcome to Screen Translator",
                expectedBody = "A few quick choices will set up what you translate most often, " +
                    "your languages, display behavior, and translation services. " +
                    "You can change any of these later in Settings.",
                expectedOpenSourceTitle = "Open source and free to use",
                expectedOpenSourceBody = "This app is fully open source. It does not charge activation, " +
                    "membership, or license fees. Download only from official release channels and beware " +
                    "of paid resellers, impersonators, or claims that payment is required to unlock features.",
                expectedServiceNote = "Cloud LLM, TTS, and other third-party services may charge separately. " +
                    "Keep API keys private and never share them.",
                expectedAction = "I understand — start setup",
            ),
            Case(
                path = "src/main/res/values-zh-rCN/strings.xml",
                expectedTitle = "欢迎使用屏译\\nScreen Translator",
                expectedBody = "接下来将通过几个简单步骤，选择你最常翻译的内容，并完成语言、展示方式和翻译服务等基础配置。" +
                    "所有选项之后都可以在设置中修改。",
                expectedOpenSourceTitle = "完全开源，免费使用",
                expectedOpenSourceBody = "本软件完全开源，所有功能均不收取激活费、会员费或授权费。" +
                    "请通过官方发布渠道下载，谨防付费倒卖、冒充官方或以“解锁功能”为名的收费行为。",
                expectedServiceNote = "云端 LLM、TTS 等第三方服务可能由服务商单独计费，与本软件无关。" +
                    "请妥善保管 API Key，不要提供给他人。",
                expectedAction = "我已了解，开始设置",
            ),
        )

        cases.forEach { case ->
            val strings = stringResources(sourceFile(case.path))
            assertEquals(case.path, case.expectedTitle, strings["onboarding_welcome_title"])
            assertEquals(case.path, case.expectedBody, strings["onboarding_welcome_body"])
            assertEquals(
                case.path,
                case.expectedOpenSourceTitle,
                strings["onboarding_open_source_title"],
            )
            assertEquals(
                case.path,
                case.expectedOpenSourceBody,
                strings["onboarding_open_source_body"],
            )
            assertEquals(
                case.path,
                case.expectedServiceNote,
                strings["onboarding_open_source_service_note"],
            )
            assertEquals(case.path, case.expectedAction, strings["onboarding_welcome_action"])
        }
    }

    @Test
    fun welcomeStep_isStandaloneAndAppearsBeforeTheLanguageStep() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/onboarding/OnboardingScreen.kt"
        ).readText()
        val welcomePage = source
            .substringAfter("private fun WelcomePage()")
            .substringBefore("private fun SourceLanguagePage(")
        val sourcePage = source
            .substringAfter("private fun SourceLanguagePage(")
            .substringBefore("private fun TargetLanguagePage(")

        data class Marker(val name: String, val value: String)
        val welcomeMarkers = listOf(
            Marker("app icon background", "R.drawable.ic_launcher_background"),
            Marker("app icon foreground", "R.drawable.ic_launcher_foreground"),
            Marker("welcome title", "R.string.onboarding_welcome_title"),
            Marker("welcome body", "R.string.onboarding_welcome_body"),
            Marker("open source title", "R.string.onboarding_open_source_title"),
            Marker("open source body", "R.string.onboarding_open_source_body"),
            Marker("third-party service note", "R.string.onboarding_open_source_service_note"),
        )
        welcomeMarkers.forEach { marker ->
            assertTrue("${marker.name} is missing", welcomePage.contains(marker.value))
            assertTrue(
                "${marker.name} must not share the language page",
                !sourcePage.contains(marker.value),
            )
        }
        assertTrue(
            "welcome page must use the app icon instead of the generic language icon",
            !welcomePage.contains("Icons.Default.Language"),
        )
        assertTrue(
            "source language question is missing",
            sourcePage.contains("R.string.onboarding_source_title"),
        )
    }

    @Test
    fun recommendedModelsStep_isUserFacingAcrossSupportedLocales() {
        data class Case(
            val path: String,
            val expectedTitle: String,
            val expectedBody: String,
            val expectedDownload: String,
        )
        val cases = listOf(
            Case(
                path = "src/main/res/values/strings.xml",
                expectedTitle = "Prepare offline models",
                expectedBody = "These models were selected for your languages and usage. " +
                    "Download everything you need in one step.",
                expectedDownload = "Download required models",
            ),
            Case(
                path = "src/main/res/values-zh-rCN/strings.xml",
                expectedTitle = "准备离线模型",
                expectedBody = "已根据你的语言和用途选择以下模型，可在这一步一次完成下载。",
                expectedDownload = "下载所需模型",
            ),
        )

        cases.forEach { case ->
            val strings = stringResources(sourceFile(case.path))
            assertEquals(case.path, case.expectedTitle, strings["onboarding_recommended_models_title"])
            assertEquals(case.path, case.expectedBody, strings["onboarding_recommended_models_body"])
            assertEquals(case.path, case.expectedDownload, strings["onboarding_recommended_models_download"])
            assertTrue(case.path, strings["onboarding_recommended_models_ocr_desc"].orEmpty().isNotBlank())
            assertTrue(
                case.path,
                strings["onboarding_recommended_models_translation_desc"].orEmpty().isNotBlank(),
            )
        }
    }

    @Test
    fun recommendedOcrAndHyMt2_shareOnePageAndOneDownloadAction() {
        val screenSource = sourceFile(
            "src/main/java/com/gameocr/app/onboarding/OnboardingScreen.kt"
        ).readText()
        val viewModelSource = sourceFile(
            "src/main/java/com/gameocr/app/onboarding/OnboardingViewModel.kt"
        ).readText()
        val markers = listOf(
            "OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD",
            "private fun RecommendedModelsDownloadPage(",
            "R.string.onboarding_recommended_models_download",
            "onDownloadRecommendedModels = ::requestRecommendedModelsDownload",
            "viewModel.downloadMissingRecommendedModels(currentDraft)",
        )

        markers.forEach { marker ->
            assertTrue("$marker is missing", screenSource.contains(marker))
        }
        listOf(
            "OnboardingStep.PADDLE_OCR_DOWNLOAD",
            "OnboardingStep.HY_MT2_OFFLINE_DOWNLOAD",
            "private fun PaddleOcrDownloadPage(",
            "private fun HyMt2DownloadPage(",
        ).forEach { marker ->
            assertTrue("standalone download path must be removed: $marker", !screenSource.contains(marker))
        }
        assertTrue(
            "OCR and Hy-MT2 must be submitted through one download plan",
            viewModelSource.contains("recommendedModelsDownloadSpecs(") &&
                viewModelSource.contains("ModelDownloadSpec.paddle(it)") &&
                viewModelSource.contains(
                    "ModelDownloadSpec.llm(LlmModelKind.HY_MT2_1_8B_Q4_K_M)"
                ) &&
                viewModelSource.contains("enqueueIndependentlyAndAwait(specs, onProgress)"),
        )
    }

    @Test
    fun japaneseMangaRecommendation_downloadsDetectorRecognizerAndOptionalSakura() {
        val screenSource = sourceFile(
            "src/main/java/com/gameocr/app/onboarding/OnboardingScreen.kt"
        ).readText()
        val viewModelSource = sourceFile(
            "src/main/java/com/gameocr/app/onboarding/OnboardingViewModel.kt"
        ).readText()
        assertTrue(
            "Japanese manga offline download must include Sakura",
            viewModelSource.contains("ModelDownloadSpec.llm(LlmModelKind.SAKURA_1_5B_Q4)"),
        )
        assertTrue(
            "Manga model step must include the PP-OCRv6 Small detector",
            viewModelSource.contains("ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL)"),
        )
        assertTrue(
            "Manga model step must include the Manga OCR recognizer",
            viewModelSource.contains("ModelDownloadSpec.mangaOcr()"),
        )
        val mangaPage = screenSource
            .substringAfter("private fun MangaOfflineDownloadPage(")
            .substringBefore("private fun CloudConfigPage(")
        assertTrue(
            "The Manga page must expose only OCR and optional translation readiness",
            listOf(
                "ready = readiness.ocrReady",
                "ready = readiness.sakuraReady",
            ).all(mangaPage::contains),
        )
        assertTrue(
            "The internal Paddle dependency must not have its own visible row",
            !mangaPage.contains("readiness.paddleReady") &&
                !mangaPage.contains("onboarding_manga_offline_paddle"),
        )
    }

    @Test
    fun cloudProviderChoices_keepEqualColumnsAndMarqueeOverflow_tableDriven() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/onboarding/OnboardingScreen.kt"
        ).readText()
        val cloudPage = source
            .substringAfter("private fun CloudConfigPage(")
            .substringBefore("private fun SummaryPage(")
        val choiceChip = source
            .substringAfter("private fun ChoiceChip(")
            .substringBefore("private fun StatusRow(")

        data class Case(
            val name: String,
            val source: String,
            val requiredMarkers: List<String>,
            val forbiddenMarkers: List<String> = emptyList(),
        )

        val cases = listOf(
            Case(
                name = "all rows keep two equal-width columns",
                source = cloudPage,
                requiredMarkers = listOf(
                    "CloudProvider.entries.chunked(2)",
                    "modifier = Modifier.weight(1f)",
                    "if (rowProviders.size == 1) Spacer(Modifier.weight(1f))",
                ),
                forbiddenMarkers = listOf("FlowRow("),
            ),
            Case(
                name = "selected choices retain the leading check icon",
                source = choiceChip,
                requiredMarkers = listOf(
                    "if (selected)",
                    "modifier = Modifier.size(18.dp)",
                    "Spacer(Modifier.width(6.dp))",
                ),
            ),
            Case(
                name = "only overflowing single-line labels animate",
                source = choiceChip,
                requiredMarkers = listOf(
                    "modifier = Modifier.basicMarquee()",
                    "maxLines = 1",
                ),
            ),
        )

        cases.forEach { case ->
            case.requiredMarkers.forEach { marker ->
                assertTrue("${case.name}: missing $marker", case.source.contains(marker))
            }
            case.forbiddenMarkers.forEach { marker ->
                assertTrue("${case.name}: must not contain $marker", !case.source.contains(marker))
            }
        }
    }

    private fun stringResources(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return document.getElementsByTagName("string").let { nodes ->
            buildMap {
                repeat(nodes.length) { index ->
                    val node = nodes.item(index)
                    put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
                }
            }
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path")
}
