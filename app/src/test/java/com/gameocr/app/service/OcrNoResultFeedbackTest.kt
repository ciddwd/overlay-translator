package com.gameocr.app.service

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrNoResultFeedbackTest {

    @Test
    fun emptyOcrResult_keepsLogAndShowsTheUserVisibleErrorHint() {
        val source = sourceFile().readText()
        val branchStart = source.indexOf("if (blocks.isEmpty())")
        val branchEnd = source.indexOf("val qualityIssue =", branchStart)
        check(branchStart >= 0 && branchEnd > branchStart) { "empty OCR branch not found" }
        val branch = source.substring(branchStart, branchEnd)

        data class Case(val name: String, val marker: String)
        val required = listOf(
            Case("diagnostic log remains", "R.string.log_msg_ocr_no_result_format"),
            Case("same actionable message is used", "R.string.toast_ocr_unreliable_result"),
            Case("message is shown in the overlay feedback bar", "overlay?.showErrorHint(message)"),
        )

        required.forEach { case -> assertTrue(case.name, branch.contains(case.marker)) }
    }

    private fun sourceFile(): File =
        listOf(
            File("src/main/java/com/gameocr/app/service/CaptureService.kt"),
            File("app/src/main/java/com/gameocr/app/service/CaptureService.kt"),
        ).firstOrNull(File::isFile) ?: error("CaptureService.kt not found")
}
