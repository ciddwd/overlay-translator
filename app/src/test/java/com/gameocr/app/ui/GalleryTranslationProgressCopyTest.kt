package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTranslationProgressCopyTest {

    @Test
    fun `task list and result summary label progress and outcomes clearly`() {
        val screen = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val english = sourceFile("src/main/res/values/strings.xml").readText()
        val chinese = sourceFile("src/main/res/values-zh-rCN/strings.xml").readText()
        val component = screen.substring(
            screen.indexOf("private fun GalleryTaskProgressSummary("),
            screen.indexOf("private fun GalleryResultItem("),
        )

        data class Case(
            val name: String,
            val actual: Boolean,
        )

        listOf(
            Case(
                name = "task list keeps outcomes while detail summary moves them to tabs",
                actual = screen.contains("GalleryTaskProgressSummary(task)") &&
                    screen.contains(
                        "GalleryTaskProgressSummary(task, showOutcomes = false)"
                    ),
            ),
            Case(
                name = "progress label and value are separate",
                actual = component.contains("R.string.gallery_task_progress_label") &&
                    component.contains("R.string.gallery_task_progress_value"),
            ),
            Case(
                name = "task list and result outcomes match the simple main summary row",
                actual = component.contains("R.string.gallery_task_success_count") &&
                    component.contains("R.string.gallery_task_failed_count") &&
                    component.contains("horizontalArrangement = Arrangement.SpaceBetween") &&
                    component.contains("color = MaterialTheme.colorScheme.primary") &&
                    component.contains("MaterialTheme.colorScheme.error") &&
                    !component.contains("GalleryTaskOutcomeBadge(") &&
                    !component.contains("Modifier.weight(1f)"),
            ),
            Case(
                name = "task result outcomes live in a sticky tab row",
                actual = screen.contains("stickyHeader(key = \"gallery-result-tabs\")") &&
                    screen.contains("SecondaryTabRow(") &&
                    screen.contains("GalleryResultFilter.SUCCEEDED") &&
                    screen.contains("GalleryResultFilter.FAILED"),
            ),
            Case(
                name = "active tasks keep a progress bar between the rows",
                actual = component.contains("LinearProgressIndicator("),
            ),
            Case(
                name = "English resources use split labels",
                actual = english.contains(
                    """<string name="gallery_task_progress_label">Progress</string>"""
                ) && english.contains(
                    """<string name="gallery_task_success_count">Succeeded %1${'$'}d</string>"""
                ),
            ),
            Case(
                name = "English main action identifies the destination as a task list",
                actual = english.contains(
                    """<string name="gallery_main_all_tasks">Task list</string>"""
                ),
            ),
            Case(
                name = "Chinese resources use the concise progress heading",
                actual = chinese.contains(
                    """<string name="gallery_task_progress_label">进度</string>"""
                ) && chinese.contains(
                    """<string name="gallery_task_failed_count">失败 %1${'$'}d</string>"""
                ),
            ),
            Case(
                name = "Chinese main action identifies the destination as a task list",
                actual = chinese.contains(
                    """<string name="gallery_main_all_tasks">任务列表</string>"""
                ),
            ),
            Case(
                name = "Chinese main title is concise",
                actual = chinese.contains(
                    """<string name="gallery_main_title">批量翻译</string>"""
                ),
            ),
            Case(
                name = "English primary action creates a task",
                actual = english.contains(
                    """<string name="gallery_main_import">New task</string>"""
                ),
            ),
            Case(
                name = "Chinese primary action creates a task",
                actual = chinese.contains(
                    "<string name=\"gallery_main_import\">" +
                        "\u65b0\u5efa\u4efb\u52a1</string>"
                ),
            ),
            Case(
                name = "English task list title is concise",
                actual = english.contains(
                    """<string name="gallery_tasks_title">Translation tasks</string>"""
                ),
            ),
            Case(
                name = "Chinese task list title is concise",
                actual = chinese.contains(
                    "<string name=\"gallery_tasks_title\">" +
                        "\u7ffb\u8bd1\u4efb\u52a1</string>"
                ),
            ),
        ).forEach { case ->
            assertEquals(case.name, true, case.actual)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
