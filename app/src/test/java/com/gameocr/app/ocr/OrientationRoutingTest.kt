package com.gameocr.app.ocr

import com.gameocr.app.data.OcrEngineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 文本方向 → OCR 引擎路由决策表测试。覆盖 [OrientationRouting.resolveEngine] 的所有分支。
 *
 * 测试重点：
 *  1. (VERTICAL_RTL, ja) 在 manga 已下载 / 未下载 / 百度配 / 不配 4 种组合下的路由
 *  2. (VERTICAL_RTL, zh-*) 绝不路由到 manga-ocr（防幻觉日文 token）
 *  3. (VERTICAL_RTL, auto) 假定日漫场景
 *  4. 早退：userEngine 已经是目标引擎时返回 null
 *  5. (STACKED / VERTICAL_LTR / HORIZONTAL_* / UNKNOWN) Phase 1 全部返回 null
 */
class OrientationRoutingTest {

    @Test
    fun resolve_engine_table_driven_core_cases() {
        data class Case(
            val name: String,
            val orientation: TextOrientation,
            val lang: String,
            val userEngine: OcrEngineKind,
            val hasManga: Boolean,
            val baiduConfigured: Boolean,
            val expected: OcrEngineKind?
        )

        val cases = listOf(
            Case(
                name = "ja-vertical-prefers-manga",
                orientation = TextOrientation.VERTICAL_RTL,
                lang = "ja",
                userEngine = OcrEngineKind.ML_KIT_AUTO,
                hasManga = true,
                baiduConfigured = false,
                expected = OcrEngineKind.MANGA_OCR_JA
            ),
            Case(
                name = "ja-vertical-without-manga-does-not-fall-back-to-baidu",
                orientation = TextOrientation.VERTICAL_RTL,
                lang = "ja-JP",
                userEngine = OcrEngineKind.ML_KIT_AUTO,
                hasManga = false,
                baiduConfigured = true,
                expected = null
            ),
            Case(
                name = "zh-vertical-no-baidu-falls-back-to-mlkit-chinese",
                orientation = TextOrientation.VERTICAL_RTL,
                lang = "zh-TW",
                userEngine = OcrEngineKind.ML_KIT_AUTO,
                hasManga = true,
                baiduConfigured = false,
                expected = OcrEngineKind.ML_KIT_CHINESE
            ),
            Case(
                name = "zh-vertical-already-on-mlkit-chinese-does-not-rerun",
                orientation = TextOrientation.VERTICAL_RTL,
                lang = "zh-Hant",
                userEngine = OcrEngineKind.ML_KIT_CHINESE,
                hasManga = true,
                baiduConfigured = false,
                expected = null
            ),
            Case(
                name = "zh-vertical-uses-offline-mlkit-even-when-baidu-configured",
                orientation = TextOrientation.VERTICAL_RTL,
                lang = "zh-CN",
                userEngine = OcrEngineKind.ML_KIT_AUTO,
                hasManga = true,
                baiduConfigured = true,
                expected = OcrEngineKind.ML_KIT_CHINESE
            ),
            Case(
                name = "explicit-paddle-keeps-paddle-even-when-baidu-configured",
                orientation = TextOrientation.VERTICAL_RTL,
                lang = "zh-TW",
                userEngine = OcrEngineKind.PADDLE_ONNX,
                hasManga = true,
                baiduConfigured = true,
                expected = null
            ),
            Case(
                name = "explicit-umi-keeps-local-http-ocr-for-chinese-vertical",
                orientation = TextOrientation.VERTICAL_RTL,
                lang = "zh-TW",
                userEngine = OcrEngineKind.UMI_OCR,
                hasManga = true,
                baiduConfigured = true,
                expected = null
            ),
            Case(
                name = "explicit-luna-keeps-local-http-ocr-for-chinese-vertical",
                orientation = TextOrientation.VERTICAL_RTL,
                lang = "zh-TW",
                userEngine = OcrEngineKind.LUNA_OCR,
                hasManga = true,
                baiduConfigured = true,
                expected = null
            ),
            Case(
                name = "stacked-no-phase1-route",
                orientation = TextOrientation.STACKED,
                lang = "en",
                userEngine = OcrEngineKind.ML_KIT_LATIN,
                hasManga = true,
                baiduConfigured = true,
                expected = null
            ),
            Case(
                name = "horizontal-no-route",
                orientation = TextOrientation.HORIZONTAL_LTR,
                lang = "ja",
                userEngine = OcrEngineKind.ML_KIT_AUTO,
                hasManga = true,
                baiduConfigured = true,
                expected = null
            )
        )

        cases.forEach { case ->
            val r = OrientationRouting.resolveEngine(
                orientation = case.orientation,
                sourceLangBcp47 = case.lang,
                userEngine = case.userEngine,
                hasMangaOcr = case.hasManga,
                baiduConfigured = case.baiduConfigured
            )
            assertEquals(case.name, case.expected, r)
        }
    }

