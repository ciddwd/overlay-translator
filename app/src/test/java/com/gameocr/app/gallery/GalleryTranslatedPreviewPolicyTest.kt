package com.gameocr.app.gallery

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTranslatedPreviewPolicyTest {

    @Test
    fun galleryResultUsesTranslatedPreview_isTableDriven() {
        data class Case(
            val name: String,
            val status: GalleryItemStatus,
            val renderMode: GalleryExportRenderMode,
            val expected: Boolean,
        )

        listOf(
            Case(
                "successful fixed block result",
                GalleryItemStatus.SUCCEEDED,
                GalleryExportRenderMode.FIXED_BLOCKS,
                true,
            ),
            Case(
                "successful adaptive block result",
                GalleryItemStatus.SUCCEEDED,
                GalleryExportRenderMode.ADAPTIVE_BLOCKS,
                true,
            ),
            Case(
                "successful floating window result stays original",
                GalleryItemStatus.SUCCEEDED,
                GalleryExportRenderMode.UNSUPPORTED_FLOATING,
                false,
            ),
            Case(
                "queued result stays original",
                GalleryItemStatus.QUEUED,
                GalleryExportRenderMode.FIXED_BLOCKS,
                false,
            ),
            Case(
                "running result stays original",
                GalleryItemStatus.RUNNING,
                GalleryExportRenderMode.ADAPTIVE_BLOCKS,
                false,
            ),
            Case(
                "failed result stays original",
                GalleryItemStatus.FAILED,
                GalleryExportRenderMode.FIXED_BLOCKS,
                false,
            ),
            Case(
                "canceled result stays original",
                GalleryItemStatus.CANCELED,
                GalleryExportRenderMode.ADAPTIVE_BLOCKS,
                false,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                galleryResultUsesTranslatedPreview(case.status, case.renderMode),
            )
        }
    }

    @Test
    fun translatedPreviewCache_contractIsTableDriven() {
        val store = sourceFile(
            "src/main/java/com/gameocr/app/gallery/GalleryTranslatedPreviewStore.kt"
        ).readText()
        val worker = sourceFile(
            "src/main/java/com/gameocr/app/gallery/GalleryTranslationWorker.kt"
        ).readText()
        val repository = sourceFile(
            "src/main/java/com/gameocr/app/gallery/GalleryTranslationRepository.kt"
        ).readText()
        val exporter = sourceFile(
            "src/main/java/com/gameocr/app/gallery/GalleryTranslationExporter.kt"
        ).readText()

        data class Case(val name: String, val actual: Boolean)

        listOf(
            Case(
                "translated results persist with their task",
                store.contains("File(context.filesDir, \"gallery_translation\")"),
            ),
            Case(
                "translated results keep lossless pixels for preview and export",
                store.contains("Bitmap.CompressFormat.PNG, 100"),
            ),
            Case(
                "floating window rendering is skipped before any cache write",
                store.indexOf("GalleryExportRenderMode.UNSUPPORTED_FLOATING") <
                    store.indexOf("writePreview("),
            ),
            Case(
                "missing cache can be rebuilt from saved source and segments",
                store.contains("imageDecoder.decode(item)") &&
                    store.contains("item.segmentsJson") &&
                    store.contains("renderer.render("),
            ),
            Case(
                "task deletion clears translated previews",
                repository.contains("translatedPreviewStore.deleteTask(taskId)"),
            ),
            Case(
                "worker warms the preview before completing the result row",
                worker.indexOf("translatedPreviewStore.storeTranslatedPreview(") <
                    worker.indexOf("repository.completeItem("),
            ),
            Case(
                "preview failures are isolated from translation completion",
                store.contains("private suspend fun <T> safely(") &&
                    store.contains("Gallery translated preview %s failed"),
            ),
            Case(
                "export reuses the same lossless translated result",
                exporter.contains("translatedPreviewStore.loadTranslatedPreview(item, settings)"),
            ),
        ).forEach { case ->
            assertEquals(case.name, true, case.actual)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
