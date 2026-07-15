package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MainAndArcMenuCopyTest {

    @Test
    fun usageInstructions_describeTheCurrentFloatingBallGestures() {
        data class Case(val path: String, val expected: String)

        listOf(
            Case(
                "src/main/res/values/strings.xml",
                "Floating ball: tap to run the current action; long-press to open the arc menu; drag to move.",
            ),
            Case(
                "src/main/res/values-zh-rCN/strings.xml",
                "悬浮圆球：单击 = 执行当前操作；长按 = 打开弧形菜单；拖动 = 移动位置",
            ),
        ).forEach { case ->
            val value = stringResourceValue(case.path, "main_usage_text")
            assertEquals(case.path, case.expected, value)
            assertFalse(case.path, value.contains("auto loop", ignoreCase = true))
            assertFalse(case.path, value.contains("自动循环"))
        }
    }

    @Test
    fun loopSlotCopy_describesSwitchingTheMainBallAction() {
        data class Case(val path: String, val expected: String)

        listOf(
            Case(
                "src/main/res/values/strings.xml",
                "Switch ball action — Loop translation",
            ),
            Case(
                "src/main/res/values-zh-rCN/strings.xml",
                "切换主球操作 — 循环翻译",
            ),
        ).forEach { case ->
            assertEquals(
                case.path,
                case.expected,
                stringResourceValue(case.path, "settings_arc_menu_item_loop"),
            )
        }
    }

    private fun stringResourceValue(resourcePath: String, resourceName: String): String {
        val file = listOf(File(resourcePath), File("app", resourcePath))
            .firstOrNull { it.isFile }
            ?: error("Resource file not found: $resourcePath")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val element = nodes.item(index)
            if (element.attributes.getNamedItem("name")?.nodeValue == resourceName) {
                return element.textContent
            }
        }
        error("String resource not found: $resourceName in ${file.path}")
    }
}
