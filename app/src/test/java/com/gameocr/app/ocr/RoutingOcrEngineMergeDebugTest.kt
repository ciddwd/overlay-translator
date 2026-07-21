package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingOcrEngineMergeDebugTest {

    @Test
    fun vertical_column_adjacency_debug_table_driven_geometry() {
        data class Case(
            val name: String,
            val first: MergeDebugRect,
            val second: MergeDebugRect,
            val expectedRight: MergeDebugRect,
            val expectedLeft: MergeDebugRect,
            val expectedColumnWidth: Int,
            val expectedHorizontalGap: Int,
            val expectedOverlapHeight: Int,
            val expectedOverlapRatio: Float
        )

        val cases = listOf(
            Case(
                name = "adjacent-right-then-left",
                first = MergeDebugRect(100, 10, 150, 210),
                second = MergeDebugRect(45, 50, 95, 190),
                expectedRight = MergeDebugRect(100, 10, 150, 210),
                expectedLeft = MergeDebugRect(45, 50, 95, 190),
                expectedColumnWidth = 50,
                expectedHorizontalGap = 5,
                expectedOverlapHeight = 140,
                expectedOverlapRatio = 1.0f
            ),
            Case(
                name = "reversed-input-still-normalizes-right-to-left",
                first = MergeDebugRect(45, 50, 95, 190),
                second = MergeDebugRect(100, 10, 150, 210),
                expectedRight = MergeDebugRect(100, 10, 150, 210),
                expectedLeft = MergeDebugRect(45, 50, 95, 190),
                expectedColumnWidth = 50,
                expectedHorizontalGap = 5,
                expectedOverlapHeight = 140,
                expectedOverlapRatio = 1.0f
            ),
            Case(
                name = "slightly-overlapping-columns-keep-negative-gap",
                first = MergeDebugRect(100, 10, 150, 210),
                second = MergeDebugRect(60, 30, 115, 180),
                expectedRight = MergeDebugRect(100, 10, 150, 210),
                expectedLeft = MergeDebugRect(60, 30, 115, 180),
                expectedColumnWidth = 50,
                expectedHorizontalGap = -15,
                expectedOverlapHeight = 150,
                expectedOverlapRatio = 1.0f
            ),
            Case(
                name = "no-vertical-overlap-reports-zero-ratio",
                first = MergeDebugRect(100, 10, 150, 60),
                second = MergeDebugRect(40, 100, 90, 160),
                expectedRight = MergeDebugRect(100, 10, 150, 60),
                expectedLeft = MergeDebugRect(40, 100, 90, 160),
                expectedColumnWidth = 50,
                expectedHorizontalGap = 10,
                expectedOverlapHeight = 0,
                expectedOverlapRatio = 0.0f
            )
        )

        cases.forEach { case ->
            val actual = verticalColumnAdjacencyDebug(case.first, case.second)
            assertEquals(case.name, case.expectedRight, actual.rightColumn)
            assertEquals(case.name, case.expectedLeft, actual.leftColumn)
            assertEquals(case.name, case.expectedColumnWidth, actual.columnWidth)
            assertEquals(case.name, case.expectedHorizontalGap, actual.horizontalGap)
            assertEquals(case.name, case.expectedOverlapHeight, actual.overlapHeight)
            assertEquals(case.name, case.expectedOverlapRatio, actual.overlapRatio, 0.0001f)
        }
    }

    @Test
    fun merge_debug_rect_clamps_empty_dimensions_for_threshold_math() {
        val rect = MergeDebugRect(left = 10, top = 20, right = 10, bottom = 20)

        assertEquals(1, rect.width)
        assertEquals(1, rect.height)
        assertEquals("(10,20,10,20)", rect.toLogString())
    }

    @Test
    fun vertical_column_merge_limits_use_observed_gaps_not_accumulated_group_width() {
        val stage1Rects = listOf(
            MergeDebugRect(997, 1327, 1046, 1797),
            MergeDebugRect(941, 1327, 993, 1519),
            MergeDebugRect(941, 1511, 990, 1658),
            MergeDebugRect(941, 1648, 993, 1844),
            MergeDebugRect(886, 1327, 938, 1754),
            MergeDebugRect(829, 1327, 886, 1982),
            MergeDebugRect(776, 1327, 825, 1755),
            MergeDebugRect(773, 1740, 828, 1845),
            MergeDebugRect(655, 1327, 704, 1975),
            MergeDebugRect(602, 1327, 651, 1980),
            MergeDebugRect(544, 1327, 598, 1968),
            MergeDebugRect(491, 1327, 540, 2026),
            MergeDebugRect(433, 1327, 486, 1757)
        )

        val limits = verticalColumnMergeLimits(stage1Rects, verticalGapRatio = 1.3f)

        assertEquals(52, limits.baseColumnWidth)
        assertTrue("normal 4-5px column gaps should still fit", limits.maxHorizontalGap >= 5f)
        assertTrue("69px logcat paragraph gap should be rejected", limits.maxHorizontalGap < 69f)
        assertEquals(39, limits.minOverlapHeight)
    }

    @Test
    fun vertical_column_merge_allowed_table_driven_logcat_regressions() {
        data class Case(
            val name: String,
            val first: MergeDebugRect,
            val second: MergeDebugRect,
            val expected: Boolean
        )

        val limits = VerticalColumnMergeLimits(
            baseColumnWidth = 52,
            maxHorizontalGap = 25f,
            minOverlapHeight = 39,
            gapSamples = listOf(4, 4, 4, 5)
        )
        val cases = listOf(
            Case(
                name = "normal-adjacent-columns-allowed",
                first = MergeDebugRect(655, 1327, 704, 1975),
                second = MergeDebugRect(602, 1327, 651, 1980),
                expected = true
            ),
            Case(
                name = "tiny-bullet-marker-rejected-despite-ratio-one",
                first = MergeDebugRect(909, 1851, 932, 1860),
                second = MergeDebugRect(773, 1327, 1046, 1982),
                expected = false
            ),
            Case(
                name = "separate-bubble-gap-rejected",
                first = MergeDebugRect(773, 1327, 1046, 1982),
                second = MergeDebugRect(433, 1327, 704, 2026),
                expected = false
            ),
            Case(
                name = "umi-adjacent-column-gap-sixteen-allowed-by-observed-limit",
                first = MergeDebugRect(527, 336, 738, 993),
                second = MergeDebugRect(471, 342, 511, 857),
                expected = true
            ),
            Case(
                name = "umi-adjacent-column-gap-fourteen-allowed-by-observed-limit",
                first = MergeDebugRect(186, 335, 338, 1039),
                second = MergeDebugRect(126, 340, 173, 764),
                expected = true
            )
        )

        cases.forEach { case ->
            val debug = verticalColumnAdjacencyDebug(case.first, case.second)
            val actual = verticalColumnMergeAllowed(debug, limits, horizontalOverlapRatio = 0.15f)
            if (case.expected) {
                assertTrue(case.name, actual)
            } else {
                assertFalse(case.name, actual)
            }
        }
    }

    @Test
    fun shouldDropVerticalOcrNoise_filtersLowConfidenceTinyAsciiOnlyBlocks() {
        data class Case(
            val name: String,
            val text: String,
            val confidence: Float,
            val rect: MergeDebugRect,
            val baseColumnWidth: Int,
            val expected: Boolean
        )

        val cases = listOf(
            Case(
                name = "umi-logcat-pt-noise",
                text = "PT",
                confidence = 0.269f,
                rect = MergeDebugRect(146, 747, 178, 785),
                baseColumnWidth = 47,
                expected = true
            ),
            Case(
                name = "umi-logcat-i01-noise",
                text = "I01",
                confidence = 0.228f,
                rect = MergeDebugRect(496, 854, 502, 859),
                baseColumnWidth = 47,
                expected = true
            ),
            Case(
                name = "normal-traditional-chinese-column-kept",
                text = "而給予我們動力的重要因素之一",
                confidence = 0.977f,
                rect = MergeDebugRect(352, 341, 393, 981),
                baseColumnWidth = 47,
                expected = false
            ),
            Case(
                name = "short-cjk-column-kept-even-if-low-confidence",
                text = "一員",
                confidence = 0.20f,
                rect = MergeDebugRect(126, 340, 173, 764),
                baseColumnWidth = 47,
                expected = false
            ),
            Case(
                name = "high-confidence-small-ascii-kept",
                text = "HP",
                confidence = 0.92f,
                rect = MergeDebugRect(146, 747, 178, 785),
                baseColumnWidth = 47,
                expected = false
            ),
            Case(
                name = "low-confidence-but-column-sized-ascii-kept",
                text = "OPEN",
                confidence = 0.30f,
                rect = MergeDebugRect(471, 342, 511, 857),
                baseColumnWidth = 47,
                expected = false
            )
        )

        cases.forEach { case ->
            val actual = shouldDropVerticalOcrNoise(
                text = case.text,
                confidence = case.confidence,
                rect = case.rect,
                baseColumnWidth = case.baseColumnWidth
            )
            if (case.expected) {
                assertTrue(case.name, actual)
            } else {
                assertFalse(case.name, actual)
            }
        }
    }

    @Test
    fun shouldDropPreDirectionOcrNoise_filtersLowConfidenceTinyAsciiBeforeOrientation() {
        data class Case(
            val name: String,
            val text: String,
            val confidence: Float,
            val rect: MergeDebugRect,
            val baseShortSide: Int,
            val expected: Boolean
        )

        val cases = listOf(
            Case(
                name = "umi-horizontal-misdetect-i-noise",
                text = "I",
                confidence = 0.290f,
                rect = MergeDebugRect(416, 466, 431, 495),
                baseShortSide = 94,
                expected = true
            ),
            Case(
                name = "umi-horizontal-misdetect-ti-noise",
                text = "TI",
                confidence = 0.124f,
                rect = MergeDebugRect(359, 537, 388, 557),
                baseShortSide = 94,
                expected = true
            ),
            Case(
                name = "umi-horizontal-misdetect-one-noise",
                text = "1",
                confidence = 0.145f,
                rect = MergeDebugRect(379, 773, 386, 780),
                baseShortSide = 94,
                expected = true
            ),
            Case(
                name = "umi-vertical-pt-noise",
                text = "PT",
                confidence = 0.269f,
                rect = MergeDebugRect(146, 747, 178, 785),
                baseShortSide = 47,
                expected = true
            ),
            Case(
                name = "low-confidence-cjk-kept-for-quality-gate",
                text = "\u6D66\u5340",
                confidence = 0.211f,
                rect = MergeDebugRect(318, 310, 549, 445),
                baseShortSide = 94,
                expected = false
            ),
            Case(
                name = "high-confidence-small-ascii-kept",
                text = "I",
                confidence = 0.91f,
                rect = MergeDebugRect(416, 466, 431, 495),
                baseShortSide = 94,
                expected = false
            ),
            Case(
                name = "longer-ascii-kept",
                text = "OPENAI",
                confidence = 0.120f,
                rect = MergeDebugRect(416, 466, 431, 495),
                baseShortSide = 94,
                expected = false
            ),
            Case(
                name = "large-low-confidence-ascii-kept",
                text = "TI",
                confidence = 0.124f,
                rect = MergeDebugRect(318, 310, 549, 445),
                baseShortSide = 94,
                expected = false
            )
        )

        cases.forEach { case ->
            val actual = shouldDropPreDirectionOcrNoise(
                text = case.text,
                confidence = case.confidence,
                rect = case.rect,
                baseShortSide = case.baseShortSide
            )
            if (case.expected) {
                assertTrue(case.name, actual)
            } else {
                assertFalse(case.name, actual)
            }
        }
    }

    @Test
    fun mergeVerticalTerminalPunctuation_tableDriven_attachesOnlyTerminalFullStops() {
        data class Case(
            val name: String,
            val columnText: String,
            val columnRect: MergeDebugRect = MergeDebugRect(421, 30, 481, 465),
            val punctuationText: String,
            val punctuationRect: MergeDebugRect,
            val punctuationConfidence: Float = 1f,
            val expectedAttached: Boolean,
            val expectedMergedText: String? = null,
        )

        val cases = listOf(
            Case(
                name = "logcat 7o full stop joins vertical column",
                columnText = "也曾經是其中的一員",
                punctuationText = "7o",
                punctuationRect = MergeDebugRect(448, 442, 475, 477),
                punctuationConfidence = 0.20f,
                expectedAttached = true,
                expectedMergedText = "也曾經是其中的一員。",
            ),
            Case(
                name = "logcat o full stop joins vertical column",
                columnText = "以期能夠取得優異的成績",
                columnRect = MergeDebugRect(761, 29, 824, 557),
                punctuationText = "o",
                punctuationRect = MergeDebugRect(795, 543, 815, 569),
                punctuationConfidence = 0.16f,
                expectedAttached = true,
                expectedMergedText = "以期能夠取得優異的成績。",
            ),
            Case(
                name = "already punctuated column does not duplicate full stop",
                columnText = "但部員們的士氣並不低落。",
                columnRect = MergeDebugRect(873, 30, 935, 564),
                punctuationText = "o",
                punctuationRect = MergeDebugRect(907, 543, 924, 565),
                punctuationConfidence = 0.16f,
                expectedAttached = true,
                expectedMergedText = "但部員們的士氣並不低落。",
            ),
            Case(
                name = "native ideographic full stop also joins",
                columnText = "也曾經是其中的一員",
                punctuationText = "。",
                punctuationRect = MergeDebugRect(448, 442, 475, 477),
                expectedAttached = true,
                expectedMergedText = "也曾經是其中的一員。",
            ),
            Case(
                name = "logcat low confidence 8 joins as geometric full stop",
                columnText = "以期能夠取得優異的成績",
                columnRect = MergeDebugRect(772, 47, 835, 578),
                punctuationText = "8",
                punctuationRect = MergeDebugRect(806, 560, 826, 587),
                punctuationConfidence = 0.112f,
                expectedAttached = true,
                expectedMergedText = "以期能夠取得優異的成績。",
            ),
            Case(
                name = "high confidence real 8 stays independent",
                columnText = "以期能夠取得優異的成績",
                columnRect = MergeDebugRect(772, 47, 835, 578),
                punctuationText = "8",
                punctuationRect = MergeDebugRect(806, 560, 826, 587),
                punctuationConfidence = 0.92f,
                expectedAttached = false,
            ),
            Case(
                name = "high confidence real o stays independent",
                columnText = "以期能夠取得優異的成績",
                columnRect = MergeDebugRect(772, 47, 835, 578),
                punctuationText = "o",
                punctuationRect = MergeDebugRect(806, 560, 826, 587),
                punctuationConfidence = 0.92f,
                expectedAttached = false,
            ),
            Case(
                name = "circle away from column end stays independent",
                columnText = "也曾經是其中的一員",
                punctuationText = "o",
                punctuationRect = MergeDebugRect(448, 300, 475, 335),
                punctuationConfidence = 0.16f,
                expectedAttached = false,
            ),
            Case(
                name = "circle outside column axis stays independent",
                columnText = "也曾經是其中的一員",
                punctuationText = "o",
                punctuationRect = MergeDebugRect(500, 442, 527, 477),
                punctuationConfidence = 0.16f,
                expectedAttached = false,
            ),
            Case(
                name = "normal ascii word is never normalized as punctuation",
                columnText = "也曾經是其中的一員",
                punctuationText = "go",
                punctuationRect = MergeDebugRect(448, 442, 475, 477),
                expectedAttached = false,
            ),
            Case(
                name = "normal sized ascii box stays independent",
                columnText = "也曾經是其中的一員",
                punctuationText = "o",
                punctuationRect = MergeDebugRect(430, 400, 480, 470),
                punctuationConfidence = 0.16f,
                expectedAttached = false,
            ),
            Case(
                name = "ascii column does not absorb circle",
                columnText = "OPENAI",
                punctuationText = "o",
                punctuationRect = MergeDebugRect(448, 442, 475, 477),
                punctuationConfidence = 0.16f,
                expectedAttached = false,
            ),
        )

        cases.forEach { case ->
            val blocks = listOf(
                VerticalTerminalPunctuationBlock(case.columnText, case.columnRect),
                VerticalTerminalPunctuationBlock(
                    case.punctuationText,
                    case.punctuationRect,
                    case.punctuationConfidence,
                ),
            )

            val result = verticalTerminalPunctuationAttachments(blocks, baseColumnWidth = 60)

            val expected = if (case.expectedAttached) mapOf(0 to listOf(1)) else emptyMap()
            assertEquals(case.name, expected, result)
            case.expectedMergedText?.let { expectedText ->
                assertEquals(case.name, expectedText, mergeVerticalTerminalPunctuationText(case.columnText))
            }
        }
    }

    @Test
    fun mergeVerticalTerminalPunctuation_logcatRegression_mergesThreePeriodBoxesIntoColumns() {
        val blocks = listOf(
            VerticalTerminalPunctuationBlock("也曾經是其中的一員", MergeDebugRect(421, 30, 481, 465)),
            VerticalTerminalPunctuationBlock("以期能夠取得優異的成績", MergeDebugRect(761, 29, 824, 557)),
            VerticalTerminalPunctuationBlock("但部員們的士氣並不低落。", MergeDebugRect(873, 30, 935, 564)),
            VerticalTerminalPunctuationBlock("7o", MergeDebugRect(448, 442, 475, 477), confidence = 0.20f),
            VerticalTerminalPunctuationBlock("o", MergeDebugRect(795, 543, 815, 569), confidence = 0.16f),
            VerticalTerminalPunctuationBlock("o", MergeDebugRect(907, 543, 924, 565), confidence = 0.16f),
        )

        val result = verticalTerminalPunctuationAttachments(blocks, baseColumnWidth = 60)

        assertEquals(
            mapOf(
                0 to listOf(3),
                1 to listOf(4),
                2 to listOf(5),
            ),
            result,
        )
    }

    @Test
    fun detectMergeOrientation_tableDriven_prefersStrongVerticalTextOverUiNoise() {
        data class Case(
            val name: String,
            val rects: List<MergeDebugRect>,
            val expectedOrientation: Orientation,
            val expectedReason: String,
            val expectedStrongVerticalCount: Int
        )

        val logcatMixedUiAndVerticalText = listOf(
            MergeDebugRect(82, 62, 213, 112),
            MergeDebugRect(462, 56, 666, 115),
            MergeDebugRect(890, 51, 1052, 118),
            MergeDebugRect(1114, 53, 1348, 118),
            MergeDebugRect(98, 232, 183, 300),
            MergeDebugRect(423, 192, 1007, 271),
            MergeDebugRect(654, 292, 779, 342),
            MergeDebugRect(439, 1689, 482, 2131),
            MergeDebugRect(490, 1689, 536, 2389),
            MergeDebugRect(539, 1685, 599, 2320),
            MergeDebugRect(603, 1689, 649, 2342),
            MergeDebugRect(657, 1689, 703, 2331),
            MergeDebugRect(776, 1689, 822, 2201),
            MergeDebugRect(831, 1689, 884, 2342),
            MergeDebugRect(882, 1685, 939, 2227),
            MergeDebugRect(943, 1689, 989, 2165),
            MergeDebugRect(998, 1689, 1044, 2201),
            MergeDebugRect(1200, 2557, 1425, 2610),
            MergeDebugRect(496, 2942, 578, 3031),
            MergeDebugRect(1210, 2950, 1286, 3029),
            MergeDebugRect(146, 3035, 222, 3085),
            MergeDebugRect(500, 3039, 579, 3089),
            MergeDebugRect(850, 3012, 939, 3094),
            MergeDebugRect(1204, 3034, 1286, 3088)
        )

        val cases = listOf(
            Case(
                name = "logcat vertical manga text with horizontal ui noise",
                rects = logcatMixedUiAndVerticalText,
                expectedOrientation = Orientation.VERTICAL,
                expectedReason = "strongVertical",
                expectedStrongVerticalCount = 10
            ),
            Case(
                name = "normal horizontal ui remains horizontal",
                rects = listOf(
                    MergeDebugRect(10, 10, 200, 50),
                    MergeDebugRect(10, 70, 260, 115),
                    MergeDebugRect(10, 130, 180, 175)
                ),
                expectedOrientation = Orientation.HORIZONTAL,
                expectedReason = "fallbackHorizontal",
                expectedStrongVerticalCount = 0
            )
        )

        cases.forEach { case ->
            val actual = detectMergeOrientation(case.rects)
            assertEquals(case.name, case.expectedOrientation, actual.orientation)
            assertEquals(case.name, case.expectedReason, actual.reason)
            assertEquals(case.name, case.expectedStrongVerticalCount, actual.strongVerticalCount)
        }
    }

    @Test
    fun wideVerticalParagraphGapAllowed_tableDriven_allowsOnlyTallMultiLineParagraphs() {
        data class Case(
            val name: String,
            val first: MergeDebugRect,
            val second: MergeDebugRect,
            val rightText: String,
            val leftText: String,
            val expected: Boolean
        )

        val limits = VerticalColumnMergeLimits(
            baseColumnWidth = 46,
            maxHorizontalGap = 35.5f,
            minOverlapHeight = 34,
            gapSamples = listOf(9, 4, 6, 73, 8, 4, 3, 8)
        )
        val fiveLineRight = listOf(
            "我所在的誠南高中棒球部",
            "雖然不能算是頂尖的隊",
            "但部具們的士氣並不低落。",
            "他們每天都全力以赴地努力訓練",
            "以期能夠取得優異的成績"
        ).joinToString("\n")
        val fiveLineLeft = listOf(
            "而給予我們動力的重要因素之一",
            "就是一直在比赛中為我們加油的",
            "誠南高中啦啦隊部的女孩子們。",
            "而且，我現在的女朋友，藤野沙織",
            "也曾經是其中的一員。"
        ).joinToString("\n")

        val cases = listOf(
            Case(
                name = "logcat gap seventy three between tall vertical paragraphs",
                first = MergeDebugRect(776, 1685, 1044, 2344),
                second = MergeDebugRect(439, 1685, 703, 2389),
                rightText = fiveLineRight,
                leftText = fiveLineLeft,
                expected = true
            ),
            Case(
                name = "same geometry but short ui labels remain blocked",
                first = MergeDebugRect(776, 1685, 1044, 2344),
                second = MergeDebugRect(439, 1685, 703, 2389),
                rightText = "删除",
                leftText = "编辑",
                expected = false
            ),
            Case(
                name = "wide gap still blocked even for paragraphs",
                first = MergeDebugRect(900, 1685, 1168, 2344),
                second = MergeDebugRect(439, 1685, 703, 2389),
                rightText = fiveLineRight,
                leftText = fiveLineLeft,
                expected = false
            ),
            Case(
                name = "low vertical overlap remains blocked",
                first = MergeDebugRect(776, 1685, 1044, 1900),
                second = MergeDebugRect(439, 2100, 703, 2389),
                rightText = fiveLineRight,
                leftText = fiveLineLeft,
                expected = false
            )
        )

        cases.forEach { case ->
            val debug = verticalColumnAdjacencyDebug(case.first, case.second)
            val actual = wideVerticalParagraphGapAllowed(debug, limits, case.rightText, case.leftText)
            if (case.expected) {
                assertTrue(case.name, actual)
            } else {
                assertFalse(case.name, actual)
            }
        }
    }
}
