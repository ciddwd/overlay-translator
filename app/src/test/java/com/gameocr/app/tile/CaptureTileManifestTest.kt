package com.gameocr.app.tile

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 磁贴声明上不了真机就验证不了，但每一条声明漏掉都是静默失效，所以逐条断言。可信度前置见
 * `app/build.gradle.kts` 的源码输入声明：AndroidManifest.xml 不在单测 classpath 上，缺了它本测试
 * 会复用陈旧结果、用绿灯冒充通过，摘除那条声明即等于让本文件失去意义。
 */
class CaptureTileManifestTest {

    @Test
    fun tileService_isDeclaredWithSystemBindPermission() {
        val service = tileServiceNode()

        assertEquals("exported", "true", service.getAttribute("android:exported"))
        assertEquals(
            "bind permission",
            "android.permission.BIND_QUICK_SETTINGS_TILE",
            service.getAttribute("android:permission"),
        )
        assertEquals("label", "@string/tile_capture_label", service.getAttribute("android:label"))
        assertEquals("icon", "@drawable/ic_tile_capture", service.getAttribute("android:icon"))
    }

    @Test
    fun tileService_declaresQuickSettingsTileAction() {
        val actions = tileServiceNode().getElementsByTagName("action")
        val declared = (0 until actions.length).map { actions.item(it) as Element }
            .map { it.getAttribute("android:name") }

        assertTrue(
            "missing the QS_TILE action, so the system would not treat this as a tile: $declared",
            declared.contains("android.service.quicksettings.action.QS_TILE"),
        )
    }

    @Test
    fun tileService_declaresActiveTileMetadata() {
        val metadata = tileServiceNode().getElementsByTagName("meta-data")
        val pairs = (0 until metadata.length)
            .map { metadata.item(it) as Element }
            .map { it.getAttribute("android:name") to it.getAttribute("android:value") }

        assertTrue(
            "missing ACTIVE_TILE=true, which makes requestListeningState a silent no-op: $pairs",
            pairs.contains("android.service.quicksettings.ACTIVE_TILE" to "true"),
        )
    }

    @Test
    fun tileService_declaresToggleableTileMetadata() {
        val metadata = tileServiceNode().getElementsByTagName("meta-data")
        val pairs = (0 until metadata.length)
            .map { metadata.item(it) as Element }
            .map { it.getAttribute("android:name") to it.getAttribute("android:value") }

        assertTrue(
            "missing TOGGLEABLE_TILE=true, so the panel would not render the on/off states that " +
                "this tile actually has: $pairs",
            pairs.contains("android.service.quicksettings.TOGGLEABLE_TILE" to "true"),
        )
    }

    @Test
    fun tileService_manifestNameMatchesTheRealClassName() {
        // The manifest uses a relative name resolved against the namespace, so compare the resolved form.
        val namespace = namespaceFromGradle()
        val declaredName = tileServiceNode().getAttribute("android:name")
        val resolved = if (declaredName.startsWith(".")) namespace + declaredName else declaredName

        assertEquals(CaptureTileService::class.java.name, resolved)
    }

    private fun namespaceFromGradle(): String {
        val gradle = sourceFile("build.gradle.kts").readText()
        return Regex("""^\s*namespace\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(gradle)
            ?.groupValues
            ?.get(1)
            ?: error("namespace not found in app/build.gradle.kts")
    }

    private fun tileServiceNode(): Element {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(sourceFile("src/main/AndroidManifest.xml"))
        val services = document.getElementsByTagName("service")
        return (0 until services.length)
            .map { services.item(it) as Element }
            .firstOrNull {
                it.getAttribute("android:name").endsWith("CaptureTileService")
            }
            ?: error("tile service declaration not found in AndroidManifest.xml")
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
