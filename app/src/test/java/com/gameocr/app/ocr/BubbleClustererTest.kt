package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BubbleClusterer 单元测试（纯 JVM，无 Android SDK 依赖）。6 场景覆盖：
 * 空 / 单 / 远距 / 相邻 / 链式传递 / 边界 clamp / 排序。
 */
class BubbleClustererTest {

    @Test
    fun empty_input_returns_empty() {
        val result = BubbleClusterer.cluster(emptyList(), imgW = 100, imgH = 100)
        assertEquals(emptyList<BubbleClusterer.Bubble>(), result)
    }

    @Test
    fun single_rect_becomes_single_bubble_with_pad_and_no_overflow() {
        val rects = listOf(IntRect(10, 20, 30, 40))
        val result = BubbleClusterer.cluster(rects, imgW = 200, imgH = 200, pad = 12, gap = 18)
        assertEquals(1, result.size)
        val b = result[0]
        assertEquals(listOf(0), b.memberIndices)
        // pad=12 → 外扩到 (-2, 8, 42, 52)，clamp 到 (0, 8, 42, 52)
        assertEquals(IntRect(0, 8, 42, 52), b.rect)
    }

    @Test
    fun far_apart_rects_become_two_bubbles() {
        // 两个 box gap=18，但相距 100px，外扩后仍不相交
        val rects = listOf(
            IntRect(0, 0, 20, 20),
            IntRect(150, 150, 170, 170)
        )
        val result = BubbleClusterer.cluster(rects, imgW = 300, imgH = 300, pad = 0, gap = 18)
        assertEquals(2, result.size)
    }

    @Test
    fun adjacent_rects_merge_into_single_bubble() {
        // 两个 box 水平间隔 15px，gap=18 外扩后相交 → 合并
        val rects = listOf(
            IntRect(0, 0, 20, 20),
            IntRect(35, 0, 55, 20)
        )
        val result = BubbleClusterer.cluster(rects, imgW = 200, imgH = 200, pad = 0, gap = 18)
        assertEquals(1, result.size)
        val b = result[0]
        // 外接矩形 (0,0,55,20)，pad=0，clamp 不生效
        assertEquals(IntRect(0, 0, 55, 20), b.rect)
        assertTrue(b.memberIndices.containsAll(listOf(0, 1)))
    }

    @Test
    fun transitive_chain_merges_via_union_find() {
        // A-B 相邻、B-C 相邻、A-C 远距 → 并查集传递性应让三个都归一
        val rects = listOf(
            IntRect(0, 0, 20, 20),     // A
            IntRect(35, 0, 55, 20),    // B（与 A 间隔 15）
            IntRect(70, 0, 90, 20)     // C（与 B 间隔 15；与 A 间隔 50，A-C 直接不相邻）
        )
        val result = BubbleClusterer.cluster(rects, imgW = 200, imgH = 200, pad = 0, gap = 18)
        assertEquals(1, result.size)
        assertEquals(IntRect(0, 0, 90, 20), result[0].rect)
    }

    @Test
    fun rect_touching_edge_clamps_to_image_bounds() {
        // 紧贴右下边的 box，pad=20 外扩后越界 → 应 clamp 到 (imgW, imgH)
        val rects = listOf(IntRect(190, 190, 200, 200))
        val result = BubbleClusterer.cluster(rects, imgW = 200, imgH = 200, pad = 20, gap = 18)
        assertEquals(1, result.size)
        val b = result[0]
        assertEquals(170, b.rect.left)     // 190-20
        assertEquals(170, b.rect.top)
        assertEquals(200, b.rect.right)    // clamp 不越界
        assertEquals(200, b.rect.bottom)
    }

    @Test
    fun result_sorted_by_top_then_left() {
        // 输入乱序，输出应按 (top, left) 升序
        val rects = listOf(
            IntRect(100, 100, 120, 120),   // 下
            IntRect(0, 0, 20, 20),         // 上
            IntRect(50, 0, 70, 20)         // 上但更右
        )
        val result = BubbleClusterer.cluster(rects, imgW = 300, imgH = 300, pad = 0, gap = 5)
        assertEquals(3, result.size)
        // 排序后第一个应是 (0,0)，第二个 (50,0)，第三个 (100,100)
        assertEquals(0, result[0].rect.top)
        assertEquals(0, result[0].rect.left)
        assertEquals(0, result[1].rect.top)
        assertEquals(50, result[1].rect.left)
        assertEquals(100, result[2].rect.top)
    }
}
