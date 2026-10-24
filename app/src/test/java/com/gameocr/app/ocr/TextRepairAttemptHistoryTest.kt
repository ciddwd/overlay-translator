package com.gameocr.app.ocr

import com.gameocr.app.ocr.BubbleClusterer.IntRect
import org.junit.Assert.*
import org.junit.Test

class TextRepairAttemptHistoryTest {
    @Test fun completedPixelsCanBeDisplayedAfterAnEarlierAttemptFailed_tableDriven() {
        val size=32
        val core=BooleanArray(size*size) { it%size in 12..17 && it/size in 12..17 }
        val source=IntArray(core.size) { if(core[it]) 0xff111111.toInt() else -1 }
        val mask=TextPixelMaskBuilder.BlockMask(0,IntRect(0,0,size,size),core,core.count { it })
        val complete=LocalTextBackgroundRepairer.repair(size,size,source,listOf(mask)).blocks.single()
        assertTrue(complete.displayable)
        val earlierFailure=complete.decisions.first().copy(accepted=false,
            reason=MaskedBackgroundRepairer.Reason.INSUFFICIENT_BOUNDARY_SAMPLES,
            mode=MaskedBackgroundRepairer.Mode.NONE)
        val retried=complete.copy(componentCount=complete.componentCount+1,
            decisions=listOf(earlierFailure)+complete.decisions,residualRepairPasses=1)
        data class Case(val value:LocalTextBackgroundRepairer.BlockRepair,val expected:Boolean)
        for(case in listOf(
            Case(retried,true),
            Case(retried.copy(residualPixels=1),false),
            Case(retried.copy(repairedRequiredErasePixels=retried.requiredErasePixels-1),false),
            Case(retried.copy(patchPixels=null),false),
            Case(retried.copy(acceptedComponentCount=0),false),
        )) assertEquals(case.expected,case.value.displayable)
        val uncertain=retried.copy(regionAuditReason=TextRegionInkAudit.Reason.PARTIAL,
            repairedSemanticPixels=0)
        assertTrue(uncertain.displayable)
        assertFalse("Attempt success is not independent proof that all original ink is gone",uncertain.fullyRepaired)
    }
}
