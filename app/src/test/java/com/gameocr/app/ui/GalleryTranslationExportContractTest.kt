package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTranslationExportContractTest {

    @Test
    fun `task results export translated png images through the system folder picker`() {
        val screen = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val exporter = sourceFile(
            "src/main/java/com/gameocr/app/gallery/GalleryTranslationExporter.kt"
        ).readText()
        val renderer = sourceFile(
            "src/main/java/com/gameocr/app/gallery/GalleryTranslatedImageRenderer.kt"
        ).readText()
        val english = sourceFile("src/main/res/values/strings.xml").readText()
        val chinese = sourceFile("src/main/res/values-zh-rCN/strings.xml").readText()

        data class Case(
            val name: String,
            val actual: Boolean,
        )

        listOf(
            Case(
                "system folder picker chooses the destination",
                screen.contains("ActivityResultContracts.OpenDocumentTree()"),
            ),
            Case(
                "export action is terminal-result gated",
                screen.contains(
                    "galleryCanExport(currentTask.status, currentTask.successCount, exportRenderMode)"
                ),
            ),
            Case(
                "floating window export is disabled with a localized reason",
                screen.contains("GalleryExportRenderMode.UNSUPPORTED_FLOATING") &&
                    screen.contains("R.string.gallery_export_floating_unavailable"),
            ),
            Case(
                "export action lives in the top app bar actions menu",
                screen.substring(
                    screen.indexOf("fun GalleryTranslationTaskDetailScreen("),
                    screen.indexOf("private fun GalleryTaskCard("),
                ).let { detail ->
                    detail.contains("DropdownMenu(") &&
                        detail.contains("Icons.Default.MoreVert") &&
                        detail.contains("R.string.gallery_task_more_actions") &&
                        detail.contains("R.string.gallery_export_action") &&
                        detail.contains("R.string.gallery_task_delete")
                },
            ),
            Case(
                "actions menu reports per-image export progress",
                screen.contains("R.string.gallery_exporting") &&
                    screen.contains("currentExportProgress.completed") &&
                    screen.contains("currentExportProgress.total"),
            ),
            Case(
                "result feedback uses the screen snackbar",
                screen.contains("SnackbarHost(snackbarHostState)") &&
                    screen.contains("snackbarHostState.showSnackbar"),
            ),
            Case(
                "exporter creates documents in the selected tree",
                exporter.contains("DocumentsContract.buildDocumentUriUsingTree") &&
                    exporter.contains("DocumentsContract.createDocument"),
            ),
            Case(
                "outputs are encoded as png",
                exporter.contains("Bitmap.CompressFormat.PNG") &&
                    exporter.contains("""const val PNG_MIME_TYPE = "image/png""""),
            ),
            Case(
                "exported png records its author and generating software",
                exporter.contains("ExifInterface.TAG_ARTIST") &&
                    exporter.contains("ExifInterface.TAG_SOFTWARE") &&
                    exporter.contains("saveAttributes()"),
            ),
            Case(
                "metadata is written before copying through the system provider",
                exporter.indexOf("encodedFile = encodeTranslatedPng(rendered)") <
                    exporter.indexOf("DocumentsContract.createDocument") &&
                    exporter.contains("encodedFile.inputStream().use"),
            ),
            Case(
                "temporary encoded images are always cleaned up",
                exporter.contains("file.exists() && !file.delete()"),
            ),
            Case(
                "failed or canceled writes remove partial documents",
                exporter.contains("DocumentsContract.deleteDocument"),
            ),
            Case(
                "images are rendered sequentially and recycled",
                exporter.contains("items.forEachIndexed") &&
                    exporter.contains("rendered") &&
                    exporter.contains(".recycle()"),
            ),
            Case(
                "exporter resolves the task settings snapshot before creating files",
                exporter.contains("repository.settingsForTask(task)") &&
                    exporter.contains("GalleryExportRenderMode.UNSUPPORTED_FLOATING") &&
                    exporter.contains("settings = settings"),
            ),
            Case(
                "renderer erases original source boxes",
                renderer.contains("segment.sourceBoxes") &&
                    renderer.contains("expandedGalleryEraseRect") &&
                    renderer.contains("canvas.drawRoundRect"),
            ),
            Case(
                "renderer samples readable adaptive colors",
                renderer.contains("AdaptiveOverlayStyleAnalyzer.analyze") &&
                    renderer.contains("style.backgroundColor") &&
                    renderer.contains("adaptiveStyle?.foregroundColor"),
            ),
            Case(
                "fixed renderer applies theme alpha border and custom font",
                renderer.contains("galleryExportPalette(settings") &&
                    renderer.contains("overlayFontManager.typefaceFor(settings)") &&
                    renderer.contains("palette.borderStyle") &&
                    renderer.contains("palette.backgroundColor"),
            ),
            Case(
                "renderer applies typography effects and spacing",
                renderer.contains("textStyle.bold") &&
                    renderer.contains("textStyle.italic") &&
                    renderer.contains("isUnderlineText = textStyle.underline") &&
                    renderer.contains("letterSpacing = textStyle.letterSpacingEm") &&
                    renderer.contains("textStyle.lineSpacingMultiplier") &&
                    renderer.contains("textStyle.strokeEnabled") &&
                    renderer.contains("textStyle.shadowEnabled"),
            ),
            Case(
                "renderer uses saved output orientation for horizontal and vertical text",
                renderer.contains("galleryExportOrientation(settings") &&
                    renderer.contains("TextOrientation.HORIZONTAL_RTL") &&
                    renderer.contains("TextOrientation.VERTICAL_LTR") &&
                    renderer.contains("horizontalRtlDisplayText"),
            ),
            Case(
                "horizontal translation wraps inside its region",
                renderer.contains("StaticLayout.Builder") &&
                    renderer.contains("largestFittingTextSize"),
            ),
            Case(
                "vertical translation reuses the existing vertical renderer",
                renderer.contains("VerticalTextDrawer.measure") &&
                    renderer.contains("VerticalTextDrawer.draw"),
            ),
            Case(
                "both locales contain complete export feedback",
                listOf(
                    "gallery_export_action",
                    "gallery_export_floating_unavailable",
                    "gallery_exporting",
                    "gallery_export_success",
                    "gallery_export_partial",
                    "gallery_export_empty",
                    "gallery_export_failed",
                    "gallery_task_more_actions",
                ).all { name ->
                    """<string name="$name">""" in english &&
                        """<string name="$name">""" in chinese
                },
            ),
            Case(
                "task result export action uses save as wording in both locales",
                """<string name="gallery_export_action">Save as…</string>""" in english &&
                    """<string name="gallery_export_action">另存为…</string>""" in chinese,
            ),
        ).forEach { case ->
            assertEquals(case.name, true, case.actual)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
