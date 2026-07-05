package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import com.gameocr.app.ocr.HeuristicOrientationClassifier.Companion.classifyByGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 启发式方向判别核心逻辑单测。直接调 [HeuristicOrientationClassifier.classifyByGeometry]
 * 纯函数，不依赖 android.graphics.Rect / Bitmap，纯 JVM 跑通（参考 [BubbleClustererTest] 同款模式）。
 *
 * 覆盖场景：横排长句 / 竖排单列 / 竖排多列 / stacked logo / 横竖混排 / 单 box / 空。
 */
class HeuristicOrientationClassifierTest {

    @Test
    fun classify_by_geometry_table_driven_core_cases() {
        data class Case(
            val name: String,
            val rects: List<IntRect>,
            val expected: TextOrientation,
            val expectedSource: String
        )

        val cases = listOf(
            Case(
                name = "empty",
                rects = emptyList(),
                expected = TextOrientation.UNKNOWN,
                expectedSource = "heuristic-too-few-boxes"
            ),
            Case(
                name = "horizontal",
                rects = listOf(
                    IntRect(0, 0, 400, 50),
                    IntRect(0, 80, 400, 130),
                    IntRect(0, 160, 400, 210)
                ),
                expected = TextOrientation.HORIZONTAL_LTR,
                expectedSource = "heuristic-boxes"
            ),
            Case(
                name = "vertical-tall",
                rects = listOf(
                    IntRect(100, 0, 140, 200),
                    IntRect(100, 220, 140, 420),
                    IntRect(100, 440, 140, 640)
                ),
                expected = TextOrientation.VERTICAL_RTL,
                expectedSource = "heuristic-boxes"
            ),
            Case(
                name = "short-columns-are-unknown-for-routing",
                rects = listOf(
                    IntRect(100, 0, 160, 90),
                    IntRect(180, 0, 240, 90),
                    IntRect(260, 0, 320, 90)
                ),
                expected = TextOrientation.UNKNOWN,
                expectedSource = "heuristic-boxes"
            ),
            Case(
                name = "stacked-logo",
                rects = listOf(
                    IntRect(100, 0, 160, 60),
                    IntRect(100, 80, 160, 140),
                    IntRect(100, 160, 160, 220),
                    IntRect(100, 240, 160, 300)
                ),
                expected = TextOrientation.STACKED,
                expectedSource = "heuristic-boxes"
            ),
            Case(
                name = "strong-vertical-with-ui-noise",
                rects = listOf(
                    IntRect(50, 200, 100, 700),
                    IntRect(150, 200, 200, 700),
                    IntRect(250, 200, 300, 700),
                    IntRect(0, 0, 800, 50),
                    IntRect(0, 900, 800, 950)
                ),
                expected = TextOrientation.VERTICAL_RTL,
                expectedSource = "heuristic-strong-vertical"
            ),
            Case(
                name = "zhtw-vertical-rtl-game-ui-logcat-regression",
                rects = listOf(
                    // From 02_zhtw_vertical_rtl_game_ui.png on device logcat.
                    IntRect(560, 402, 693, 799),
                    IntRect(687, 407, 810, 796),
                    IntRect(442, 408, 556, 795),
                    IntRect(1046, 350, 1197, 909),
                    IntRect(922, 350, 1078, 909),
                    IntRect(194, 650, 306, 844),
                    IntRect(302, 648, 421, 846),
                    IntRect(236, 1927, 318, 2068)
                ),
                expected = TextOrientation.VERTICAL_RTL,
                expectedSource = "heuristic-boxes"
            )
        )

        cases.forEach { case ->
            val r = classifyByGeometry(case.rects, bitmapW = 1000, bitmapH = 1000)
            assertEquals(case.name, case.expected, r.orientation)
            assertEquals(case.name, case.expectedSource, r.source)
        }
    }

