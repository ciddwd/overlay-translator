package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleTextGroupingPolicyTest {
    @Test fun recordedGameBodyNeverAbsorbsItsOkControlAcrossCaptureScales() {
        val boxes=listOf(IntRect(1090,495,2235,548),IntRect(1177,556,2152,610),
            IntRect(1146,619,2183,665),IntRect(1122,681,2207,726),IntRect(1189,742,2140,787),
            IntRect(1645,946,1688,981))
        for(scale in listOf(.5f,1f,2f)) {
            val scaled=boxes.map { IntRect((it.left*scale).toInt(),(it.top*scale).toInt(),
                (it.right*scale).toInt(),(it.bottom*scale).toInt()) }
            assertEquals(listOf(listOf(0,1,2,3,4),listOf(5)),BubbleTextGroupingPolicy.partition(scaled,true))
            val merged=IntRect(scaled.take(5).minOf { it.left },scaled.first().top,
                scaled.take(5).maxOf { it.right },scaled[4].bottom)
            assertEquals(listOf(listOf(0),listOf(1)),BubbleTextGroupingPolicy.partition(listOf(merged,scaled[5]),true))
        }
    }

    @Test fun normalAndBoundaryGroups_table() {
        val two=listOf(IntRect(10,10,100,40),IntRect(12,50,100,80))
        assertEquals(listOf(listOf(0,1)),BubbleTextGroupingPolicy.partition(two,true))
        assertEquals(listOf(listOf(0),listOf(1)),BubbleTextGroupingPolicy.partition(
            listOf(two[0],IntRect(12,100,100,130)),true))
        assertEquals(listOf(listOf(0,1)),BubbleTextGroupingPolicy.partition(two,false))
        assertEquals(emptyList<List<Int>>(),BubbleTextGroupingPolicy.partition(emptyList(),true))
    }
}
