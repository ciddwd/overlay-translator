package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryImagePreviewContractTest {

    @Test
    fun `gallery thumbnails open a safe original image preview`() {
        val screen = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val decoder = sourceFile(
            "src/main/java/com/gameocr/app/gallery/GalleryImageDecoder.kt"
        ).readText()
        val preview = screen.substring(
            screen.indexOf("private fun GalleryImagePreviewDialog("),
            screen.indexOf("private fun GalleryStatusText("),
        )
        val resultThumbnail = screen.substring(
            screen.indexOf("private fun GalleryResultThumbnail("),
            screen.indexOf("private data class GalleryPreviewSource("),
        )

        data class Case(
            val name: String,
            val actual: Boolean,
        )

        listOf(
            Case(
                "selected image card is clickable",
                screen.contains("onClick = onPreview"),
            ),
            Case(
                "result thumbnail is clickable",
                screen.contains(".clickable(onClick = onPreview)"),
            ),
            Case(
                "result thumbnail shows the same clean original image",
                resultThumbnail.contains("Image(") &&
                    !resultThumbnail.contains("Canvas(") &&
                    !resultThumbnail.contains("segments") &&
                    !resultThumbnail.contains("E11D48"),
            ),
            Case(
                "delete action remains independent",
                screen.contains("onClick = onRemove"),
            ),
            Case(
                "both screens use the shared preview dialog",
                screen.split("GalleryImagePreviewDialog(").size - 1 == 3,
            ),
            Case(
                "selected images are passed to the preview as a list",
                screen.contains("sources = selectedUris.mapIndexed"),
            ),
            Case(
                "selected image preview opens at the tapped image",
                screen.contains("onPreview = { previewIndex = index }"),
            ),
            Case(
                "preview supports horizontal previous and next navigation",
                preview.contains("HorizontalPager(") &&
                    preview.contains("state = pagerState"),
            ),
            Case(
                "preview supports pinch zoom and dragging",
                preview.contains("rememberTransformableState") &&
                    preview.contains(".transformable(") &&
                    preview.contains(".graphicsLayer"),
            ),
            Case(
                "one-to-one scale leaves horizontal swipes to the pager",
                preview.contains(
                    "canPan = { scale > GALLERY_PREVIEW_MIN_SCALE }"
                ),
            ),
            Case(
                "result preview remains a single original image",
                screen.contains("sources = listOf(source)") &&
                    screen.contains("initialPage = 0"),
            ),
            Case(
                "preview is full screen",
                preview.contains("usePlatformDefaultWidth = false") &&
                    preview.contains("decorFitsSystemWindows = false"),
            ),
            Case(
                "original image keeps its aspect ratio",
                preview.contains("contentScale = ContentScale.Fit"),
            ),
            Case(
                "preview excludes detection overlays",
                !preview.contains("Canvas(") && !preview.contains("segments"),
            ),
            Case(
                "preview decoder caps memory use",
                decoder.contains("const val PREVIEW_DIMENSION = 3072") &&
                    decoder.contains("fun decodePreview("),
            ),
        ).forEach { case ->
            assertEquals(case.name, true, case.actual)
        }
    }

    @Test
    fun `preview initial page is safe for every list state`() {
        data class Case(
            val name: String,
            val requestedPage: Int,
            val pageCount: Int,
            val expectedPage: Int,
        )

        listOf(
            Case("empty list", 0, 0, 0),
            Case("negative page", -1, 4, 0),
            Case("first page", 0, 4, 0),
            Case("middle page", 2, 4, 2),
            Case("last page", 3, 4, 3),
            Case("page beyond the end", 8, 4, 3),
            Case("invalid page count", 2, -1, 0),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedPage,
                galleryPreviewInitialPage(case.requestedPage, case.pageCount),
            )
        }
    }

    @Test
    fun `preview zoom is clamped for every scale change`() {
        data class Case(
            val name: String,
            val currentScale: Float,
            val zoomChange: Float,
            val expectedScale: Float,
        )

        listOf(
            Case("no zoom", 1f, 1f, 1f),
            Case("zoom in", 1f, 2f, 2f),
            Case("incremental zoom", 2f, 1.5f, 3f),
            Case("clamp below minimum", 2f, 0.1f, 1f),
            Case("clamp above maximum", 4f, 2f, 5f),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedScale,
                galleryPreviewScale(case.currentScale, case.zoomChange),
                0.0001f,
            )
        }
    }

    @Test
    fun `preview pan limits avoid blank space for every image shape`() {
        data class Case(
            val name: String,
            val viewportWidth: Float,
            val viewportHeight: Float,
            val imageWidth: Float,
            val imageHeight: Float,
            val scale: Float,
            val expectedX: Float,
            val expectedY: Float,
        )

        listOf(
            Case("square at minimum scale", 100f, 100f, 100f, 100f, 1f, 0f, 0f),
            Case("square zoomed", 100f, 100f, 100f, 100f, 2f, 50f, 50f),
            Case("landscape zoomed", 100f, 100f, 200f, 100f, 2f, 50f, 0f),
            Case("portrait zoomed", 100f, 100f, 100f, 200f, 2f, 0f, 50f),
            Case("missing viewport", 0f, 100f, 100f, 100f, 2f, 0f, 0f),
            Case("missing image size", 100f, 100f, 0f, 100f, 2f, 0f, 0f),
        ).forEach { case ->
            val limit = galleryPreviewPanLimit(
                viewportWidth = case.viewportWidth,
                viewportHeight = case.viewportHeight,
                imageWidth = case.imageWidth,
                imageHeight = case.imageHeight,
                scale = case.scale,
            )
            assertEquals("${case.name} x", case.expectedX, limit.x, 0.0001f)
            assertEquals("${case.name} y", case.expectedY, limit.y, 0.0001f)
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
