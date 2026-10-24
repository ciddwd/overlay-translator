package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.Bubble
import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class MangaTextMemberConsolidatorTest {
    @Test
    fun savedPageBridge_isSplitBetweenTwoTextOwnersWithNoLostMembers() {
        for (scale in listOf(1, 2)) for (transpose in listOf(false, true)) {
            fun r(l: Int, t: Int, rr: Int, b: Int) = if (transpose) IntRect(t * scale, l * scale, b * scale, rr * scale)
                else IntRect(l * scale, t * scale, rr * scale, b * scale)
            val members = listOf(r(597, 799, 689, 1044), r(829, 803, 886, 950),
                r(744, 784, 866, 1025), r(630, 787, 767, 1090), r(1000, 1700, 1020, 1720))
            val entries = listOf(entry(members[0], listOf(0), 2), entry(members[1], listOf(1), 8),
                entry(r(630, 784, 1020, 1720), listOf(2, 3, 4)))
            val result = MangaTextMemberConsolidator.consolidate(entries, members, listOf(
                detection(r(611, 813, 746, 1064)), detection(r(775, 812, 908, 1007))))
            assertEquals(listOf(0, 3), result.entries[0].bubble.memberIndices)
            assertEquals(listOf(1, 2), result.entries[1].bubble.memberIndices)
            assertEquals(listOf(4), result.entries[2].bubble.memberIndices)
            assertEquals(members[4], result.entries[2].bubble.contentRect)
            assertEquals((0..4).toList(), result.entries.flatMap { it.bubble.memberIndices }.sorted())
            assertEquals(mapOf(2 to 8, 3 to 2), result.movedMembers)
        }
    }

    @Test
    fun memberTable_preservesFreeTextNeighborsAmbiguityAndWeakEvidence() {
        data class Case(val name: String, val free: Boolean = false, val confidence: Float = 0.9f,
            val neighbor: Boolean = false, val ambiguous: Boolean = false, val move: Boolean)
        listOf(Case("clear owner", move = true), Case("standalone", free = true, move = false),
            Case("weak evidence", confidence = 0.34f, move = false), Case("neighbor", neighbor = true, move = false),
            Case("ambiguous owners", ambiguous = true, move = false)).forEach { c ->
            val member = if (c.neighbor) IntRect(95, 20, 130, 60) else IntRect(20, 20, 40, 60)
            val owner = entry(IntRect(0, 0, 100, 100), listOf(0), 0)
            val entries = listOf(owner) + if (c.ambiguous) listOf(entry(IntRect(1, 0, 101, 100), listOf(2), 1)) else emptyList()
            val fragment = entry(member, listOf(1)).copy(regionGranularity = if (c.free) TextRegionGranularity.FREE_TEXT else TextRegionGranularity.BUBBLE)
            val ds = listOf(detection(IntRect(0, 0, 100, 100), c.confidence)) +
                if (c.ambiguous) listOf(detection(IntRect(1, 0, 101, 100))) else emptyList()
            val result = MangaTextMemberConsolidator.consolidate(entries + fragment,
                listOf(owner.bubble.contentRect, member, IntRect(1, 0, 101, 100)), ds)
            assertEquals(c.name, c.move, result.movedMembers.isNotEmpty())
            assertEquals(c.name, (entries + fragment).flatMap { it.bubble.memberIndices }.sorted(),
                result.entries.flatMap { it.bubble.memberIndices }.sorted())
        }
    }

    @Test
    fun savedSingleStrokeCorner_isOwnedByTheCompleteWordIncludingEraseGeometry() {
        val word = IntRect(214, 1298, 428, 1371)
        val corner = IntRect(321, 1260, 363, 1283)
        val evidence = detection(IntRect(210, 1266, 422, 1375), .391f)
        val owner = entry(word, listOf(0), 9)
        val fragment = entry(corner, listOf(1))
        val result = MangaTextMemberConsolidator.consolidate(listOf(owner, fragment), listOf(word, corner), listOf(evidence))
        assertEquals(1, result.entries.size)
        assertEquals(listOf(0, 1), result.entries.single().bubble.memberIndices)
        assertEquals(1260, result.entries.single().bubble.contentRect.top)
        assertEquals(mapOf(1 to 9), result.movedMembers)
        val broad = corner.copy(right = 410, bottom = 1330)
        val uncertain = MangaTextMemberConsolidator.consolidate(listOf(owner, entry(broad, listOf(1))),
            listOf(word, broad), listOf(evidence))
        assertTrue("larger weak regions need their own recognition", uncertain.movedMembers.isEmpty())
    }

    private fun entry(bounds: IntRect, members: List<Int>, model: Int? = null) = MangaOcrBubbleGroupingPolicy.Entry(
        Bubble(bounds, bounds, members), if (model == null) BubbleModelRegrouper.Source.LEGACY_FALLBACK else BubbleModelRegrouper.Source.MODEL, model)
    private fun detection(r: IntRect, confidence: Float = .9f) = MangaBubbleDetectionPostprocessor.Detection(
        confidence, r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), MangaBubbleDetectionPostprocessor.Kind.TEXT_BUBBLE)
}