    @Test
    fun empty_returns_unknown() {
        val r = classifyByGeometry(emptyList(), bitmapW = 1000, bitmapH = 1000)
        assertEquals(TextOrientation.UNKNOWN, r.orientation)
        assertEquals("heuristic-too-few-boxes", r.source)
    }

    @Test
    fun single_box_returns_unknown() {
        // 1 个 box 统计样本不足，宁可不动
        val r = classifyByGeometry(listOf(IntRect(0, 0, 100, 100)), 1000, 1000)
        assertEquals(TextOrientation.UNKNOWN, r.orientation)
        assertEquals("heuristic-too-few-boxes", r.source)
    }

    @Test
    fun horizontal_long_lines_classify_as_horizontal_ltr() {
        // 3 行宽 box，w >> h（典型横排句段，比例 4:1）
        val rects = listOf(
            IntRect(0, 0, 400, 50),
            IntRect(0, 80, 400, 130),
            IntRect(0, 160, 400, 210)
        )
        val r = classifyByGeometry(rects, 500, 300)
        assertEquals(TextOrientation.HORIZONTAL_LTR, r.orientation)
        assertEquals("heuristic-boxes", r.source)
        assertTrue("confidence should be high for clear horizontal", r.confidence >= 0.5f)
    }

    @Test
    fun vertical_single_column_classify_as_vertical_rtl() {
        // 1 列 4 个高瘦 box，h >> w（典型竖排单列，比例 1:5）
        val rects = listOf(
            IntRect(100, 0, 140, 200),
            IntRect(100, 220, 140, 420),
            IntRect(100, 440, 140, 640),
            IntRect(100, 660, 140, 860)
        )
        val r = classifyByGeometry(rects, 300, 1000)
        assertEquals(TextOrientation.VERTICAL_RTL, r.orientation)
        assertEquals("heuristic-boxes", r.source)
        assertTrue("confidence should be high for clear vertical", r.confidence >= 0.5f)
    }

    @Test
    fun vertical_multi_columns_classify_as_vertical_rtl() {
        // 3 列各 2 个高瘦 box，模拟日漫页面气泡内多列竖排
        val rects = listOf(
            // 左列
            IntRect(50, 0, 90, 200),
            IntRect(50, 220, 90, 420),
            // 中列
            IntRect(150, 0, 190, 200),
            IntRect(150, 220, 190, 420),
            // 右列
            IntRect(250, 0, 290, 200),
            IntRect(250, 220, 290, 420)
        )
        val r = classifyByGeometry(rects, 400, 500)
        assertEquals(TextOrientation.VERTICAL_RTL, r.orientation)
    }

    @Test
    fun stacked_logo_classify_as_stacked() {
        // 5 个正方 box 叠在同一 x 列（一字母一行的英文 logo，如 "BANK\n" 竖着写）
        val rects = listOf(
            IntRect(100, 0, 160, 60),
            IntRect(100, 80, 160, 140),
            IntRect(100, 160, 160, 220),
            IntRect(100, 240, 160, 300),
            IntRect(100, 320, 160, 380)
        )
        val r = classifyByGeometry(rects, 300, 500)
        assertEquals(TextOrientation.STACKED, r.orientation)
    }

    @Test
    fun two_square_boxes_not_stacked() {
        // 仅 2 个正方 box 不该判 STACKED（"OK" / "NO" 类单字组）；总体仍归类 UNKNOWN
        val rects = listOf(
            IntRect(100, 0, 160, 60),
            IntRect(100, 80, 160, 140)
        )
        val r = classifyByGeometry(rects, 300, 500)
        // 2 个接近正方 box：既不是 portrait（h/w=1.0 不 >2.0）也不是 landscape → UNKNOWN
        assertEquals(TextOrientation.UNKNOWN, r.orientation)
    }

