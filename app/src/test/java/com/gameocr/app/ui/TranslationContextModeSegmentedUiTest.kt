package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationContextModeSegmentedUiTest {
    private val source by lazy {
        listOf(
            File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt"),
            File("app/src/main/java/com/gameocr/app/ui/SettingsScreen.kt"),
        ).first(File::isFile).readText()
    }

    private val chineseStrings by lazy {
        listOf(
            File("src/main/res/values-zh-rCN/strings.xml"),
            File("app/src/main/res/values-zh-rCN/strings.xml"),
        ).first(File::isFile).readText()
    }

    @Test
    fun translationMode_usesThreePartDisplayStyle_tableDriven() {
        val start = source.indexOf("private fun TranslationContextModeSelector(")
        val end = source.indexOf("private fun translationContextModeLabelRes", start)
        val snippet = source.substring(start, end)
        listOf(
            "SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth())",
            "modes.forEachIndexed { index, mode ->",
            "SegmentedButton(",
            "SegmentedButtonDefaults.itemShape(index, modes.size)",
            "selected = value == mode",
            "icon = {}",
        ).forEach { marker -> assertTrue(marker, marker in snippet) }
    }

    @Test
    fun translationMode_isFollowedByStreamingWithoutASecondGroupingSwitch() {
        val start = source.indexOf("private fun TranslationAssistanceSettings(")
        val end = source.indexOf("private fun supportsTranslationPromptContext", start)
            .takeIf { it > start } ?: source.length
        val snippet = source.substring(start, end)
        val mode = snippet.indexOf("TranslationContextModeSelector(")
        val streaming = snippet.indexOf("R.string.settings_streaming")
        assertTrue("translation mode exists", mode >= 0)
        assertTrue("streaming exists", streaming >= 0)
        assertTrue("translation mode before streaming", mode < streaming)
        assertTrue("same-segment switch removed", "settings_cross_line_context" !in snippet)
    }

    @Test
    fun translationMode_copy_isUserFacing_tableDriven() {
        data class Case(val name: String, val expected: String)
        listOf(
            Case("two-character screen label", ">同屏</string>"),
            Case("fast explains speed and use", "分别翻译每段文字，速度最快，适合大多数场景。"),
            Case("screen explains current visual context", "结合当前画面的全部文字翻译，对话衔接更自然，但速度稍慢。"),
            Case("continuous covers manga video and games", "适合漫画、视频和游戏等连续内容，但速度最慢。"),
        ).forEach { case -> assertTrue(case.name, case.expected in chineseStrings) }
    }
}
