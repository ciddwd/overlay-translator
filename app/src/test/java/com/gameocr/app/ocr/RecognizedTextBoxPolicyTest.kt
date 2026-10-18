package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class RecognizedTextBoxPolicyTest {
    private fun block(vararg boxes: IntRect) = DelayedTextEraseMaskBuilder.ConfirmedBlock(0, boxes.toList())

    @Test fun sourceRegionsRemainWholeWithoutPaintingTheirRectangle_table() {
        val boxes = listOf(IntRect(8,7,20,17),IntRect(30,7,42,17))
        for (sparse in listOf(false,true)) {
            val candidate = BooleanArray(50*25) { (it%50 in 10..14 && it/50 in 9..13) ||
                (!sparse && it%50 in 32..36 && it/50 in 9..13) }
            val result = RecognizedTextBoxPolicy.build(50,25,candidate,listOf(block(*boxes.toTypedArray())))
            val mask = result.masks.single()
            assertEquals(240,mask.recognizedBoxPixels.count { it })
            for(y in 0 until 25) for(x in 0 until 50) {
                val expected = boxes.any { x in it.left until it.right && y in it.top until it.bottom }
                val actual = if(x in mask.bounds.left until mask.bounds.right && y in mask.bounds.top until mask.bounds.bottom)
                    mask.recognizedBoxPixels[(y-mask.bounds.top)*mask.bounds.width+x-mask.bounds.left] else false
                assertEquals("($x,$y), sparse=$sparse",expected,actual)
            }
        }
    }

    @Test fun geometryIsClampedAndInvalidBoxesAreRejected_table() {
        for(scale in listOf(1,2,3)) {
            val result = RecognizedTextBoxPolicy.build(40*scale,30*scale,
                BooleanArray(1200*scale*scale) { it%(40*scale) in 2..5 && it/(40*scale) in 2..5 },
                listOf(block(IntRect(-5,-4,10*scale,12*scale))))
            assertEquals(120*scale*scale,result.masks.single().recognizedBoxPixels.count { it })
        }
        for(boxes in listOf(emptyArray(),arrayOf(IntRect(5,5,5,9)),arrayOf(IntRect(80,80,90,90)))) {
            val result = RecognizedTextBoxPolicy.build(40,30,BooleanArray(1200),listOf(block(*boxes)))
            assertTrue(result.masks.isEmpty())
            assertEquals(TextPixelMaskBuilder.Reason.NO_SOURCE_BOXES,result.decisions.single().reason)
        }
    }

    @Test fun aBlockIsBubbleTextOnlyWhenAllItsBoxesAreInsideOneVerifiedBubble_table() {
        val full = BubbleSegmentationPostprocessor.InstanceMask(10,10,30,30,BooleanArray(900) { true })
        val notched = full.copy(pixels=full.pixels.copyOf().also { it[5*30+5] = false })
        data class Case(val boxes:List<IntRect>,val models:List<BubbleSegmentationPostprocessor.InstanceMask>,
                        val quality:List<BubbleShapeMaskQuality>,val expected:TextRegionGranularity)
        for(case in listOf(
            Case(listOf(IntRect(12,12,25,25)),listOf(full),listOf(BubbleShapeMaskQuality.TRUSTED),TextRegionGranularity.BUBBLE),
            Case(listOf(IntRect(5,12,25,25)),listOf(full),listOf(BubbleShapeMaskQuality.TRUSTED),TextRegionGranularity.FREE_TEXT),
            Case(listOf(IntRect(12,12,25,25)),listOf(notched),listOf(BubbleShapeMaskQuality.TRUSTED),TextRegionGranularity.FREE_TEXT),
            Case(listOf(IntRect(12,12,25,25)),listOf(full),listOf(BubbleShapeMaskQuality.REJECTED),TextRegionGranularity.FREE_TEXT),
            Case(listOf(IntRect(12,12,25,25)),emptyList(),emptyList(),TextRegionGranularity.FREE_TEXT),
            Case(emptyList(),listOf(full),listOf(BubbleShapeMaskQuality.TRUSTED),TextRegionGranularity.FREE_TEXT),
            Case(listOf(IntRect(12,12,25,25),IntRect(52,12,65,25)),listOf(full,full.copy(left=50)),
                List(2) { BubbleShapeMaskQuality.TRUSTED },TextRegionGranularity.FREE_TEXT),
        )) assertEquals(case.toString(),case.expected,RecognizedTextBoxPolicy.classify(case.boxes,case.models,case.quality))
    }

    @Test fun entireOriginalBoxesAreRepairedAndAllGapsAndExteriorPixelsArePreserved_table() {
        val w=80; val h=60
        val boxes=listOf(IntRect(15,15,30,45),IntRect(50,15,65,45))
        for(background in listOf(0xffffffff.toInt(),0xffeaf6ff.toInt(),0xff111111.toInt())) {
            val ink=if(background==0xff111111.toInt()) 0xffffffff.toInt() else 0xff111111.toInt()
            val source=IntArray(w*h) { background }
            boxes.forEach { b -> for(y in b.top until b.bottom) for(x in b.left until b.right) {
                if(x<b.left+4 || y<b.top+4 || (y-b.top)%8<3) source[y*w+x]=ink
            } }
            source[30*w+40]=0xffdd0011.toInt() // Artwork between original lines must survive.
            val mask=RecognizedTextBoxPolicy.build(w,h,BooleanArray(w*h) { source[it]==ink },listOf(block(*boxes.toTypedArray()))).masks
            val repaired=LocalTextBackgroundRepairer.repair(w,h,source,mask).blocks.single()
            assertTrue(repaired.decisions.toString(),repaired.fullyRepaired)
            assertTrue(repaired.requiredErasePixels >= source.count { it == ink })
            assertEquals(0,repaired.residualPixels)
            val output=source.copyOf(); val bounds=repaired.mask.bounds
            repaired.patchPixels!!.forEachIndexed { i,color -> if(color ushr 24 != 0) {
                output[(bounds.top+i/bounds.width)*w+bounds.left+i%bounds.width]=color
            } }
            for(y in 0 until h) for(x in 0 until w) {
                val targeted=boxes.any { x in it.left until it.right && y in it.top until it.bottom }
                assertEquals("($x,$y), bg=$background",if(targeted) background else source[y*w+x],output[y*w+x])
            }
        }
    }

    @Test fun missingBackgroundSamplesAreReportedAsARepairFailure() {
        val mask=RecognizedTextBoxPolicy.build(10,10,BooleanArray(100) { true },listOf(block(IntRect(0,0,10,10)))).masks
        val result=LocalTextBackgroundRepairer.repair(10,10,IntArray(100) { 0xff111111.toInt() },mask).blocks.single()
        assertFalse(result.fullyRepaired)
        assertFalse(result.displayable)
        assertEquals(100,result.residualPixels)
    }

    @Test fun missingDetectorEvidenceIsNotReplacedWithASolidRectangleAndCalledSuccess() {
        val result=RecognizedTextBoxPolicy.build(40,30,BooleanArray(1200),listOf(block(IntRect(10,10,30,20))))
        assertTrue(result.masks.isEmpty())
        assertEquals(TextPixelMaskBuilder.Reason.TEXT_CORE_EMPTY,result.decisions.single().reason)
    }
}
