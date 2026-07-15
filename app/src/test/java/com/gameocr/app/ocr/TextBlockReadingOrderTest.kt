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

    private fun block(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        orientation: TextOrientation? = null
    ): TextBlock =
        TextBlock(
            text = text,
            boundingBox = Rect().apply {
                this.left = left
                this.top = top
                this.right = right
                this.bottom = bottom
            },
            layoutOrientation = orientation
        )
}
