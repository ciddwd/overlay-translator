package com.gameocr.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class LoopSettingsCopyTest {

    @Test
    fun loopOptionLabels_doNotMarkChoicesAsRecommended() {
        data class Case(
            val name: String,
            val resourcePath: String,
            val expectedSmartLabel: String,
            val expectedAutoRegionLabel: String,
        )

        val cases = listOf(
            Case("English", "src/main/res/values/strings.xml", "Wait for text completion", "Auto"),
            Case("Simplified Chinese", "src/main/res/values-zh-rCN/strings.xml", "智能等待文字完成", "自动"),
        )

        cases.forEach { case ->
            val resources = readStringResources(case.resourcePath)
            assertEquals(case.name, case.expectedSmartLabel, resources["settings_loop_trigger_smart"])
            assertEquals(case.name, case.expectedAutoRegionLabel, resources["settings_loop_text_region_auto"])
        }
    }

    private fun readStringResources(relativePath: String): Map<String, String> {
        val file = listOf(File(relativePath), File("app/$relativePath"))
            .firstOrNull(File::isFile) ?: error("Resource file not found: $relativePath")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                put(name, node.textContent)
            }
        }
    }
}
