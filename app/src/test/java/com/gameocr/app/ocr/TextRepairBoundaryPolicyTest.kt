package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class TextRepairBoundaryPolicyTest {
    @Test fun trustworthyCurvesSeparateTextFromTheOutline_tableDriven() {
        for (scale in listOf(1,2)) {
            val size=80*scale
            val interior=BooleanArray(size*size) { i ->
                val x=(i%size-40*scale).toDouble()/(32*scale)
                val y=(i/size-40*scale).toDouble()/(36*scale)
                x*x+y*y<1
            }
            val core=BooleanArray(size*size) { i -> i%size in 48*scale..58*scale && i/size in 20*scale..50*scale }
            val mask=TextPixelMaskBuilder.BlockMask(0,IntRect(0,0,size,size),core.copyOf(),core.count { it },
                core,BooleanArray(core.size) { true },BooleanArray(core.size) { true })
            val before=interior.copyOf()
            val fixed=TextRepairBoundaryPolicy.constrain(mask,
                BubbleSegmentationPostprocessor.InstanceMask(0,0,size,size,interior),BubbleShapeMaskQuality.TRUSTED)
            assertTrue(fixed.barrierPixels.any { it })
            assertFalse(fixed.barrierPixels[40*scale*size+55*scale])
            assertTrue(fixed.barrierPixels[0])
            assertEquals(mask.selectedCorePixels,fixed.selectedCorePixels)
            assertTrue(interior.contentEquals(before))
            assertTrue(mask.barrierPixels.isEmpty())
        }
    }

    @Test fun approximateRejectedOrUnrelatedInteriorsDoNotConstrainTheRepair_tableDriven() {
        val pixels=BooleanArray(100) { it%10 in 6..8 && it/10 in 3..7 }
        val mask=TextPixelMaskBuilder.BlockMask(0,IntRect(0,0,10,10),pixels,pixels.count { it })
        val unrelated=BubbleSegmentationPostprocessor.InstanceMask(0,0,4,10,BooleanArray(40) { true })
        for(quality in BubbleShapeMaskQuality.entries) {
            assertSame(mask,TextRepairBoundaryPolicy.constrain(mask,unrelated,quality))
        }
        val full=BubbleSegmentationPostprocessor.InstanceMask(0,0,10,10,BooleanArray(100) { true })
        for(quality in listOf(BubbleShapeMaskQuality.APPROXIMATE,BubbleShapeMaskQuality.REJECTED)) {
            assertSame(mask,TextRepairBoundaryPolicy.constrain(mask,full,quality))
        }
    }

    @Test fun genuineConcavityIsPreserved() {
        val size=60
        val interior=BooleanArray(size*size) { i -> i%size in 5..54 && i/size in 5..54 && !(i%size>30 && i/size in 20..40) }
        val core=BooleanArray(size*size) { i -> i%size in 10..20 && i/size in 10..50 }
        val mask=TextPixelMaskBuilder.BlockMask(0,IntRect(0,0,size,size),core,core.count { it },core,
            BooleanArray(core.size) { true },BooleanArray(core.size) { true })
        val result=TextRepairBoundaryPolicy.constrain(mask,
            BubbleSegmentationPostprocessor.InstanceMask(0,0,size,size,interior),BubbleShapeMaskQuality.TRUSTED)
        assertTrue(result.barrierPixels[30*size+40])
    }
}
