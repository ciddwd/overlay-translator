package com.gameocr.app.ui

import com.gameocr.app.gallery.GalleryTaskStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainGalleryTaskSummaryTest {

    @Test
    fun `task slot defaults to placeholder until availability is known`() {
        data class Case(
            val name: String,
            val canDrawOverlay: Boolean,
            val isLoaded: Boolean,
            val hasTask: Boolean,
            val expected: MainGalleryTaskSlot,
        )

        listOf(
            Case(
                "permission granted while loading",
                true,
                false,
                false,
                MainGalleryTaskSlot.PLACEHOLDER,
            ),
            Case(
                "permission granted with loaded task",
                true,
                true,
                true,
                MainGalleryTaskSlot.TASK,
            ),
            Case(
                "permission granted with confirmed empty result",
                true,
                true,
                false,
                MainGalleryTaskSlot.EMPTY,
            ),
            Case(
                "permission missing while loading",
                false,
                false,
                false,
                MainGalleryTaskSlot.HIDDEN,
            ),
            Case(
                "permission missing with loaded task",
                false,
                true,
                true,
                MainGalleryTaskSlot.HIDDEN,
            ),
            Case(
                "permission missing with confirmed empty result",
                false,
                true,
                false,
                MainGalleryTaskSlot.HIDDEN,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                mainGalleryTaskSlot(
                    canDrawOverlay = case.canDrawOverlay,
                    isLoaded = case.isLoaded,
                    hasTask = case.hasTask,
                ),
            )
        }
    }

    @Test
    fun `active task classification covers every status`() {
        data class Case(
            val status: GalleryTaskStatus,
            val expected: Boolean,
        )

        listOf(
            Case(GalleryTaskStatus.QUEUED, true),
            Case(GalleryTaskStatus.RUNNING, true),
            Case(GalleryTaskStatus.WAITING_RETRY, true),
            Case(GalleryTaskStatus.PARTIAL, false),
            Case(GalleryTaskStatus.SUCCEEDED, false),
            Case(GalleryTaskStatus.FAILED, false),
            Case(GalleryTaskStatus.CANCELED, false),
        ).forEach { case ->
            assertEquals(
                case.status.name,
                case.expected,
                isMainGalleryTaskActive(case.status),
            )
        }
    }

    @Test
    fun `task progress is safe and clamped`() {
        data class Case(
            val name: String,
            val completed: Int,
            val total: Int,
            val expected: Float,
        )

        listOf(
            Case("empty task", 0, 0, 0f),
            Case("half complete", 2, 4, 0.5f),
            Case("complete", 4, 4, 1f),
            Case("over complete clamps", 5, 4, 1f),
            Case("negative completed clamps", -1, 4, 0f),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                mainGalleryTaskProgress(case.completed, case.total),
                0.0001f,
            )
        }
    }

    @Test
    fun `task preset label covers built in custom and unsaved cases`() {
        data class Case(
            val name: String,
            val storedName: String,
            val expected: String,
        )

        listOf(
            Case(
                "blank task snapshot is unsaved",
                "",
                "未保存预设",
            ),
            Case(
                "whitespace task snapshot is unsaved",
                "   ",
                "未保存预设",
            ),
            Case(
                "legacy built in name is localized",
                "Offline Manga OCR to Chinese",
                "日漫离线翻译（日→中）",
            ),
            Case(
                "custom preset keeps its name",
                "我的游戏预设",
                "我的游戏预设",
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                mainGalleryTaskPresetLabel(
                    storedName = case.storedName,
                    builtInStoredName = "Offline Manga OCR to Chinese",
                    builtInDisplayName = "日漫离线翻译（日→中）",
                    unsavedDisplayName = "未保存预设",
                ),
            )
        }
    }

    @Test
    fun `featured query prioritizes active work and summary is permission gated`() {
        val database = sourceFile(
            "src/main/java/com/gameocr/app/gallery/GalleryTranslationDatabase.kt"
        ).readText()
        val screen = sourceFile("src/main/java/com/gameocr/app/ui/MainScreen.kt").readText()
        val activity = sourceFile("src/main/java/com/gameocr/app/ui/MainActivity.kt").readText()

        data class Case(
            val name: String,
            val actual: Boolean,
        )

        listOf(
            Case(
                "active statuses are prioritized by the query",
                database.contains(
                    "status IN ('QUEUED', 'RUNNING', 'WAITING_RETRY')"
                ),
            ),
            Case(
                "recent creation time breaks ties",
                database.contains("createdAtMs DESC LIMIT 1"),
            ),
            Case(
                "summary checks overlay permission",
                screen.contains("canDrawOverlay = canDrawOverlay"),
            ),
            Case(
                "database result starts in loading state",
                screen.contains(
                    "initialValue = MainGalleryTaskLoadState.Loading"
                ),
            ),
            Case(
                "loading state renders a real layout placeholder",
                screen.contains(
                    "MainGalleryTaskSlot.PLACEHOLDER -> MainGalleryTaskSummaryPlaceholder()"
                ),
            ),
            Case(
                "confirmed empty state renders a visible history placeholder",
                screen.contains(
                    "MainGalleryTaskSlot.EMPTY -> MainGalleryTaskEmptyPlaceholder()"
                ) && screen.contains("R.string.gallery_main_no_history_task"),
            ),
            Case(
                "placeholder reuses summary content without accessibility ghosts",
                screen.contains("MainGalleryTaskSummaryContent(task = null)") &&
                    screen.contains(".clearAndSetSemantics {}"),
            ),
            Case(
                "empty placeholder preserves the summary footprint",
                screen.substring(
                    screen.indexOf("private fun MainGalleryTaskEmptyPlaceholder("),
                    screen.indexOf("private fun MainGalleryTaskSummaryContent("),
                ).let { placeholder ->
                    placeholder.contains("MainGalleryTaskSummaryContent(task = null)") &&
                        placeholder.contains("Modifier.align(Alignment.Center)")
                },
            ),
            Case(
                "summary is placed after the task list action",
                screen.indexOf(
                    "MainGalleryTaskSummary(",
                    screen.indexOf("R.string.gallery_main_all_tasks"),
                ) > screen.indexOf("R.string.gallery_main_all_tasks"),
            ),
            Case(
                "task list action includes a list icon",
                screen.substring(
                    screen.indexOf(
                        "OutlinedButton(",
                        screen.indexOf("R.string.gallery_main_import"),
                    ),
                    screen.indexOf("mainGalleryTaskSlot("),
                ).contains("Icon(Icons.AutoMirrored.Filled.List"),
            ),
            Case(
                "summary keeps normal padding instead of hard filling height",
                screen.contains(".padding(12.dp)") &&
                    !screen.contains("vertical = 16.dp"),
            ),
            Case(
                "placeholder derives its size from summary content",
                screen.substring(
                    screen.indexOf("private fun MainGalleryTaskSummaryPlaceholder("),
                    screen.indexOf("private fun MainGalleryTaskSummaryContent("),
                ).let { placeholder ->
                    placeholder.contains("MainGalleryTaskSummaryContent(task = null)") &&
                        !placeholder.contains(".height(")
                },
            ),
            Case(
                "success and failure are real summary elements",
                screen.contains("R.string.gallery_task_success_count") &&
                    screen.contains("R.string.gallery_task_failed_count"),
            ),
            Case(
                "summary uses only an outline without a background fill",
                screen.contains(
                    "border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)"
                ) && screen.contains("color = Color.Transparent"),
            ),
            Case(
                "summary sends the selected task id",
                screen.contains("onClick = { onOpenGalleryTask(task.id) }"),
            ),
            Case(
                "summary surface is clickable",
                screen.contains("onClick = onClick"),
            ),
            Case(
                "main route stores the selected task id",
                activity.contains("selectedGalleryTaskId = taskId"),
            ),
            Case(
                "main route opens the task result directly",
                activity.contains("routeName = Route.GalleryTaskDetail.name"),
            ),
            Case(
                "summary resolves localized and unsaved preset labels",
                screen.contains("mainGalleryTaskPresetLabel(") &&
                    screen.contains("R.string.settings_translation_preset_unsaved_name"),
            ),
        ).forEach { case ->
            assertTrue(case.name, case.actual)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
