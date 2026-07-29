package com.gameocr.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTranslationConfirmStyleTest {

    @Test
    fun `task settings and add images use the requested order and outlined style`() {
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
                "task settings are shown before add images",
                "R.string.gallery_confirm_settings",
                "R.string.gallery_confirm_add_photos",
            ),
            OrderCase(
                "add images is shown before selected count",
                "R.string.gallery_confirm_add_photos",
                "R.string.gallery_confirm_count",
            ),
        ).forEach { case ->
            val earlierIndex = screen.indexOf(case.earlier)
            val laterIndex = screen.indexOf(case.later)
            assertTrue(case.name, earlierIndex >= 0 && laterIndex > earlierIndex)
        }

        data class StyleCase(
            val name: String,
            val marker: String,
            val expectedCount: Int,
        )

        listOf(
            StyleCase(
                "only add images uses the primary 1dp border",
                "border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)",
                1,
            ),
            StyleCase(
                "settings card uses the neutral 1dp border",
                "MaterialTheme.colorScheme.outlineVariant",
                1,
            ),
            StyleCase(
                "settings card and add button use the surface background",
                "containerColor = MaterialTheme.colorScheme.surface",
                2,
            ),
            StyleCase(
                "add images uses an outlined button",
                "OutlinedButton(",
                1,
            ),
            StyleCase(
                "add images no longer uses a filled tonal button",
                "FilledTonalButton(",
                0,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expectedCount,
                Regex(Regex.escape(case.marker)).findAll(screen).count(),
            )
        }
    }

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull(File::isFile)
            ?: error("Source file not found: $path")
}
