package com.gameocr.app.ocr

/**
 * 把 DBNet 出的「文本行」级矩形聚类成「漫画气泡」级外接矩形，供 [MangaOcrEngine] 整气泡识别。
 *
 * manga-ocr 训练时见的是**整个气泡**的 crop（多列竖排在一个 224×224 输入里），不是单行文本。
 * 用 DBNet 行级 quads 直接逐个识别会浪费 manga-ocr 的跨行连读能力。
 *
 * 算法：膨胀矩形 + 并查集
 * 1. 每个输入矩形外扩 [gap] 像素（向四面）
 * 2. 任意两矩形相交（含相邻 gap 内）→ 并查集合并
 * 3. 合并完用每组的外接矩形 + 再外扩 [pad] 给 manga-ocr 留 padding，clamp 到画面边界
 *
 * 复杂度 O(N²)；DBNet 单页 N 通常 < 100，实测 < 5ms。
 *
 * 与 [tools/local_ocr_debug/run_manga_ocr.py:cluster_to_bubbles] 1:1 等价（Phase 0 PoC 验证过）。
 *
 * **API 设计**：用本地 [IntRect] 数据类而非 `android.graphics.Rect`，让单测直接走 JVM
 * （Android SDK 的 Rect 在 JVM 是 Stub，必须 Robolectric）。调用方 [MangaOcrEngine] 负责
 * 在算法前后做 Rect ↔ IntRect 转换。
 */
object BubbleClusterer {

    /** 不依赖 android.graphics.Rect 的纯 Kotlin 矩形（half-open: right/bottom 不含）。 */
    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class Bubble(
        /** 合并后的外接矩形 + [pad] 外扩 + clamp 到 (0..imgW, 0..imgH) */
        val rect: IntRect,
        /** 原 [rects] 数组中属于本气泡的索引（按输入顺序） */
        val memberIndices: List<Int>
    )

    /**
     * @param rects DBNet 检测出的轴对齐文本行矩形列表
     * @param imgW 原图宽度（用于 rect clamp）
     * @param imgH 原图高度
     * @param pad 合并后 bubble rect 向四面外扩的像素数，给 manga-ocr 留 padding
     * @param gap 两矩形外扩 [gap] 后仍相交则视为同一气泡；越大越激进
     * @return 按 (top, left) 排序的 bubble 列表，每个 rect 已 clamp 到画面内
     */
    fun cluster(
        rects: List<IntRect>,
        imgW: Int,
        imgH: Int,
        pad: Int = 12,
        gap: Int = 18
    ): List<Bubble> {
        val n = rects.size
        if (n == 0) return emptyList()

        // 每个矩形向四面外扩 gap 像素，用来判 "邻接"
        val inflated = rects.map { r ->
            IntRect(r.left - gap, r.top - gap, r.right + gap, r.bottom + gap)
        }

        // 并查集
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var cur = x
            while (parent[cur] != cur) {
                parent[cur] = parent[parent[cur]]  // 路径压缩
                cur = parent[cur]
            }
            return cur
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        // 两两判相交（含邻接）
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (rectsOverlap(inflated[i], inflated[j])) union(i, j)
            }
        }

        // 按 root 分组
        val groups = HashMap<Int, MutableList<Int>>(n)
        for (i in 0 until n) {
            groups.getOrPut(find(i)) { mutableListOf() }.add(i)
        }

        // 每组合并到外接矩形 + pad + clamp
        val bubbles = groups.values.map { members ->
            var l = Int.MAX_VALUE; var t = Int.MAX_VALUE
            var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
            for (idx in members) {
                val rc = rects[idx]
                if (rc.left < l) l = rc.left
                if (rc.top < t) t = rc.top
                if (rc.right > r) r = rc.right
                if (rc.bottom > b) b = rc.bottom
            }
            val rect = IntRect(
                (l - pad).coerceAtLeast(0),
                (t - pad).coerceAtLeast(0),
                (r + pad).coerceAtMost(imgW),
                (b + pad).coerceAtMost(imgH)
            )
            Bubble(rect = rect, memberIndices = members.toList())
        }

        // 按 (top, left) 稳定排序，便于阅读 / 日志
        return bubbles.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    /** Android Rect 的 intersects 严格不允许 touch；这里允许相切（边界共享视为相交）。 */
    private fun rectsOverlap(a: IntRect, b: IntRect): Boolean =
        a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom
}
