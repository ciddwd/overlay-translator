package com.gameocr.app.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainPermissionGuideUiTest {
    @Test
    fun readyShizukuShowsFullControlsWithoutClaimingTheOverlayPermissionIsGranted() {
        val home = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val captureSection = home.substringAfter("capturePage = { pageModifier ->")
            .substringBefore("galleryPage = { pageModifier ->")
        listOf(
            "showCaptureStartControls(canDrawOverlay, serviceRunning, shizukuAvail)" to true,
            "canDrawOverlay = Settings.canDrawOverlays(context)" to true,
            "canDrawOverlay = true" to false,
        ).forEach { (marker, expected) -> assertEquals(marker, expected, home.contains(marker)) }
        listOf(
            "if (!showCaptureControls)" to true,
            "if (!canDrawOverlay)" to false,
            "mainUsageTextRes(showCaptureControls)" to true,
            "mainUsageTextRes(canDrawOverlay)" to false,
            "CaptureStartRequestActivity.newIntent(context)" to true,
            "if (serviceRunning)" to true,
        ).forEach { (marker, expected) -> assertEquals(marker, expected, captureSection.contains(marker)) }
        val statusArguments = home.substringAfter("StatusPresetCarousel(").substringBefore("CaptureGalleryCarousel(")
        assertTrue(statusArguments.contains("canDrawOverlay = canDrawOverlay"))
        assertFalse(statusArguments.contains("showCaptureControls"))
    }

    @Test
    fun homeRemovesOnlyTheTileGuideAndRetainsPermissionEntries() {
        val home = moduleFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        listOf(
            "R.string.quick_settings_tile_title" to false,
            "R.string.quick_settings_tile_description" to false,
            "R.string.quick_settings_tile_add" to false,
            "CaptureQuickSettingsTileService.requestAdd" to false,
            "R.string.main_section_rom_guide" to true,
            "R.string.main_btn_open_autostart" to true,
            "R.string.main_btn_open_battery_whitelist" to true,
            "R.string.settings_btn_open_a11y" to true,
            "R.string.main_hint_shizuku_ready" to true,
            "CaptureStartRequestActivity.newIntent(" to true,
        ).forEach { (marker, expected) ->
            assertEquals(marker, expected, home.contains(marker))
        }
        assertFalse(home.contains("import com.gameocr.app.service.CaptureQuickSettingsTileService"))
    }

    @Test
    fun chineseCopyUsesTheApprovedPermissionDescriptionAndKeepsTheTileLabel() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(moduleFile("src/main/res/values-zh-rCN/strings.xml"))
        val nodes = document.getElementsByTagName("string")
        val strings = (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        listOf(
            "main_hint_shizuku_ready" to "Shizuku 已就绪，可使用更高的系统权限。",
            "quick_settings_tile_label" to "屏译服务",
        ).forEach { (name, expected) ->
            assertEquals(name, expected, strings[name])
        }
        val tile = moduleFile("src/main/java/com/gameocr/app/service/CaptureQuickSettingsTileService.kt")
            .readText()
        listOf("R.string.quick_settings_tile_label", "override fun onClick()", "updateTileState")
            .forEach { marker -> assertTrue(marker, tile.contains(marker)) }
    }

    private fun moduleFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Module file not found: $path")
}