    @Test
    fun vertical_rtl_japanese_with_manga_installed_routes_to_manga() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "ja",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = true,
            baiduConfigured = false
        )
        assertEquals(OcrEngineKind.MANGA_OCR_JA, r)
    }

    @Test
    fun vertical_rtl_japanese_when_user_already_on_manga_returns_null() {
        // 早退：用户已经在 manga，避免无意义重跑
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "ja",
            userEngine = OcrEngineKind.MANGA_OCR_JA,
            hasMangaOcr = true,
            baiduConfigured = true
        )
        assertNull(r)
    }

    @Test
    fun vertical_rtl_japanese_without_manga_but_baidu_configured_keeps_user_engine() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "ja",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = false,
            baiduConfigured = true
        )
        assertNull(r)
    }

    @Test
    fun vertical_rtl_japanese_no_manga_no_baidu_returns_null() {
        // 都没配 → Phase 1 无可用兜底，保留用户原引擎
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "ja",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = false,
            baiduConfigured = false
        )
        assertNull(r)
    }

    @Test
    fun vertical_rtl_traditional_chinese_with_baidu_routes_to_offline_mlkit() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "zh-TW",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = true,
            baiduConfigured = true
        )
        assertEquals(OcrEngineKind.ML_KIT_CHINESE, r)
    }

    @Test
    fun vertical_rtl_simplified_chinese_with_baidu_routes_to_offline_mlkit() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "zh-CN",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = false,
            baiduConfigured = true
        )
        assertEquals(OcrEngineKind.ML_KIT_CHINESE, r)
    }

    @Test
    fun vertical_rtl_chinese_when_user_explicitly_chose_paddle_returns_null() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "zh-TW",
            userEngine = OcrEngineKind.PADDLE_ONNX,
            hasMangaOcr = true,
            baiduConfigured = true
        )
        assertNull(r)
    }

    @Test
    fun vertical_rtl_chinese_without_baidu_routes_to_mlkit_chinese_not_manga_ocr() {
        // 关键防御：manga-ocr 喂中文会幻觉日文 token（kha-white/manga-ocr README 明示），
        // 即使 manga 已下载且 baidu 未配，也绝不能走 manga
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "zh-TW",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = true,
            baiduConfigured = false
        )
        assertEquals(OcrEngineKind.ML_KIT_CHINESE, r)
    }

    @Test
    fun vertical_rtl_chinese_when_user_already_on_mlkit_chinese_returns_null() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "zh-Hant",
            userEngine = OcrEngineKind.ML_KIT_CHINESE,
            hasMangaOcr = true,
            baiduConfigured = false
        )
        assertNull(r)
    }

    @Test
    fun vertical_rtl_chinese_when_user_already_on_baidu_returns_null() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "zh-CN",
            userEngine = OcrEngineKind.BAIDU,
            hasMangaOcr = false,
            baiduConfigured = true
        )
        assertNull(r)
    }

    @Test
    fun vertical_rtl_auto_lang_assumes_japanese_manga() {
        // auto + 竖排 RTL 的最常见场景是日漫，优先 manga-ocr
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "auto",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = true,
            baiduConfigured = false
        )
        assertEquals(OcrEngineKind.MANGA_OCR_JA, r)
    }

    @Test
    fun vertical_rtl_other_language_not_routed() {
        // 韩文竖排不在 Phase 1 路由范围（ML Kit 韩文也支持竖排）
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "ko",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = true,
            baiduConfigured = true
        )
        assertNull(r)
    }

    @Test
    fun stacked_returns_null_phase1() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.STACKED,
            sourceLangBcp47 = "en",
            userEngine = OcrEngineKind.ML_KIT_LATIN,
            hasMangaOcr = true,
            baiduConfigured = true
        )
        assertNull(r)
    }

    @Test
    fun horizontal_ltr_returns_null() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.HORIZONTAL_LTR,
            sourceLangBcp47 = "en",
            userEngine = OcrEngineKind.ML_KIT_LATIN,
            hasMangaOcr = true,
            baiduConfigured = true
        )
        assertNull(r)
    }

    @Test
    fun vertical_ltr_returns_null() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_LTR,
            sourceLangBcp47 = "mn",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = true,
            baiduConfigured = true
        )
        assertNull(r)
    }

    @Test
    fun unknown_returns_null() {
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.UNKNOWN,
            sourceLangBcp47 = "ja",
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = true,
            baiduConfigured = true
        )
        assertNull(r)
    }

    @Test
    fun case_insensitive_lang_handling() {
        // resolveEngine 内部 lowercase 处理，sourceLangBcp47 大小写不敏感
        val r = OrientationRouting.resolveEngine(
            orientation = TextOrientation.VERTICAL_RTL,
            sourceLangBcp47 = "JA",  // 大写
            userEngine = OcrEngineKind.ML_KIT_AUTO,
            hasMangaOcr = true,
            baiduConfigured = false
        )
        assertEquals(OcrEngineKind.MANGA_OCR_JA, r)
    }
}
