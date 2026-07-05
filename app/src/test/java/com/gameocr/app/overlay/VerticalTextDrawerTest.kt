package com.gameocr.app.overlay

import com.gameocr.app.overlay.VerticalTextDrawer.GlyphKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 竖排文本分类单元测试。
 *
 * 只测 [VerticalTextDrawer.classify] 纯函数——其它 measure/draw 涉及 Paint/Canvas，
 * 在 src/test 下走 stub 不可用，需要真机端到端验证。
 *
 * 测试设计：W3C CSS Writing Modes 4 §4 + Unicode Vertical_Orientation 属性表常用子集。
 */
class VerticalTextDrawerTest {

    @Test
    fun classify_table_driven_common_codepoints() {
        val cases = listOf(
            "cjk" to (0x56FD to GlyphKind.UPRIGHT),
            "hiragana" to (0x3042 to GlyphKind.UPRIGHT),
            "katakana" to (0x30A2 to GlyphKind.UPRIGHT),
            "hangul" to (0xD55C to GlyphKind.UPRIGHT),
            "emoji" to (0x1F389 to GlyphKind.UPRIGHT),
            "ideographic-full-stop" to (0x3002 to GlyphKind.PUNCT_TOP_RIGHT),
            "ideographic-comma" to (0x3001 to GlyphKind.PUNCT_TOP_RIGHT),
            "fullwidth-comma" to (0xFF0C to GlyphKind.PUNCT_TOP_RIGHT),
            "corner-bracket" to (0x300C to GlyphKind.ROTATE_90_CW),
            "fullwidth-paren" to (0xFF08 to GlyphKind.ROTATE_90_CW),
            "em-dash" to (0x2014 to GlyphKind.ROTATE_90_CW),
            "ascii-letter" to (0x41 to GlyphKind.ROTATE_90_CW),
            "ascii-digit" to (0x35 to GlyphKind.ROTATE_90_CW),
            "ascii-punctuation" to (0x3F to GlyphKind.ROTATE_90_CW),
            "control-fallback" to (0x09 to GlyphKind.UPRIGHT)
        )

        cases.forEach { (name, pair) ->
            val (codePoint, expected) = pair
            assertEquals(name, expected, VerticalTextDrawer.classify(codePoint))
        }
    }

    // ===== UPRIGHT（CJK 主体保持正立） =====

    @Test
    fun cjk_kanji_is_upright() {
        // 国 U+56FD（CJK Unified）
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0x56FD))
        // 中 U+4E2D
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0x4E2D))
        // 漢 U+6F22（繁体）
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0x6F22))
    }

    @Test
    fun japanese_hiragana_katakana_is_upright() {
        // あ U+3042（hiragana）
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0x3042))
        // ア U+30A2（katakana）
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0x30A2))
        // ン U+30F3（katakana 长尾）
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0x30F3))
    }

    @Test
    fun korean_hangul_is_upright() {
        // 한 U+D55C
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0xD55C))
    }

    @Test
    fun emoji_falls_through_to_upright() {
        // 🎉 U+1F389（虽然没人在 OCR 译文里见到，但默认分支应该兜底）
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0x1F389))
    }

    // ===== PUNCT_TOP_RIGHT（句末标点改右上） =====

    @Test
    fun japanese_full_stop_is_punct_top_right() {
        // 。U+3002
        assertEquals(GlyphKind.PUNCT_TOP_RIGHT, VerticalTextDrawer.classify(0x3002))
    }

    @Test
    fun japanese_comma_ideographic_is_punct_top_right() {
        // 、U+3001
        assertEquals(GlyphKind.PUNCT_TOP_RIGHT, VerticalTextDrawer.classify(0x3001))
    }

    @Test
    fun fullwidth_comma_is_punct_top_right() {
        // ，U+FF0C
        assertEquals(GlyphKind.PUNCT_TOP_RIGHT, VerticalTextDrawer.classify(0xFF0C))
        // ．U+FF0E
        assertEquals(GlyphKind.PUNCT_TOP_RIGHT, VerticalTextDrawer.classify(0xFF0E))
    }

    // ===== ROTATE_90_CW（括号引号破折号 + 拉丁） =====

    @Test
    fun cjk_brackets_rotate_90() {
        // 《 U+300A
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x300A))
        // 》 U+300B
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x300B))
        // 「 U+300C
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x300C))
        // 」 U+300D
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x300D))
        // 『 U+300E
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x300E))
        // 〈 U+3008
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x3008))
        // 〉 U+3009
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x3009))
    }

    @Test
    fun fullwidth_parens_rotate_90() {
        // （ U+FF08
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0xFF08))
        // ） U+FF09
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0xFF09))
        // ｛ U+FF5B
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0xFF5B))
        // ｝ U+FF5D
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0xFF5D))
    }

    @Test
    fun em_dash_and_ellipsis_rotate_90() {
        // — U+2014 (em dash)
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x2014))
        // ― U+2015 (horizontal bar)
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x2015))
        // … U+2026 (horizontal ellipsis)
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x2026))
        // ～ U+FF5E (fullwidth tilde)
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0xFF5E))
    }

    @Test
    fun ascii_letters_rotate_90() {
        // A U+0041
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x41))
        // z U+007A
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x7A))
    }

    @Test
    fun ascii_digits_rotate_90() {
        // Phase 1.5 简化：数字按 sideways 兜底，tate-chu-yoko 留到后续
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x30)) // 0
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x35)) // 5
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x39)) // 9
    }

    @Test
    fun ascii_punctuation_rotate_90() {
        // ! U+0021
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x21))
        // ? U+003F
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x3F))
        // ~ U+007E
        assertEquals(GlyphKind.ROTATE_90_CW, VerticalTextDrawer.classify(0x7E))
    }

    // ===== 边界 =====

    @Test
    fun control_chars_are_upright_fallback() {
        // 控制字符（如 \t \r）落到 UPRIGHT 兜底分支——实际渲染时不会画出可见字形，
        // 但分类逻辑不应抛异常
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0x09)) // tab
        assertEquals(GlyphKind.UPRIGHT, VerticalTextDrawer.classify(0x0D)) // CR
    }
}
