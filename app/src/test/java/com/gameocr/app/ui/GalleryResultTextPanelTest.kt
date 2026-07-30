package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryResultTextPanelTest {

    @Test
    fun `result text uses the space beside the thumbnail and scrolls internally`() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val start = source.indexOf("private fun GalleryResultItem(")
        val end = source.indexOf("private fun GalleryResultThumbnail(", start)
        assertTrue("result item block exists", start >= 0 && end > start)
        val resultItem = source.substring(start, end)
        val sectionStart = resultItem.indexOf("private fun GalleryResultTextSection(")
        assertTrue("text section block exists", sectionStart > 0)
        val itemContent = resultItem.substring(0, sectionStart)
        val sectionContent = resultItem.substring(sectionStart)

        data class Case(
            val name: String,
            val marker: String,
            val expectedPresent: Boolean,
        )

        listOf(
            Case(
                "filename and status share the top row",
                "GalleryItemStatusText(item.status)",
                true,
            ),
            Case(
                "filename stays on one line",
                "maxLines = 1",
                true,
            ),
            Case(
                "long filename is truncated at the end",
                "overflow = TextOverflow.Ellipsis",
                true,
            ),
            Case(
                "text panel height follows the thumbnail ratio",
                "galleryResultTextPaneHeightDp(thumbnailRatio).dp",
                true,
            ),
            Case(
                "source and translation use separate sections",
                "GalleryResultTextSection(",
                true,
            ),
            Case(
                "source and translation are separated by one divider",
                "HorizontalDivider(",
                true,
            ),
            Case(
                "source and translation stay selectable",
                "SelectionContainer",
                true,
            ),
            Case(
                "text region count is removed",
                "gallery_item_segments",
                false,
            ),
            Case(
                "old bottom fold control is removed",
                "Icons.Default.ExpandMore",
                false,
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expectedPresent, case.marker in resultItem)
        }
        assertTrue(
            "status follows the filename and occupies the right side",
            resultItem.indexOf("GalleryItemStatusText(item.status)") >
                resultItem.indexOf("item.displayName"),
        )
        assertEquals(
            "source and translation create two independent sections",
            2,
            Regex("""GalleryResultTextSection\s*\(""").findAll(itemContent).count(),
        )
        assertEquals(
            "source and translation each receive half of the remaining height",
            2,
            Regex(
                """GalleryResultTextSection\([\s\S]*?modifier = Modifier\.weight\(1f\),\s*\)"""
            ).findAll(itemContent).count(),
        )
        val sourceSection = itemContent.indexOf(
            "title = stringResource(R.string.gallery_item_source)"
        )
        val divider = itemContent.indexOf("HorizontalDivider(", sourceSection)
        val translationSection = itemContent.indexOf(
            "title = stringResource(R.string.gallery_item_translation)"
        )
        assertTrue(
            "the only divider sits between source and translation",
            sourceSection >= 0 && divider > sourceSection && translationSection > divider,
        )
        listOf(
            "each section owns its scroll state" to "val scrollState = rememberScrollState()",
            "each section scrolls internally" to ".verticalScroll(scrollState)",
            "text remains selectable" to "SelectionContainer",
        ).forEach { (name, marker) ->
            assertTrue(name, marker in sectionContent)
        }
        assertTrue("individual text outlines are removed", "BorderStroke" !in sectionContent)
        assertTrue("individual text surfaces are removed", "Surface(" !in sectionContent)
        assertTrue(
            "portrait text panel is not capped below the thumbnail",
            ".coerceIn(112.dp, 220.dp)" !in itemContent,
        )
    }

    @Test
    fun `thumbnail ratio is safe for every stored dimension case`() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val expected: Float,
        )

        listOf(
            Case("landscape", 200, 100, 2f),
            Case("portrait", 100, 200, 0.5f),
            Case("very wide clamps", 1000, 100, 2.2f),
            Case("very tall clamps", 100, 1000, 0.45f),
            Case("missing dimensions", 0, 0, 1f),
            Case("invalid height", 100, -1, 1f),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                galleryResultThumbnailRatio(case.width, case.height),
                0.0001f,
            )
        }
    }

    @Test
    fun `text pane follows tall thumbnails and keeps a readable landscape minimum`() {
        data class Case(
            val name: String,
            val thumbnailRatio: Float,
            val expectedHeightDp: Float,
        )

        listOf(
            Case("square", 1f, 112f),
            Case("landscape keeps minimum", 2f, 112f),
            Case("portrait follows thumbnail", 0.5f, 224f),
            Case("tall clamped ratio remains fully matched", 0.45f, 112f / 0.45f),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedHeightDp,
                galleryResultTextPaneHeightDp(case.thumbnailRatio),
                0.0001f,
            )
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
