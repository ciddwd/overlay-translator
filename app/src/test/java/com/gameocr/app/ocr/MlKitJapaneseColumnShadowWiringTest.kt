package com.gameocr.app.ocr

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitJapaneseColumnShadowWiringTest {

    @Test
    fun shadowRecognition_isDebugOnlyAndReplacesOnlyAcceptedSourceLines_tableDriven() {
        val source = source("app/src/main/java/com/gameocr/app/ocr/MlKitOcrEngine.kt")

        data class Case(val name: String, val marker: String)
        listOf(
            Case("shadow path is guarded by debug build", "if (BuildConfig.DEBUG)"),
            Case("symbol geometry is collected during first pass", "japaneseGlyphCollector = glyphs"),
            Case("geometry conflict policy gates extra recognition", "if (!columnPlan.shouldRunShadowRecognition) return"),
            Case("every detected region receives an upright attempt", "regions.forEachIndexed"),
            Case("symbol runs expand to complete line regions", "MlKitJapaneseRegionPolicy.expand"),
            Case("rotation attempts are selected by structural fallback policy", "MlKitJapaneseRotationFallbackPolicy.next"),
            Case("rotated output returns to upright reading coordinates", "MlKitJapaneseShadowReadingPolicy.orderVerticalRtl"),
            Case("debug output records restored geometry", "orderedShadow.debugGeometry()"),
            Case("selection combines structure completeness and confidence", "MlKitJapaneseShadowSelectionPolicy.select"),
            Case("debug images are saved to an explicit cache folder", "mlkit_japanese_shadow"),
            Case("accepted debug regions replace their own source lines", "MlKitJapaneseShadowRepairPolicy.apply(firstPass, repairs)"),
            Case("release path keeps first pass", "else {\n                firstPass"),
            Case("repaired boxes return to input coordinates", "visibleResult.map { block -> block.mapRects"),
        ).forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }

        assertFalse("selection failure must keep original lines", source.contains("selection.selected!!"))
        assertFalse("local regions must not be truncated to a fixed count", source.contains("MAX_JAPANESE_SHADOW_REGIONS"))
    }

    private fun source(path: String): String = listOf(
        File("../$path"),
        File(path),
    ).firstOrNull(File::isFile)?.readText() ?: error("Source not found: $path")
}
