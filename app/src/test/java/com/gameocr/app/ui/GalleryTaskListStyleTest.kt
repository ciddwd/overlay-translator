package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTaskListStyleTest {

    @Test
    fun `task result summary shows task settings and navigation owns terminal actions`() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val start = source.indexOf("private fun GalleryTaskSummary(")
        val end = source.indexOf("private fun GalleryTaskProgressSummary(", start)
        assertTrue("task result summary block exists", start >= 0 && end > start)
        val summary = source.substring(start, end)

        data class Case(
            val name: String,
            val marker: String,
            val expectedPresent: Boolean,
        )

        listOf(
            Case("delete is not inside the summary", "onDelete", false),
            Case("export is not inside the summary", "R.string.gallery_export_action", false),
            Case("summary uses the same date time title as the task list", "DateFormat.getDateTimeInstance(", true),
            Case("summary no longer titles itself with an image count", "R.string.gallery_task_images", false),
            Case("summary displays OCR translator and languages", "GalleryTaskSettingsSummary(task)", true),
        ).forEach { case ->
            assertEquals(case.name, case.expectedPresent, case.marker in summary)
        }

        assertTrue(
            "status follows the date time title in the top right group",
            summary.indexOf("GalleryStatusText(task.status)") >
                summary.indexOf("DateFormat.getDateTimeInstance("),
        )
        val settingsSummary = source.substring(
            source.indexOf("private fun GalleryTaskSettingsSummary("),
            source.indexOf("private fun GalleryTaskSummary("),
        )
        listOf(
            "OCR display label" to "ocrEngineLabelRes",
            "translator display label" to "translatorEngineLabelRes",
            "source language display label" to "Languages.nameOf(context, task.sourceLang)",
            "target language display label" to "Languages.nameOf(context, task.targetLang)",
        ).forEach { (name, marker) ->
            assertTrue(name, marker in settingsSummary)
        }
        val detailScreen = source.substring(
            source.indexOf("fun GalleryTranslationTaskDetailScreen("),
            source.indexOf("private fun GalleryTaskCard("),
        )
        assertTrue(
            "save as and delete share the top app bar actions dropdown",
            detailScreen.contains("Icons.Default.MoreVert") &&
                detailScreen.contains("R.string.gallery_task_more_actions") &&
                detailScreen.contains("DropdownMenu(") &&
                detailScreen.indexOf("R.string.gallery_export_action") >
                    detailScreen.indexOf("DropdownMenu(") &&
                detailScreen.indexOf(
                    "R.string.gallery_task_delete",
                    detailScreen.indexOf("DropdownMenu("),
                ) >
                    detailScreen.indexOf("DropdownMenu("),
        )
        assertTrue(
            "sticky tabs sit between the summary and the result items",
            detailScreen.indexOf("stickyHeader(key = \"gallery-result-tabs\")") >
                detailScreen.indexOf("GalleryTaskSummary(") &&
                detailScreen.indexOf("stickyHeader(key = \"gallery-result-tabs\")") <
                detailScreen.indexOf("items(filteredItems"),
        )
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