    @Test
    fun multi_column_vertical_not_misclassified_as_stacked() {
        // 关键回归：日漫竖排多列（box 接近正方）不应被 STACKED 判据吸走
        // 列数 ≥ 2 + 列间距明显（x center 差 > avg width * 0.5）→ 第 3 条件不满足
        val rects = listOf(
            IntRect(50, 0, 110, 60),
            IntRect(50, 80, 110, 140),
            IntRect(200, 0, 260, 60),
            IntRect(200, 80, 260, 140)
        )
        val r = classifyByGeometry(rects, 400, 200)
        // 此 case box 都正方且分布在两列：portraitRatio=0, landscapeRatio=0 → UNKNOWN
        // STACKED 第 3 条件（x center 差 < avg w * 0.5）不满足，不会误判
        assertEquals(TextOrientation.UNKNOWN, r.orientation)
    }

    @Test
    fun three_extreme_tall_boxes_classify_via_strong_vertical_judge() {
        // 3 个极细长 box（h/w = 10），刚好命中 STRONG_VERTICAL_MIN_COUNT 下限
        val rects = listOf(
            IntRect(50, 0, 100, 500),
            IntRect(150, 0, 200, 500),
            IntRect(250, 0, 300, 500)
        )
        val r = classifyByGeometry(rects, 400, 600)
        assertEquals(TextOrientation.VERTICAL_RTL, r.orientation)
        assertEquals("heuristic-strong-vertical", r.source)
    }

    @Test
    fun two_extreme_tall_boxes_below_threshold_falls_to_main_judge() {
        // 2 个极细长 box（< STRONG_VERTICAL_MIN_COUNT=3）→ 走主判据。h/w=10 也是 portrait
        // → portrait 占比 100% → VERTICAL_RTL（source: heuristic-boxes 不是 strong-vertical）
        val rects = listOf(
            IntRect(50, 0, 100, 500),
            IntRect(150, 0, 200, 500)
        )
        val r = classifyByGeometry(rects, 400, 600)
        assertEquals(TextOrientation.VERTICAL_RTL, r.orientation)
        assertEquals("heuristic-boxes", r.source)
    }

    @Test
    fun strong_vertical_overrides_mixed_with_horizontal_ui() {
        // 真实回归：屏译截图常含状态栏 / 标题 / 按钮等横排 UI box 稀释主判据。
        // 实测一张图：10 列竖排繁中（h/w ≈ 8-10）+ 18 个横排 UI box → portrait 占比 38%，
        // 主判据触发不了。STRONG_VERTICAL 兜底应直接返回 VERTICAL_RTL。
        val verticalCols = (0 until 10).map { i ->
            IntRect(left = 50 + i * 60, top = 200, right = 100 + i * 60, bottom = 700)
        }
        val horizontalUi = listOf(
            // 状态栏 / 标题栏 / 按钮（横排 UI）
            IntRect(0, 0, 200, 50),
            IntRect(220, 0, 400, 50),
            IntRect(420, 0, 600, 50),
            IntRect(0, 60, 800, 100),
            IntRect(0, 800, 800, 850),
            IntRect(0, 900, 800, 950),
            IntRect(0, 950, 200, 1000),
            IntRect(220, 950, 400, 1000)
        )
        val r = classifyByGeometry(verticalCols + horizontalUi, 800, 1000)
        assertEquals(TextOrientation.VERTICAL_RTL, r.orientation)
        assertEquals("heuristic-strong-vertical", r.source)
    }

    @Test
    fun mixed_horizontal_and_vertical_returns_unknown() {
        // 横竖各占一半：portrait 占比 50%，landscape 占比 50%，无任何一方主导 → UNKNOWN
        val rects = listOf(
            IntRect(0, 0, 400, 50),    // 横
            IntRect(0, 80, 400, 130),  // 横
            IntRect(0, 200, 40, 600),  // 竖
            IntRect(80, 200, 120, 600) // 竖
        )
        val r = classifyByGeometry(rects, 500, 700)
        // portrait 占比 50% 但 landscape 占比 50% 时 when 分支两个 condition 都 false → UNKNOWN
        assertEquals(TextOrientation.UNKNOWN, r.orientation)
    }
}
