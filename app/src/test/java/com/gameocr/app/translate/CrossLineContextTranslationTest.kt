package com.gameocr.app.translate

import android.graphics.Rect
import com.gameocr.app.data.Settings
import com.gameocr.app.ocr.TextBlock
import com.gameocr.app.ocr.TextRegionGranularity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossLineContextTranslationTest {
    @Test
    fun planner_regionBoundaryPolicy_isTableDriven() {
        data class Case(
            val name: String,
            val firstGranularity: TextRegionGranularity,
            val secondGranularity: TextRegionGranularity,
            val firstParentId: Int?,
            val secondParentId: Int?,
            val expectedUnitCount: Int,
        )

        listOf(
            Case(
                name = "legacy unknown blocks keep geometry behavior",
                firstGranularity = TextRegionGranularity.UNKNOWN,
                secondGranularity = TextRegionGranularity.UNKNOWN,
                firstParentId = null,
                secondParentId = null,
                expectedUnitCount = 1,
            ),
            Case(
                name = "separate bubbles never merge",
                firstGranularity = TextRegionGranularity.BUBBLE,
                secondGranularity = TextRegionGranularity.BUBBLE,
                firstParentId = 10,
                secondParentId = 10,
                expectedUnitCount = 2,
            ),
            Case(
                name = "lines in the same explicit parent may merge",
                firstGranularity = TextRegionGranularity.LINE,
                secondGranularity = TextRegionGranularity.LINE,
                firstParentId = 10,
                secondParentId = 10,
                expectedUnitCount = 1,
            ),
            Case(
                name = "lines in different parents stay separate",
                firstGranularity = TextRegionGranularity.LINE,
                secondGranularity = TextRegionGranularity.LINE,
                firstParentId = 10,
                secondParentId = 11,
                expectedUnitCount = 2,
            ),
            Case(
                name = "lines without a known parent stay separate",
                firstGranularity = TextRegionGranularity.LINE,
                secondGranularity = TextRegionGranularity.LINE,
                firstParentId = null,
                secondParentId = null,
                expectedUnitCount = 2,
            ),
            Case(
                name = "known and unknown semantics do not cross",
                firstGranularity = TextRegionGranularity.LINE,
                secondGranularity = TextRegionGranularity.UNKNOWN,
                firstParentId = 10,
                secondParentId = null,
                expectedUnitCount = 2,
            ),
        ).forEach { case ->
            val units = planCrossLineSourceUnits(
                blocks = listOf(
                    sourceBlock(
                        text = "first",
                        left = 10,
                        top = 10,
                        right = 200,
                        bottom = 30,
                        regionGranularity = case.firstGranularity,
                        parentRegionId = case.firstParentId,
                    ),
                    sourceBlock(
                        text = "second",
                        left = 10,
                        top = 31,
                        right = 200,
                        bottom = 51,
                        regionGranularity = case.secondGranularity,
                        parentRegionId = case.secondParentId,
                    ),
                ),
                sourceLanguageTag = "en",
            )

            assertEquals(case.name, case.expectedUnitCount, units.size)
        }
    }

    @Test
    fun planner_textBlockBoundaryMetadata_reachesSourcePlanner() {
        val blocks = listOf(
            TextBlock(
                text = "first bubble",
                boundingBox = Rect(10, 10, 200, 30),
                regionId = 1,
                regionGranularity = TextRegionGranularity.BUBBLE,
            ),
            TextBlock(
                text = "second bubble",
                boundingBox = Rect(10, 31, 200, 51),
                regionId = 2,
                regionGranularity = TextRegionGranularity.BUBBLE,
            ),
        )

        val units = planCrossLineTranslationUnits(blocks, sourceLanguageTag = "en")

        assertEquals(2, units.size)
        assertEquals(listOf(listOf(0), listOf(1)), units.map { it.blockIndexes })
    }

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
    fun enablement_userSettingDefaultsOnAndMergeStillTakesPrecedence() {
        data class Case(
            val name: String,
            val disableCrossLineContextTranslation: Boolean,
            val mergeAdjacentBlocks: Boolean,
            val expected: Boolean,
        )

        assertFalse(Settings().disableCrossLineContextTranslation)
        listOf(
            Case("default enables cross-context translation", false, false, true),
            Case("user can disable cross-context translation", true, false, false),
            Case("merged blocks do not run the context planner", false, true, false),
            Case("disabled remains off when blocks are already merged", true, true, false),
        ).forEach { case ->
            val enabled = crossLineContextTranslationEnabled(
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
        regionGranularity: TextRegionGranularity = TextRegionGranularity.UNKNOWN,
        parentRegionId: Int? = null,
    ) = CrossLineSourceBlock(
        text = text,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        parentRegionId = parentRegionId,
        regionGranularity = regionGranularity,
    )
}
