package com.gameocr.app.tile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 磁贴图标是 Manifest 里的一个字符串引用，删掉或改尺寸都由编译器放行、真机上才看得出来，所以锁住它。
 * 可信度前置同 [CaptureTileManifestTest]：res 下的改动不在单测依赖图上，缺了源码输入声明即假绿。
 */
class CaptureTileIconTest {

    @Test
    fun theIconTheManifestReferencesExists() {
        val icon = tileServiceIcon()
        val name = icon.removePrefix("@drawable/")
        val file = sourceFile("src/main/res/drawable/$name.xml")

        assertEquals(
            "the manifest icon must point at a drawable that is actually in res/drawable",
            true,
            file.isFile,
        )
    }

    @Test
    fun theIconIsTheTilesOwn24dpVariant() {
        // 24dp 是本文件存在的**唯一理由**：主球图标 ic_overlay_button 是 48dp，当成磁贴图标会偏大。
        // 若哪天有人把这份尺寸改成 48dp，二者就完全等价了，应当合并成一个文件。
        val vector = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(sourceFile("src/main/res/drawable/ic_tile_capture.xml")).documentElement

        assertEquals("the drawable must be a <vector>", "vector", vector.tagName)
        assertEquals("android:width", "24dp", vector.getAttribute("android:width"))
        assertEquals("android:height", "24dp", vector.getAttribute("android:height"))
    }

    @Test
    fun theIconActuallyDrawsSomething() {
        val paths = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(sourceFile("src/main/res/drawable/ic_tile_capture.xml"))
            .getElementsByTagName("path")

        assertTrue(
            "an icon with no <path> renders as a blank tile slot",
            paths.length >= 1,
        )
        assertEquals(
            "the path must carry actual geometry, not an empty placeholder",
            false,
            (paths.item(0) as Element).getAttribute("android:pathData").isBlank(),
        )
    }

    private fun tileServiceIcon(): String {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(sourceFile("src/main/AndroidManifest.xml"))
        val services = document.getElementsByTagName("service")
        val tile = (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull { it.getAttribute("android:name").endsWith("CaptureTileService") }
            ?: error("tile service declaration not found in AndroidManifest.xml")
        return tile.getAttribute("android:icon")
    }

    // Paths are relative to the module root; both candidates cover the repo-root and app/ working directories.
    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: File(path)
}
