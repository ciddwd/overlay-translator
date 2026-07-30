package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTaskConfirmationTest {

    @Test
    fun `create and cancel actions require catalyst confirmation`() {
        val screen = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val english = sourceFile("src/main/res/values/strings.xml").readText()
        val chinese = sourceFile("src/main/res/values-zh-rCN/strings.xml").readText()
        val createScreen = screen.substring(
            screen.indexOf("fun GalleryTranslationConfirmScreen("),
            screen.indexOf("private fun GalleryPresetSwitcher("),
        )
        val detailScreen = screen.substring(
            screen.indexOf("fun GalleryTranslationTaskDetailScreen("),
            screen.indexOf("private fun GalleryTaskCard("),
        )

        data class Case(
            val name: String,
            val actual: Boolean,
        )

        listOf(
            Case(
                "create button only opens confirmation",
                createScreen.contains("onClick = { showCreateDialog = true }"),
            ),
            Case(
                "create confirmation uses catalyst dialog",
                createScreen.contains("if (showCreateDialog)") &&
                    createScreen.substring(
                        createScreen.indexOf("if (showCreateDialog)"),
                        createScreen.indexOf("Scaffold("),
                    ).contains("CatalystAlertDialog("),
            ),
            Case(
                "task is created only by the confirm action",
                createScreen.indexOf("viewModel.createAndEnqueue(selectedUris)") <
                    createScreen.indexOf("Scaffold("),
            ),
            Case(
                "create message includes selected image count",
                createScreen.contains("selectedUris.size"),
            ),
            Case(
                "cancel button only opens confirmation",
                detailScreen.contains("onCancel = { showCancelDialog = true }"),
            ),
            Case(
                "cancel confirmation uses catalyst dialog",
                detailScreen.contains("if (showCancelDialog)") &&
                    detailScreen.contains("R.string.gallery_task_cancel_title"),
            ),
            Case(
                "task cancellation occurs only in confirm action",
                detailScreen.contains("scope.launch { viewModel.cancel(taskId) }"),
            ),
            Case(
                "both locales contain all confirmation copy",
                listOf(
                    "gallery_confirm_create_dialog_title",
                    "gallery_confirm_create_dialog_confirm",
                    "gallery_confirm_create_dialog_message",
                    "gallery_task_cancel_title",
                    "gallery_task_cancel_message",
                ).all { key ->
                    """<string name="$key">""" in english &&
                        """<string name="$key">""" in chinese
                },
            ),
            Case(
                "create dialog uses its own confirm copy",
                createScreen.contains(
                    "Text(stringResource(R.string.gallery_confirm_create_dialog_confirm))"
                ) &&
                    createScreen.contains(
                        "Text(stringResource(R.string.gallery_confirm_create))"
                    ),
            ),
            Case(
                "simplified Chinese create dialog copy matches the approved wording",
                chinese.contains(
                    """<string name="gallery_confirm_create_dialog_title">提示</string>"""
                ) &&
                    chinese.contains(
                        """<string name="gallery_confirm_create_dialog_confirm">确定</string>"""
                    ),
            ),
        ).forEach { case ->
            assertTrue(case.name, case.actual)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
