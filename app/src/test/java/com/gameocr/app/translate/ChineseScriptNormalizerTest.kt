package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseScriptNormalizerTest {

    @Test
    fun targetScriptFor_recognizesSimplifiedAndTraditionalChineseCodes() {
        data class Case(
            val targetLang: String,
            val expected: ChineseScriptNormalizer.TargetScript?,
        )

        val cases = listOf(
            Case("zh-CN", ChineseScriptNormalizer.TargetScript.SIMPLIFIED),
            Case(" zh ", ChineseScriptNormalizer.TargetScript.SIMPLIFIED),
            Case("zh-Hans", ChineseScriptNormalizer.TargetScript.SIMPLIFIED),
            Case("zh-Hans-CN", ChineseScriptNormalizer.TargetScript.SIMPLIFIED),
            Case("zh-SG", ChineseScriptNormalizer.TargetScript.SIMPLIFIED),
            Case("zh-CHS", ChineseScriptNormalizer.TargetScript.SIMPLIFIED),
            Case("zh-TW", ChineseScriptNormalizer.TargetScript.TRADITIONAL),
            Case("zh-Hant", ChineseScriptNormalizer.TargetScript.TRADITIONAL),
            Case("zh-Hant-TW", ChineseScriptNormalizer.TargetScript.TRADITIONAL),
            Case("zh-HK", ChineseScriptNormalizer.TargetScript.TRADITIONAL),
            Case("zh-CHT", ChineseScriptNormalizer.TargetScript.TRADITIONAL),
            Case("en", null),
            Case("auto", null),
            Case("", null),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                ChineseScriptNormalizer.targetScriptFor(case.targetLang)
            )
        }
    }

    @Test
    fun normalizeForTarget_convertsCommonTraditionalOutputToSimplifiedWhenTargetIsZhCn() {
        data class Case(
            val name: String,
            val input: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "common translation sentence",
                input = "後臺資訊軟體裏面還沒收到，這個畫面與臺灣伺服器已啟動。",
                expected = "后台资讯软件里面还没收到，这个画面与台湾服务器已启动。"
            ),
            Case(
                name = "ui words",
                input = "請選擇語言，顯示翻譯結果，錯誤時重新偵測識別。",
                expected = "请选择语言，显示翻译结果，错误时重新检测识别。"
            ),
            Case(
                name = "alias zh target",
                input = "繁體中文轉簡體中文",
                expected = "繁体中文转简体中文"
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                ChineseScriptNormalizer.normalizeForTarget(case.input, "zh-CN")
            )
        }
        assertEquals(
            "繁体中文转简体中文",
            ChineseScriptNormalizer.normalizeForTarget("繁體中文轉簡體中文", "zh")
        )
    }

    @Test
    fun normalizeForTarget_convertsCommonSimplifiedOutputToTraditionalWhenTargetIsZhTw() {
        data class Case(
            val name: String,
            val input: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "common translation sentence",
                input = "后台资讯软件里面还没收到，这个画面与台湾服务器已启动。",
                expected = "後臺資訊軟體裏面還沒收到，這個畫面與臺灣伺服器已啟動。"
            ),
            Case(
                name = "ui words",
                input = "请选择语言，显示翻译结果，错误时重新检测识别。",
                expected = "請選擇語言，顯示翻譯結果，錯誤時重新偵測識別。"
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                ChineseScriptNormalizer.normalizeForTarget(case.input, "zh-TW")
            )
        }
        assertEquals(
            "繁體中文轉簡體中文",
            ChineseScriptNormalizer.normalizeForTarget("繁体中文转简体中文", "zh-Hant")
        )
    }

    @Test
    fun normalizeForTarget_leavesNonChineseTargetsUnchanged() {
        val input = "後臺資訊 software"
        assertEquals(input, ChineseScriptNormalizer.normalizeForTarget(input, "en"))
        assertEquals(input, ChineseScriptNormalizer.normalizeForTarget(input, "auto"))
    }
}
