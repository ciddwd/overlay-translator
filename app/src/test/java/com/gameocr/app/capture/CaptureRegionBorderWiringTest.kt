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
            Case("settings_capture_region_hide_on_capture", "Hide automatically during capture", "截屏时自动隐藏"),
            Case("settings_capture_region_move_enabled", "Allow capture region adjustment", "允许调整截屏区域"),
            Case("settings_capture_region_move_help", "Drag the center handle to move the region, or drag a corner to resize it.", "拖动中间图标可移动区域，拖动四角可调整大小。"),
            Case("capture_region_move_handle", "Move capture region", "移动截屏区域"),
            Case("capture_region_resize_handle", "Resize capture region", "调整截屏区域大小"),
            Case("settings_capture_region_style_customization", "Customize region style", "区域样式自定义"),
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
    fun settingsStyleCustomization_tableDriven_keepsCommonControlsVisibleAndCollapsesStyleControls() {
        val settings = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val section = slice(
            settings,
            "item(key = SectionKeys.CAPTURE_REGION)",
            "// —— 循环触发器 ——",
        )
        val collapsedStart = section.indexOf("if (captureRegionStyleExpanded)")
        require(collapsedStart >= 0) { "Missing capture-region style collapse boundary" }
        val collapsed = section.substring(collapsedStart)

        data class Case(val name: String, val marker: String, val expectedInCollapsedArea: Boolean)
        listOf(
            Case("border visibility remains immediately available", "settings_capture_region_border_enabled", false),
            Case("capture auto hide remains immediately available", "settings_capture_region_hide_on_capture", false),
            Case("region adjustment remains immediately available", "settings_capture_region_move_enabled", false),
            Case("border color is collapsed", "settings_capture_region_border_color", true),
            Case("border width is collapsed", "settings_capture_region_border_width_format", true),
            Case("border style is collapsed", "settings_floating_window_border_style_label", true),
        ).forEach { case ->
            assertEquals(case.name, case.expectedInCollapsedArea, collapsed.contains(case.marker))
        }
        assertTrue(section.contains("settings_capture_region_style_customization"))
        assertTrue(section.contains("captureRegionStyleExpanded = !captureRegionStyleExpanded"))
        assertTrue(settings.contains("entry.targetId in CAPTURE_REGION_STYLE_SEARCH_TARGET_RES_IDS"))
    }

    @Test
    fun serviceSmoke_routesAllCaptureHidingThroughSharedConfigurableOverlay() {
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
            Case("adjusted region persists with display size only", service, "settingsRepository.setCaptureRegion(region, screenWidth, screenHeight)"),
            Case("editor commit uses same partial writer", editor, "settingsRepository.setCaptureRegion(region, screen.width, screen.height)"),
            Case("failed commit still restores ball", editor, "finally {"),
            Case("reentrant opening is blocked", editor, "regionPickerJob?.isActive == true"),
        ).forEach { case -> assertTrue(case.name, case.source.contains(case.marker)) }
        assertFalse("editor must not read or rewrite full settings", editor.contains("settingsRepository.get()"))
        assertFalse("editor must not read or rewrite full settings", editor.contains("settingsRepository.update"))

        assertTrue(
            "border must hide before screenshot capture",
            capture.indexOf("setHiddenForCapture(hidden = true)") < capture.indexOf("captureScreenshotWithTiming(shotter, diagId)"),
        )
        assertTrue(
            "border must restore after screenshot capture",
            capture.indexOf("restoreCaptureRegionBorderAfterCapture()", capture.indexOf("captureScreenshotWithTiming(shotter, diagId)")) >
                capture.indexOf("captureScreenshotWithTiming(shotter, diagId)"),
        )
        val overlay = sourceFile("src/main/java/com/gameocr/app/overlay/CaptureRegionBorderOverlay.kt").readText()
        val settings = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        assertTrue("all backends honor same option", overlay.contains("autoHideOnCapture = settings.captureRegionHideOnCapture"))
        assertTrue("visibility uses the option", overlay.contains("autoHideOnCapture = autoHideOnCapture"))
        assertTrue("disabled option must not report a hidden window", overlay.contains("return wasVisible && borderView?.visibility != View.VISIBLE"))
        assertTrue("toggle saves immediately", settings.contains("viewModel.saveCaptureRegionHideOnCapture(enabled)"))
        assertTrue("draft included when saving/exporting", settings.contains("captureRegionHideOnCapture = captureRegionHideOnCapture,"))
    }

    @Test
    fun overlaySmoke_isTouchThroughWhenLockedAndRestrictsAdjustmentTouchesToHandles() {
        val overlay = sourceFile("src/main/java/com/gameocr/app/overlay/CaptureRegionBorderOverlay.kt").readText()
        val picker = sourceFile("src/main/java/com/gameocr/app/overlay/RegionPickerOverlay.kt").readText()
        val dragFrame = slice(overlay, "private fun updateDraggedRegion", "private fun resize")

        data class Case(val name: String, val marker: String)
        listOf(
            Case("application overlay", "WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY"),
            Case("not focusable", "WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE"),
            Case("touch through", "WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE"),
            Case("adjustment does not make the whole screen modal", "WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL"),
            Case("only small handles receive touches", "five small overlay windows"),
            Case("center handle moves", "CaptureRegionDragKind.MOVE -> movedCaptureRegion"),
            Case("corner handles resize", "CaptureRegionDragKind.BOTTOM_RIGHT -> resize"),
            Case("four direction icon", "drawFourDirectionArrow"),
            Case("Android maximum pass-through opacity", "const val MAX_TOUCH_THROUGH_ALPHA: Float = 0.8f"),
            Case("solid style", "CaptureRegionBorderStyle.SOLID -> null"),
            Case("dashed style", "CaptureRegionBorderStyle.DASHED -> DashPathEffect"),
            Case("dotted style", "CaptureRegionBorderStyle.DOTTED -> DashPathEffect"),
        ).forEach { case -> assertTrue(case.name, overlay.contains(case.marker)) }

        assertTrue("drag preview redraws the single border view", dragFrame.contains("updateAdjustmentPreview"))
        assertFalse("drag frame must not relayout handle windows", dragFrame.contains("updateViewLayout"))
        assertTrue("drag redraw is coalesced to display frames", overlay.contains("postInvalidateOnAnimation()"))
        assertTrue("handle windows sync only after adjustment", overlay.contains("syncHandlePositions(adjusted)"))

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
