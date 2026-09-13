package com.gameocr.app.ocr

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PaddleColumnSplitWiringTest {
    private fun source(name: String): String {
        val path = "src/main/java/com/gameocr/app/ocr/$name.kt"
        return listOf(File(path), File("app/$path")).first { it.isFile }.readText()
    }
    @Test fun only_verified_paddle_recognition_calls_enable_splitting_before_ids_and_mask_analysis() {
        val s = source("PaddleOcrEngine")
        val detectionOnly = s.substringAfter("internal fun detectQuads(").substringBefore("): List<DBPostprocessor.Quad>")
        assertTrue(detectionOnly.contains("splitTextColumns: Boolean = false"))
        val plan = s.substringAfter("private fun detectQuadsForRecognition(").substringBefore("private fun recoverLargeTextQuads(")
        assertEquals(2, Regex("splitTextColumns = loadedVersion == PaddleModelVersion.V6_SMALL", RegexOption.LITERAL).findAll(plan).count())
        val run = s.substringAfter("private suspend fun runFull(").substringBefore("private fun detectQuadsForRecognition(")
        assertTrue(run.indexOf("detectQuadsForRecognition(") < run.indexOf("val sorted ="))
        assertTrue(run.indexOf("val sorted =") < run.indexOf("dumpMangaMaskDebugSet("))
        assertTrue(run.contains("PaddleRecognitionAcceptancePolicy.accepts(text, recognition.confidence)"))
        assertTrue(run.contains("memberDetectionIndices[memberIndex]"))
        assertTrue(run.indexOf("val acceptedIndices") < run.indexOf("shapeAwareSessionStore.manager.publish"))
        assertTrue(run.contains("input.copy(protectedSourceBounds = protected)"))
    }
    @Test fun background_and_whole_bubble_paths_protect_rejected_text_in_ocr_coordinates() {
        val s = source("MangaDelayedMaskDebugSession")
        assertEquals(2, Regex("protectSourceRegions(input.protectedSourceBounds)", RegexOption.LITERAL).findAll(s).count())
        val layout = s.substringAfter("private fun renderShapeAwareLayoutPreview(").substringBefore("private fun resolveShapeLayoutOrientation(")
        assertTrue(layout.contains("overlapsProtectedSource(input.protectedSourceBounds)"))
        assertTrue(layout.indexOf("REJECTED_OCR_SOURCE_PROTECTED") < layout.indexOf("ShapeAwareTextLayout.layout("))
    }
}
