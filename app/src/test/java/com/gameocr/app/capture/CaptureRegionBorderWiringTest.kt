package com.gameocr.app.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRegionBorderWiringTest {

    @Test
    fun approvedCopy_tableDriven_matchesEnglishAndChineseResources() {
        data class Case(val key: String, val english: String, val chinese: String)

        val english = sourceFile("src/main/res/values/strings.xml").readText()
        val chinese = sourceFile("src/main/res/values-zh-rCN/strings.xml").readText()
        listOf(
            Case("settings_section_capture_region", "Capture region", "截屏区域"),
            Case("settings_capture_region_border_enabled", "Show capture region border", "显示截屏区域边框"),
            Case("settings_capture_region_border_color", "Border color", "边框颜色"),
            Case("settings_capture_region_border_width_format", "Border width: %1\$d dp", "边框粗细：%1\$d dp"),
            Case("settings_floating_window_border_style_label", "Border style", "边框样式"),
            Case("settings_border_style_solid", "Solid", "实线"),
            Case("settings_border_style_dashed", "Dashed", "虚线"),
            Case("settings_border_style_dotted", "Dotted", "点线"),
        ).forEach { case ->
            assertEquals(case.key, case.english, stringValue(english, case.key))
            assertEquals(case.key, case.chinese, stringValue(chinese, case.key))
        }

        assertFalse(chinese.contains("仅在已设置截屏区域时显示；截图时边框会自动隐藏"))
    }

    @Test
    fun serviceSmoke_hidesBorderForEveryCaptureAndForRegionEditor() {
        val service = sourceFile("src/main/java/com/gameocr/app/service/CaptureService.kt").readText()
        val prepare = slice(service, "private suspend fun prepareCleanCaptureFrame", "private fun restoreCaptureChrome")
        val restore = slice(service, "private fun restoreCaptureChrome", "private fun startForegroundCompat")
        val editor = slice(service, "private fun showRegionPickerOverlay", "private fun shortError")
        val capture = slice(service, "private suspend fun captureOnce", "private fun cropIfNeeded")

        data class Case(val name: String, val source: String, val marker: String)
        listOf(
            Case("manual capture hides border", prepare, "setHiddenForCapture(hidden = true)"),
            Case("capture chrome restores border", restore, "setHiddenForCapture(hidden = false)"),
            Case("direct loop capture hides border", capture, "setHiddenForCapture(hidden = true)"),
            Case("capture finally restores border", capture, "restoreCaptureRegionBorderAfterCapture()"),
            Case("region editor hides status border", editor, "setHiddenForEditor(hidden = true)"),
            Case("region editor restores status border", editor, "setHiddenForEditor(hidden = false)"),
            Case("settings updates live", service, "captureRegionBorder?.applySettings(settings)"),
        ).forEach { case -> assertTrue(case.name, case.source.contains(case.marker)) }

        assertTrue(
            "border must hide before screenshot capture",
            capture.indexOf("setHiddenForCapture(hidden = true)") < capture.indexOf("shotter.capture()"),
        )
        assertTrue(
            "border must restore after screenshot capture",
            capture.indexOf("restoreCaptureRegionBorderAfterCapture()", capture.indexOf("shotter.capture()")) >
                capture.indexOf("shotter.capture()"),
        )
    }

    @Test
    fun overlaySmoke_isTouchThroughAndSupportsApprovedStyles() {
        val overlay = sourceFile("src/main/java/com/gameocr/app/overlay/CaptureRegionBorderOverlay.kt").readText()
        val picker = sourceFile("src/main/java/com/gameocr/app/overlay/RegionPickerOverlay.kt").readText()

        data class Case(val name: String, val marker: String)
        listOf(
            Case("application overlay", "WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"),
            Case("not focusable", "WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"),
            Case("touch through", "WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"),
            Case("Android maximum pass-through opacity", "const val MAX_TOUCH_THROUGH_ALPHA: Float = 0.8f"),
            Case("solid style", "CaptureRegionBorderStyle.SOLID -> null"),
            Case("dashed style", "CaptureRegionBorderStyle.DASHED -> DashPathEffect"),
            Case("dotted style", "CaptureRegionBorderStyle.DOTTED -> DashPathEffect"),
        ).forEach { case -> assertTrue(case.name, overlay.contains(case.marker)) }

        assertFalse(
            "custom status-border styling must not leak into the region editor",
            picker.contains("CaptureRegionBorderStyle"),
        )
    }

    private fun stringValue(source: String, key: String): String =
        Regex("""<string name="${Regex.escape(key)}">([^<]*)</string>""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?: error("Missing string resource: $key")

    private fun slice(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        require(startIndex >= 0 && endIndex > startIndex) { "Missing slice: $start .. $end" }
        return source.substring(startIndex, endIndex)
    }

    private fun sourceFile(path: String): File = listOf(File(path), File("app", path))
        .firstOrNull(File::isFile)
        ?: error("Source file not found: $path")
}
