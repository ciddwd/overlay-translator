package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class MangaPanelGroupingPolicyTest {
    @Test fun frameSeparatesMainTextAndDisjointRuby_tableDriven() {
        for (scale in listOf(1, 2, 3)) for (vertical in listOf(true, false)) {
            fun box(l: Int, t: Int, r: Int, b: Int): IntRect = if (vertical)
                IntRect(l*scale,t*scale,r*scale,b*scale) else IntRect(t*scale,l*scale,b*scale,r*scale)
            val members = listOf(box(65,20,92,130), box(90,22,98,40),
                box(112,30,142,135), box(115,104,121,128))
            val line = MangaPanelGroupingPolicy.Separator(vertical, 100*scale, 0, 180*scale)
            val result = MangaPanelGroupingPolicy.split(listOf(entry(members)), members, listOf(line))
            assertEquals("scale=$scale vertical=$vertical", 2, result.entries.size)
            assertEquals(setOf(setOf(0,1), setOf(2,3)), result.entries.map { it.bubble.memberIndices.toSet() }.toSet())
            assertEquals(members.indices.toList(), result.entries.flatMap { it.bubble.memberIndices }.sorted())
            assertEquals(listOf(0,0), result.originalIndices)
            assertEquals(setOf(0), result.splitOriginalIndices)
        }
    }

    @Test fun absentShortOrNonintersectingFramesDoNotSplit_tableDriven() {
        val members = listOf(IntRect(20,20,45,100), IntRect(60,25,85,105))
        val entry = entry(members)
        for (lines in listOf(emptyList(),
            listOf(MangaPanelGroupingPolicy.Separator(true, 50, 30, 40)),
            listOf(MangaPanelGroupingPolicy.Separator(true, 50, 120, 240)),
            listOf(MangaPanelGroupingPolicy.Separator(true, 90, 0, 240)))) {
            val result = MangaPanelGroupingPolicy.split(listOf(entry), members, lines)
            assertEquals(listOf(entry), result.entries)
            assertEquals(members, result.memberBounds)
            assertTrue(result.splitOriginalIndices.isEmpty())
        }
    }

    @Test fun detectsAntialiasedPanelFramesAtDifferentResolutions_tableDriven() {
        for (scale in listOf(1,2)) for (vertical in listOf(true,false)) {
            val size=200*scale
            val source=IntArray(size*size) { -1 }
            for (along in 8*scale until 190*scale) for (lane in 98*scale..100*scale) {
                source[if(vertical) along*size+lane else lane*size+along]=0xff404040.toInt()
            }
            val lines=MangaPanelGroupingPolicy.separators(size,size,source)
            assertTrue(lines.any { it.vertical==vertical && it.lane in 95*scale..103*scale && it.end-it.start>170*scale })
        }
    }

    @Test fun emptyAndInvalidInputsAreExplicit() {
        assertTrue(MangaPanelGroupingPolicy.split(emptyList(),emptyList(),emptyList()).entries.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { MangaPanelGroupingPolicy.separators(0,1,IntArray(0)) }
        assertThrows(IllegalArgumentException::class.java) { MangaPanelGroupingPolicy.separators(4,4,IntArray(15)) }
    }

    private fun entry(boxes: List<IntRect>): MangaOcrBubbleGroupingPolicy.Entry {
        val bounds=IntRect(boxes.minOf { it.left },boxes.minOf { it.top },boxes.maxOf { it.right },boxes.maxOf { it.bottom })
        return MangaOcrBubbleGroupingPolicy.Entry(BubbleClusterer.Bubble(bounds,bounds,boxes.indices.toList()),
            BubbleModelRegrouper.Source.LEGACY_FALLBACK,null,TextRegionGranularity.FREE_TEXT,true)
    }
}
