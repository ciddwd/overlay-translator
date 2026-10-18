package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class TextStrokeMaskPolicyTest {
    @Test fun outlinedDarkLightAndFaintTextAreCoveredWithoutErasingTheFrame_tableDriven() {
        for (scale in listOf(1,2)) for (light in listOf(false,true)) for (faint in listOf(false,true)) {
            val size=100*scale
            fun inside(i:Int,l:Int,t:Int,r:Int,b:Int)=i%size in l*scale until r*scale && i/size in t*scale until b*scale
            val core=BooleanArray(size*size) { inside(it,36,30,52,63) }
            val semantic=BooleanArray(size*size) { inside(it,12,10,88,89) }
            val source=IntArray(core.size) { i -> when {
                inside(i,9,0,11,100)->gray(5)
                inside(i,36,30,52,63)->gray(if(light) 250 else if(faint) 140 else 12)
                inside(i,32,26,56,67)->gray(if(light) 8 else 250)
                inside(i,55,29,60,35)->0xffe090a0.toInt()
                else -> gray(if(light) 185+(i%size%3) else 170+(i%size%4))
            } }
            val before=source.copyOf()
            val plan=checkNotNull(TextStrokeMaskPolicy.plan(size,size,source,core,semantic))
            assertTrue(core.indices.filter { core[it] }.all { plan.erase[it] })
            for (i in core.indices) {
                if(inside(i,9,0,11,100)) assertFalse("frame pixel $i",plan.erase[i])
                if(source[i]==0xffe090a0.toInt()) assertFalse("colored pixel $i",plan.erase[i])
            }
            assertTrue(source.contentEquals(before))
        }
    }

    @Test fun spatialRepairUsesLocalBackgroundOnBothSidesOfAnOutlinedWord() {
        val size=100
        val source=IntArray(size*size) { i -> gray(if(i%size<50) 172 else 12) }
        val core=BooleanArray(source.size)
        for(y in 30..60) for(x in 30..70) {
            source[y*size+x]=gray(250)
            if(y in 35..55 && (x in 35..42 || x in 57..64)) {
                core[y*size+x]=true;source[y*size+x]=gray(2)
            }
        }
        val semantic=BooleanArray(source.size) { i -> i%size in 25..76 && i/size in 24..66 }
        val block=LocalTextBackgroundRepairer.repair(size,size,source,listOf(
            TextPixelMaskBuilder.BlockMask(0,IntRect(0,0,size,size),core,core.count { it },core,semantic,semantic)
        ),texturedBlockIndices=setOf(0)).blocks.single()
        assertTrue(block.displayable)
        val patch=checkNotNull(block.patchPixels)
        assertTrue((patch[45*size+60] and 255)<70)
        assertTrue((patch[45*size+38] and 255)>100)
        assertEquals(0,patch[10*size+50])
    }

    @Test fun emptyEvidenceAndInvalidSizesDoNotProduceARepair_tableDriven() {
        for(color in listOf(0xff777777.toInt(),-1,0xff000000.toInt())) {
            assertNull(TextStrokeMaskPolicy.plan(10,10,IntArray(100) { color },BooleanArray(100),BooleanArray(100) { true }))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextStrokeMaskPolicy.plan(10,10,IntArray(99),BooleanArray(100),BooleanArray(100))
        }
    }

    @Test fun mixedColorEvidenceKeepsTheOriginalRepairFallback_tableDriven() {
        for (color in listOf(0xffcc1234.toInt(),0xff1266cc.toInt())) {
            val width=60;val source=IntArray(width*width) { gray(175) }
            val core=BooleanArray(source.size)
            for(y in 15..35) for(x in 18..23) { core[y*width+x]=true;source[y*width+x]=gray(12) }
            for(y in 15..35) for(x in 36..41) { core[y*width+x]=true;source[y*width+x]=color }
            assertNull(TextStrokeMaskPolicy.plan(width,width,source,core,BooleanArray(source.size) { true }))
        }
    }

    @Test fun supportedChromaticStrokesDoNotTurnTheWhiteBalloonIntoAnOutlineMask_table() {
        for(color in listOf(0xffbb2020.toInt(),0xff2050bb.toInt())) {
            val width=100;val source=IntArray(width*width) { gray(175) }
            for(y in 15..84)for(x in 15..84)source[y*width+x]=-1
            val core=BooleanArray(source.size)
            for(y in 35..59)for(x in 39..47) { core[y*width+x]=true;source[y*width+x]=color }
            for(y in 0 until width)source[y*width+88]=gray(5)
            val semantic=BooleanArray(source.size) { it%width in 10..90 && it/width in 10..90 }
            val plan=checkNotNull(TextStrokeMaskPolicy.plan(width,width,source,core,semantic,allowChromaticInk=true))
            for(i in core.indices)if(core[i])assertTrue("colored original omitted",plan.erase[i])
            assertFalse("white balloon was replaced as one component",plan.erase[20*width+20])
            for(y in 0 until width)assertFalse("frame erased",plan.erase[y*width+88])
        }
    }

    private fun gray(value:Int)=0xff000000.toInt() or(value shl 16)or(value shl 8)or value
}
