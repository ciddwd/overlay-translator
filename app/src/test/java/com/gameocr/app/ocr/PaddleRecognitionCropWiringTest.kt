package com.gameocr.app.ocr

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PaddleRecognitionCropWiringTest {
    @Test fun refinement_reuses_pixels_preserves_ids_and_does_not_change_detection_or_render_boxes() {
        val path = "src/main/java/com/gameocr/app/ocr/PaddleOcrEngine.kt"
        val source = listOf(File(path), File("app/$path")).first { it.isFile }.readText()
        val body = source.substringAfter("private fun refineKoreanRecognitionCrops(").substringBefore("private fun prepareRecognitionCrop(")
        assertTrue(body.contains("loadedVersion != PaddleModelVersion.V5_KOREAN"))
        assertTrue(body.contains("PaddleRecognitionCropPolicy.shouldRefine(original.score)"))
        assertTrue(body.contains("val support = quad.support ?: continue"))
        assertTrue(body.contains("PaddleRecognitionCropPolicy.Budget()"))
        assertTrue(body.contains("if (accepted) results[item.boxIndex] = candidate"))
        assertTrue(body.contains("candidate.text.isNotBlank()"))
        assertTrue(body.contains("catch (cancelled: kotlinx.coroutines.CancellationException)"))
        assertTrue(body.contains("candidateCrop?.let"))
        listOf("detectQuads(", "sorted[", "sourceBoxes =", "boundingBox =", "shapeAwareSessionStore")
            .forEach { assertFalse(it, body.contains(it)) }
        assertEquals(1, "refineKoreanRecognitionCrops(prepared,".toRegex(RegexOption.LITERAL).findAll(source).count())
    }
}
