package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTranslationConfirmStyleTest {

    @Test
    fun `task settings and add images use the requested order and preset drawer behavior`() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val start = source.indexOf("fun GalleryTranslationConfirmScreen(")
        val end = source.indexOf("private fun GallerySelectedThumbnail(", start)
        assertTrue("confirm screen block exists", start >= 0 && end > start)
        val screen = source.substring(start, end)

        data class OrderCase(
            val name: String,
            val earlier: String,
            val later: String,
        )

        listOf(
            OrderCase(
                "preset expander is shown after translator settings",
                "R.string.gallery_confirm_translator",
                "GalleryPresetSwitcher(",
            ),
            OrderCase(
                "task settings are shown before selected count",
                "R.string.gallery_confirm_settings",
                "R.string.gallery_confirm_count",
            ),
            OrderCase(
                "add image tile follows selected thumbnails",
                "previewUris.forEachIndexed",
                "GalleryAddImageTile(",
            ),
        ).forEach { case ->
            val earlierIndex = screen.indexOf(case.earlier)
            val laterIndex = screen.indexOf(case.later)
            assertTrue(case.name, earlierIndex >= 0 && laterIndex > earlierIndex)
        }

        data class RemovedCase(
            val name: String,
            val marker: String,
        )

        listOf(
            RemovedCase(
                "image ordering toggle is removed",
                "GalleryImportOrderToggle(",
            ),
            RemovedCase(
                "image selection is never reordered",
                "sortEntries(",
            ),
            RemovedCase(
                "obsolete export-only translated image hint is removed",
                "R.string.gallery_confirm_output",
            ),
            RemovedCase(
                "glossary summary is removed from task settings",
                "R.string.gallery_confirm_scope",
            ),
        ).forEach { case ->
            assertTrue(case.name, !screen.contains(case.marker))
        }

        data class PresetToggleCase(
            val name: String,
            val marker: String,
        )

        listOf(
            PresetToggleCase(
                "preset switcher starts expanded",
                "var showPresetSwitcher by rememberSaveable { mutableStateOf(true) }",
            ),
            PresetToggleCase(
                "task settings button toggles preset visibility",
                "showPresetSwitcher = !showPresetSwitcher",
            ),
            PresetToggleCase(
                "preset switcher uses drawer-like animated visibility",
                "AnimatedVisibility(",
            ),
            PresetToggleCase(
                "collapsed state uses the show preset label",
                "R.string.gallery_confirm_show_presets",
            ),
            PresetToggleCase(
                "expanded state offers a collapse label",
                "R.string.gallery_confirm_hide_presets",
            ),
            PresetToggleCase(
                "expanded carousel sits above the drawer handle",
                "GalleryPresetSwitcher(",
            ),
            PresetToggleCase(
                "expanded state points upward before the label",
                "Icons.Default.KeyboardArrowUp",
            ),
            PresetToggleCase(
                "collapsed state points downward before the label",
                "Icons.Default.KeyboardArrowDown",
            ),
        ).forEach { case ->
            assertTrue(case.name, screen.contains(case.marker))
        }

        assertTrue(
            "drawer handle follows the preset content",
            screen.indexOf("GalleryPresetSwitcher(") <
                screen.indexOf("showPresetSwitcher = !showPresetSwitcher"),
        )
    }

    @Test
    fun `selected image grid fills phone and tablet widths then wraps`() {
        data class Case(
            val name: String,
            val availableWidthDp: Float,
            val expectedColumns: Int,
            val expectedItemWidthDp: Float,
        )

        listOf(
            Case("narrow phone", 280f, 2, 136f),
            Case("regular phone", 328f, 3, 104f),
            Case("large phone", 400f, 3, 128f),
            Case("small tablet", 600f, 5, 113.6f),
            Case("large tablet", 840f, 8, 98f),
            Case("invalid width", 0f, 1, 0f),
        ).forEach { case ->
            val columns = galleryThumbnailColumnCount(case.availableWidthDp)
            assertEquals(case.name, case.expectedColumns, columns)
            assertEquals(
                case.name,
                case.expectedItemWidthDp,
                galleryThumbnailWidthDp(case.availableWidthDp, columns),
                0.001f,
            )
        }
    }

    @Test
    fun `selected image grid uses a full width wrapping flow layout`() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val start = source.indexOf("fun GalleryTranslationConfirmScreen(")
        val end = source.indexOf("private fun GalleryPresetSwitcher(", start)
        assertTrue("confirm screen block exists", start >= 0 && end > start)
        val screen = source.substring(start, end)

        data class Case(
            val name: String,
            val marker: String,
        )

        listOf(
            Case("reads the available width", "BoxWithConstraints("),
            Case("wraps thumbnails onto more rows", "FlowRow("),
            Case("fills the available row width", "Modifier.fillMaxWidth()"),
            Case("limits each row to the calculated columns", "maxItemsInEachRow = columnCount"),
            Case("calculates responsive columns", "galleryThumbnailColumnCount(maxWidth.value)"),
            Case("calculates an equal item width", "galleryThumbnailWidthDp("),
            Case("keeps stable thumbnail identity", "key(uri)"),
            Case("keeps add images after thumbnails", "GalleryAddImageTile("),
        ).forEach { case ->
            assertTrue(case.name, screen.contains(case.marker))
        }

        assertTrue("single-line horizontal list is removed", !screen.contains("LazyRow("))
    }

    @Test
    fun `add image tile shows a plus with an accessible label`() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/ui/GalleryTranslationScreens.kt"
        ).readText()
        val start = source.indexOf("private fun GalleryAddImageTile(")
        assertTrue("add image tile exists", start >= 0)
        val tile = source.substring(start)

        data class Case(
            val name: String,
            val marker: String,
        )

        listOf(
            Case("shows a plus icon", "imageVector = Icons.Default.Add"),
            Case("preserves the add images accessibility label", "gallery_confirm_add_photos"),
        ).forEach { case ->
            assertTrue(case.name, tile.contains(case.marker))
        }
    }


    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
