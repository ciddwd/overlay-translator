package com.gameocr.app.onboarding

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingContentContractTest {
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
