package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaOcrTilingTest {

    @Test
    fun starts_for_table_driven_cases() {
        data class Case(
            val name: String,
            val length: Int,
            val tileSide: Int,
            val overlap: Int,
            val expected: List<Int>
        )

        val cases = listOf(
            Case(
                name = "short-side-single-tile",
                length = 1440,
                tileSide = 1800,
                overlap = 360,
                expected = listOf(0)
            ),
            Case(
                name = "phone-long-side-two-overlapped-tiles",
                length = 3200,
                tileSide = 1800,
                overlap = 360,
                expected = listOf(0, 1400)
            ),
            Case(
                name = "large-page-multiple-overlapped-tiles",
                length = 5000,
                tileSide = 1800,
                overlap = 360,
                expected = listOf(0, 1440, 2880, 3200)
            ),
            Case(
                name = "overlap-is-clamped-before-step",
                length = 23,
                tileSide = 20,
                overlap = 80,
                expected = listOf(0, 1, 2, 3)
            )
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MangaOcrTiling.startsFor(case.length, case.tileSide, case.overlap)
            )
        }
    }

    @Test
    fun tiles_for_table_driven_screen_shapes() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val expected: List<OcrTile>
        )

        val cases = listOf(
            Case(
                name = "portrait-phone-page-splits-vertically",
                width = 1440,
                height = 3200,
                expected = listOf(
                    OcrTile(0, 0, 1440, 1800),
                    OcrTile(0, 1400, 1440, 3200)
                )
            ),
            Case(
                name = "landscape-page-splits-horizontally",
                width = 3200,
                height = 1440,
                expected = listOf(
                    OcrTile(0, 0, 1800, 1440),
                    OcrTile(1400, 0, 3200, 1440)
                )
            ),
            Case(
                name = "square-large-page-uses-overlapped-grid",
                width = 3200,
                height = 3200,
                expected = listOf(
                    OcrTile(0, 0, 1800, 1800),
                    OcrTile(1400, 0, 3200, 1800),
                    OcrTile(0, 1400, 1800, 3200),
                    OcrTile(1400, 1400, 3200, 3200)
                )
            )
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, MangaOcrTiling.tilesFor(case.width, case.height))
        }
    }

    @Test
    fun should_use_tiles_only_when_image_exceeds_tile_side() {
        assertFalse(MangaOcrTiling.shouldUseTiles(1440, 1700))
        assertTrue(MangaOcrTiling.shouldUseTiles(1440, 3200))
        assertTrue(MangaOcrTiling.shouldUseTiles(3200, 1440))
    }
}
