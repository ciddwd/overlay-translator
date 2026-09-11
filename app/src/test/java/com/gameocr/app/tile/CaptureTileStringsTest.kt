package com.gameocr.app.tile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两种语言的 values 目录是手写的两份，漏一份在真机上很难注意到，所以在这里钉死。可信度前置同
 * [CaptureTileManifestTest]：strings.xml 不在单测 classpath 上，缺了源码输入声明它就不会触发重跑。
 */
class CaptureTileStringsTest {

    private val english = "src/main/res/values/strings.xml"
    private val chinese = "src/main/res/values-zh-rCN/strings.xml"

    /** The system truncates the tile label, so it has to stay short. */
    private val maxLabelLength = 24

    @Test
    fun everyTileStringExistsInBothLocales() {
        val names = listOf(
            "tile_capture_label",
            "tile_toast_overlay_required",
            "tile_toast_unlock_first",
        )
        listOf(english, chinese).forEach { path ->
            names.forEach { name ->
                val value = stringValue(path, name)
                assertTrue("$name must not be blank in $path", value.isNotBlank())
            }
        }
    }

    @Test
    fun tileLabel_matchesTheAppNameAndStaysShort() {
        val en = stringValue(english, "tile_capture_label")
        val zh = stringValue(chinese, "tile_capture_label")

        assertEquals("Screen Translator", en)
        assertEquals("屏译", zh)
        listOf(en, zh).forEach { label ->
            assertTrue(
                "tile label '$label' exceeds $maxLabelLength chars and would be truncated",
                label.length <= maxLabelLength,
            )
        }
    }

    @Test
    fun toasts_tableDriven_sayWhatToDoNotJustWhatFailed() {
        data class Case(val path: String, val name: String, val mustContain: List<String>)

        listOf(
            Case(english, "tile_toast_overlay_required", listOf("Display over other apps")),
            Case(chinese, "tile_toast_overlay_required", listOf("显示在其他应用上层")),
            Case(english, "tile_toast_unlock_first", listOf("Unlock")),
            Case(chinese, "tile_toast_unlock_first", listOf("解锁")),
        ).forEach { case ->
            val value = stringValue(case.path, case.name)
            case.mustContain.forEach { fragment ->
                assertTrue(
                    "$fragment missing from ${case.name} in ${case.path}: $value",
                    value.contains(fragment),
                )
            }
        }
    }

    @Test
    fun neitherLocaleKeepsATodoPlaceholder() {
        listOf(english, chinese).forEach { path ->
            listOf("tile_capture_label", "tile_toast_overlay_required", "tile_toast_unlock_first")
                .forEach { name ->
                    val value = stringValue(path, name)
                    assertFalse("$name is still a placeholder in $path: $value", value.contains("TODO", true))
                }
        }
    }

    private fun stringValue(resourcePath: String, resourceName: String): String {
        val file = listOf(File(resourcePath), File("app", resourcePath))
            .firstOrNull(File::isFile)
            ?: error("Resource file not found: $resourcePath")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.attributes?.getNamedItem("name")?.nodeValue == resourceName) {
                return node.textContent ?: ""
            }
        }
        error("$resourcePath is missing the string $resourceName")
    }
}
