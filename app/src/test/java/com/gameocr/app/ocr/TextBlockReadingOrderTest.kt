package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class TextBlockReadingOrderTest {

    @Test
    fun sort_horizontal_blocks_groups_same_line_before_sorting_left_to_right() {
        val blocks = listOf(
            block("7/2更新:", 39, 24, 252, 92),
            block("因為沒有可更新的資訊所以小小回報現況", 41, 109, 1019, 180),
            block("6/17左右我們收到代理提供的Focus PX1000，", 38, 195, 1153, 270),
            block("我們也交", 1170, 202, 1391, 264),
            block("換線材測試Vortex依舊是不能啟動(海韻官網確認pin out", 38, 286, 1402, 355),
            block("相同)", 40, 376, 172, 443),
            block("但至今還沒收到", 973, 462, 1354, 530),
            block("隔天寄回給海韻到6/23海韻說已收到，", 39, 460, 952, 532),
            block("任何報告", 41, 552, 260, 617),
            block("Focus安裝後目前一切安好", 37, 637, 685, 705)
        )

        val sorted = sortTextBlocksForReading(blocks).map { it.text }

        assertEquals(
            listOf(
                "7/2更新:",
                "因為沒有可更新的資訊所以小小回報現況",
                "6/17左右我們收到代理提供的Focus PX1000，",
                "我們也交",
                "換線材測試Vortex依舊是不能啟動(海韻官網確認pin out",
                "相同)",
                "隔天寄回給海韻到6/23海韻說已收到，",
                "但至今還沒收到",
                "任何報告",
                "Focus安裝後目前一切安好"
            ),
            sorted
        )
    }

    @Test
    fun sort_vertical_rtl_blocks_reads_right_column_before_left_column() {
        val blocks = listOf(
            block("左上", 80, 10, 110, 80, TextOrientation.VERTICAL_RTL),
            block("右下", 180, 90, 210, 160, TextOrientation.VERTICAL_RTL),
            block("右上", 180, 10, 210, 80, TextOrientation.VERTICAL_RTL),
            block("左下", 80, 90, 110, 160, TextOrientation.VERTICAL_RTL)
        )

        val sorted = sortTextBlocksForReading(blocks).map { it.text }

        assertEquals(listOf("右上", "右下", "左上", "左下"), sorted)
    }

    @Test
    fun resolveTextBlockReadingOrientation_andSort_coverUnknownAndExplicitDirections() {
        data class Case(
            val name: String,
            val blocks: List<TextBlock>,
            val hint: TextOrientation?,
            val expectedOrientation: TextOrientation,
            val expectedOrder: List<String>,
        )

        val portraitBlocks = listOf(
            block("left", 20, 10, 50, 120),
            block("right", 100, 10, 130, 120),
        )
        val horizontalBlocks = listOf(
            block("left", 10, 10, 70, 40),
            block("right", 90, 10, 150, 40),
        )
        val cases = listOf(
            Case(
                "unknown portrait blocks infer Japanese vertical rtl",
                portraitBlocks,
                TextOrientation.UNKNOWN,
                TextOrientation.VERTICAL_RTL,
                listOf("right", "left"),
            ),
            Case(
                "explicit horizontal rtl sorts right first",
                horizontalBlocks,
                TextOrientation.HORIZONTAL_RTL,
                TextOrientation.HORIZONTAL_RTL,
                listOf("right", "left"),
            ),
            Case(
                "explicit horizontal ltr sorts left first",
                horizontalBlocks,
                TextOrientation.HORIZONTAL_LTR,
                TextOrientation.HORIZONTAL_LTR,
                listOf("left", "right"),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedOrientation,
                resolveTextBlockReadingOrientation(case.blocks, case.hint),
            )
            assertEquals(
                case.name,
                case.expectedOrder,
                sortTextBlocksForReading(case.blocks, case.hint).map { it.text },
            )
        }
    }

    @Test
    fun sort_verticalRtlManga_tableDriven_prioritizesPageRowsWithoutChangingLegacyOcr() {
        data class Box(
            val text: String,
            val left: Int,
            val top: Int,
            val right: Int,
            val bottom: Int,
            val granularity: TextRegionGranularity = TextRegionGranularity.BUBBLE,
        )

        data class Case(
            val name: String,
            val boxes: List<Box>,
            val expectedBands: List<Set<String>>,
            val expectedOrder: List<String>,
        )

        val separatedRows = listOf(
            Box("top-left", 20, 10, 50, 80),
            Box("bottom-right", 120, 140, 150, 210),
            Box("top-right", 120, 10, 150, 80),
            Box("bottom-left", 20, 140, 50, 210),
        )
        val closeColumns = listOf(
            Box("right-top", 120, 10, 150, 80),
            Box("left-top", 20, 10, 50, 80),
            Box("right-next", 120, 90, 150, 160),
            Box("left-next", 20, 90, 50, 160),
        )
        val cases = listOf(
            Case(
                name = "two panel rows read the full upper row before lower-right content",
                boxes = separatedRows,
                expectedBands = listOf(
                    setOf("top-left", "top-right"),
                    setOf("bottom-right", "bottom-left"),
                ),
                expectedOrder = listOf("top-right", "top-left", "bottom-right", "bottom-left"),
            ),
            Case(
                name = "small inter-line gap stays one row and preserves vertical columns",
                boxes = closeColumns,
                expectedBands = listOf(
                    setOf("right-top", "left-top", "right-next", "left-next"),
                ),
                expectedOrder = listOf("right-top", "right-next", "left-top", "left-next"),
            ),
            Case(
                name = "a tall bridging region prevents speculative row splitting",
                boxes = listOf(
                    Box("right-bridge", 120, 0, 150, 220),
                    Box("left-top", 20, 10, 50, 80),
                    Box("left-bottom", 20, 140, 50, 210),
                ),
                expectedBands = listOf(setOf("right-bridge", "left-top", "left-bottom")),
                expectedOrder = listOf("right-bridge", "left-top", "left-bottom"),
            ),
            Case(
                name = "free text semantic also enables manga row ordering",
                boxes = separatedRows.map { it.copy(granularity = TextRegionGranularity.FREE_TEXT) },
                expectedBands = listOf(
                    setOf("top-left", "top-right"),
                    setOf("bottom-right", "bottom-left"),
                ),
                expectedOrder = listOf("top-right", "top-left", "bottom-right", "bottom-left"),
            ),
            Case(
                name = "unknown OCR regions keep the legacy global column order",
                boxes = separatedRows.map { it.copy(granularity = TextRegionGranularity.UNKNOWN) },
                expectedBands = listOf(
                    setOf("top-left", "top-right"),
                    setOf("bottom-right", "bottom-left"),
                ),
                expectedOrder = listOf("top-right", "bottom-right", "top-left", "bottom-left"),
            ),
        )

        cases.forEach { case ->
            val blocks = case.boxes.map { box ->
                block(
                    text = box.text,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    orientation = TextOrientation.VERTICAL_RTL,
                    granularity = box.granularity,
                )
            }
            assertEquals(
                "${case.name} bands",
                case.expectedBands,
                splitMangaHorizontalBands(blocks).map { band -> band.map { it.text }.toSet() },
            )
            assertEquals(
                case.name,
                case.expectedOrder,
                sortTextBlocksForReading(blocks).map { it.text },
            )
        }
    }

    @Test
    fun mangaHorizontalBandGapThreshold_tableDriven_scalesWithSourceTextThickness() {
        data class Case(
            val name: String,
            val sourceThicknesses: List<Int>,
            val expectedThreshold: Int,
        )

        val cases = listOf(
            Case(
                name = "device page uses half of a seventy eight pixel median text line",
                sourceThicknesses = listOf(78, 78, 78),
                expectedThreshold = 39,
            ),
            Case(
                name = "even sample count uses the numeric median",
                sourceThicknesses = listOf(20, 40),
                expectedThreshold = 15,
            ),
            Case(
                name = "tiny geometry still requires a positive gap",
                sourceThicknesses = listOf(1),
                expectedThreshold = 1,
            ),
        )

        cases.forEach { case ->
            val blocks = case.sourceThicknesses.mapIndexed { index, thickness ->
                block(
                    text = "block-$index",
                    left = index * 100,
                    top = 0,
                    right = index * 100 + thickness,
                    bottom = thickness * 3,
                    orientation = TextOrientation.VERTICAL_RTL,
                    granularity = TextRegionGranularity.BUBBLE,
                ).copy(
                    sourceBoxes = listOf(
                        Rect().apply {
                            left = index * 100
                            top = 0
                            right = index * 100 + thickness
                            bottom = thickness * 3
                        }
                    )
                )
            }
            assertEquals(
                case.name,
                case.expectedThreshold,
                mangaHorizontalBandGapThresholdPx(blocks),
            )
        }
    }

    private fun block(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        orientation: TextOrientation? = null,
        granularity: TextRegionGranularity = TextRegionGranularity.UNKNOWN,
    ): TextBlock =
        TextBlock(
            text = text,
            boundingBox = Rect().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            },
            layoutOrientation = orientation,
            regionGranularity = granularity,
        )
}
