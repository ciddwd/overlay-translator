package com.gameocr.app.translate

import android.graphics.Rect
import com.gameocr.app.data.Settings
import com.gameocr.app.ocr.TextBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossLineContextTranslationTest {
    @Test
    fun planner_joinsJapaneseHardWrapsButKeepsSentenceAndListBoundaries() {
        val blocks = listOf(
            sourceBlock("メールでお知", 10, 10, 220, 30),
            sourceBlock("らせしています。", 10, 31, 170, 51),
            sourceBlock("1．初めてログインした場", 10, 60, 230, 80),
            sourceBlock("合", 10, 81, 30, 101),
            sourceBlock("2．一定期間以上ログインしていない場合", 10, 102, 280, 122),
        )

        val units = planCrossLineSourceUnits(blocks, "ja")

        assertEquals(3, units.size)
        assertEquals(listOf(0, 1), units[0].blockIndexes)
        assertEquals("メールでお知らせしています。", units[0].sourceText)
        assertEquals(listOf(2, 3), units[1].blockIndexes)
        assertEquals("1．初めてログインした場合", units[1].sourceText)
        assertEquals(listOf(4), units[2].blockIndexes)
    }

    @Test
    fun planner_joinsEnglishInversionWithAWordBoundary() {
        val blocks = listOf(
            sourceBlock("Only after the alarm had sounded", 10, 10, 310, 30),
            sourceBlock("did the crew evacuate.", 10, 31, 230, 51),
        )

        val units = planCrossLineSourceUnits(blocks, "en")

        assertEquals(1, units.size)
        assertEquals(
            "Only after the alarm had sounded did the crew evacuate.",
            units.single().sourceText,
        )
    }

    @Test
    fun reflow_preservesAllTranslatedTextAcrossOriginalBoxes() {
        val blocks = listOf(
            sourceBlock("Only after the alarm had sounded", 10, 10, 310, 30),
            sourceBlock("did the crew evacuate.", 10, 31, 230, 51),
        )
        val unit = planCrossLineSourceUnits(blocks, "en").single()
        val displayBlocks = blocks.map { block ->
            TextBlock(
                text = block.text,
                boundingBox = Rect(block.left, block.top, block.right, block.bottom),
            )
        }

        val chunks = reflowCrossLineTranslation(
            translatedText = "直到警报响起后，船员们才撤离。",
            unit = unit,
            blocks = displayBlocks,
            targetLanguageTag = "zh-CN",
        )

        assertEquals(2, chunks.size)
        assertTrue(chunks.all(String::isNotBlank))
        assertEquals("直到警报响起后，船员们才撤离。", chunks.joinToString(""))
    }

    @Test
    fun enablement_developerDiagnosticOptOutIsGatedAndMergeStillTakesPrecedence() {
        data class Case(
            val name: String,
            val developerOptionsEnabled: Boolean,
            val disableCrossLineContextTranslation: Boolean,
            val mergeAdjacentBlocks: Boolean,
            val expected: Boolean,
        )

        assertFalse(Settings().disableCrossLineContextTranslation)
        listOf(
            Case("normal mode always enables context", false, false, false, true),
            Case("hidden diagnostic value cannot disable normal mode", false, true, false, true),
            Case("developer mode keeps context by default", true, false, false, true),
            Case("developer diagnostic can disable context", true, true, false, false),
            Case("merged blocks do not run the context planner", false, false, true, false),
            Case("merge still wins in developer mode", true, false, true, false),
        ).forEach { case ->
            val enabled = crossLineContextTranslationEnabled(
                developerOptionsEnabled = case.developerOptionsEnabled,
                disableCrossLineContextTranslation = case.disableCrossLineContextTranslation,
            )
            assertEquals(
                case.name,
                case.expected,
                shouldUseCrossLineContextTranslation(
                    enabled = enabled,
                    mergeAdjacentBlocks = case.mergeAdjacentBlocks,
                ),
            )
        }
    }

    @Test
    fun planner_realScreenshotGeometry_keepsSevenSemanticGroupsAndTwoDistantControls() {
        val blocks = listOf(
            sourceBlock("セキュリティ対策の一環として、新しい環境から", 96, 1153, 1298, 1220),
            sourceBlock("のログイン操作を検知した場合に、メールでお知", 95, 1222, 1299, 1281),
            sourceBlock("らせしています。", 97, 1284, 500, 1349),
            sourceBlock("下記の場合にメールが配信される場合があります", 93, 1340, 1301, 1416),
            sourceBlock("ので、ご確認をお願いします。", 93, 1410, 842, 1476),
            sourceBlock("1．（機種変更後も含む）初めてログインした場", 93, 1473, 1302, 1540),
            sourceBlock("合", 95, 1542, 158, 1607),
            sourceBlock("2．一定期間以上、ログインしていない場合", 96, 1603, 1189, 1670),
            sourceBlock("3.", 101, 1668, 197, 1724),
            sourceBlock("家計簿アプリ等のサービスを使用している場", 195, 1666, 1301, 1733),
            sourceBlock("合", 95, 1729, 155, 1797),
            sourceBlock("4．スマートフォンからログインした場合（IPア", 96, 1793, 1292, 1860),
            sourceBlock("ドレスが変更される場合があるため)", 101, 1862, 994, 1920),
            sourceBlock("5．接続しているプロバイダが、IPアドレスを定", 96, 1923, 1295, 1990),
            sourceBlock("期的に変更する場合", 93, 1987, 591, 2052),
            sourceBlock("业", 940, 2948, 1007, 3014),
            sourceBlock("2", 1259, 2931, 1364, 3031),
        )

        val units = planCrossLineSourceUnits(blocks, "ja")

        assertEquals(units.joinToString { "${it.blockIndexes}:${it.sourceText}" }, 9, units.size)
        assertEquals(
            listOf(
                listOf(0, 1, 2),
                listOf(3, 4),
                listOf(5, 6),
                listOf(7),
                listOf(8, 9, 10),
                listOf(11, 12),
                listOf(13, 14),
                listOf(15),
                listOf(16),
            ),
            units.map { it.blockIndexes },
        )
        assertTrue(units[0].sourceText.contains("メールでお知らせしています。"))
        assertTrue(units[4].sourceText.startsWith("3.家計簿"))
    }

    private fun sourceBlock(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = CrossLineSourceBlock(
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
    )
}
