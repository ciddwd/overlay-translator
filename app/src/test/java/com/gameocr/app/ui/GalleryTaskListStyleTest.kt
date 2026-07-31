package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTaskListStyleTest {

    @Test
    fun `task list cards match the outlined main screen card style`() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val start = source.indexOf("private fun GalleryTaskCard(")
        val end = source.indexOf("private fun GalleryTaskSummary(", start)
        assertTrue("task list card block exists", start >= 0 && end > start)
        val taskCard = source.substring(start, end)

        data class Case(
            val name: String,
            val marker: String,
            val expectedPresent: Boolean,
        )

        listOf(
            Case(
                "task card keeps a clickable Card",
                "Card(",
                true,
            ),
            Case(
                "task card uses the theme surface background",
                "containerColor = MaterialTheme.colorScheme.surface",
                true,
            ),
            Case(
                "task card uses the matching surface content color",
                "contentColor = MaterialTheme.colorScheme.onSurface",
                true,
            ),
            Case(
                "task card uses the standard outline",
                "border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)",
                true,
            ),
            Case(
                "task card has no elevation",
                "elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)",
                true,
            ),
            Case(
                "task card no longer uses a transparent background",
                "containerColor = Color.Transparent",
                false,
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expectedPresent, case.marker in taskCard)
        }
    }

    @Test
    fun `task result summary is outlined and navigation owns terminal actions`() {
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
            Case(
                "summary uses the white theme surface",
                "containerColor = MaterialTheme.colorScheme.surface",
                true,
            ),
            Case(
                "summary uses the matching content color",
                "contentColor = MaterialTheme.colorScheme.onSurface",
                true,
            ),
            Case(
                "summary has a one dp outline",
                "border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)",
                true,
            ),
            Case(
                "summary has no elevation",
                "elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)",
                true,
            ),
            Case(
                "delete is not inside the summary",
                "onDelete",
                false,
            ),
            Case(
                "export is not inside the summary",
                "R.string.gallery_export_action",
                false,
            ),
            Case(
                "summary uses the same date time title as the task list",
                "DateFormat.getDateTimeInstance(",
                true,
            ),
            Case(
                "summary no longer titles itself with an image count",
                "R.string.gallery_task_images",
                false,
            ),
            Case(
                "summary title aligns with the top status line",
                "verticalAlignment = Alignment.Top",
                true,
            ),
            Case(
                "summary displays OCR translator and languages",
                "GalleryTaskSettingsSummary(task)",
                true,
            ),
            Case(
                "summary no longer uses a filled container",
                "containerColor = MaterialTheme.colorScheme.surfaceContainer",
                false,
            ),
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
    }

    @Test
    fun `task result tabs are edge to edge and result items are outlined cards`() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val detail = source.substring(
            source.indexOf("fun GalleryTranslationTaskDetailScreen("),
            source.indexOf("private fun GalleryTaskCard("),
        )
        val resultItem = source.substring(
            source.indexOf("private fun GalleryResultItem("),
            source.indexOf("private fun GalleryResultTextSection("),
        )

        data class Case(
            val name: String,
            val actual: Boolean,
        )

        listOf(
            Case(
                "list content padding is vertical only",
                detail.contains(
                    "PaddingValues(vertical = 16.dp)"
                ),
            ),
            Case(
                "summary and result cards own the horizontal inset",
                Regex("""\.padding\(horizontal = 16\.dp\)""")
                    .findAll(detail)
                    .count() == 2,
            ),
            Case(
                "sticky tabs remain outside card insets",
                detail.indexOf("stickyHeader(key = \"gallery-result-tabs\")") >
                    detail.indexOf("GalleryTaskSummary(") &&
                    detail.indexOf("stickyHeader(key = \"gallery-result-tabs\")") <
                    detail.indexOf("items(filteredItems"),
            ),
            Case(
                "result item uses the white theme surface",
                resultItem.contains(
                    "containerColor = MaterialTheme.colorScheme.surface"
                ),
            ),
            Case(
                "result item uses a neutral one dp border",
                resultItem.contains(
                    "border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)"
                ),
            ),
            Case(
                "result item has no elevation",
                resultItem.contains(
                    "elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)"
                ),
            ),
            Case(
                "result item no longer uses a filled container",
                !resultItem.contains("surfaceContainerLow"),
            ),
        ).forEach { case ->
            assertTrue(case.name, case.actual)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
