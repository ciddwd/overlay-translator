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
        )

        val cases = listOf(
            Case(
                path = "src/main/res/values/strings.xml",
                expectedTitle = "Welcome to Screen Translator",
                expectedBody = "We’ll guide you through a few quick choices to understand " +
                    "what you translate most often and set up the languages, display behavior, " +
                    "and translation services. You can change any of these later in Settings.",
            ),
            Case(
                path = "src/main/res/values-zh-rCN/strings.xml",
                expectedTitle = "欢迎使用屏译\\nScreen Translator",
                expectedBody = "接下来，我们会通过几个简单步骤了解你最常翻译的内容，并完成语言、展示方式和翻译服务等基础配置。" +
                    "所有选项之后都可以在设置中修改。",
            ),
        )

        cases.forEach { case ->
            val strings = stringResources(sourceFile(case.path))
            assertEquals(case.path, case.expectedTitle, strings["onboarding_welcome_title"])
            assertEquals(case.path, case.expectedBody, strings["onboarding_welcome_body"])
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
