package com.gameocr.app.ocr

import com.gameocr.app.data.MergeStrength
import com.gameocr.app.data.RenderMode
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrMergePresentationPolicyTest {
    @Test
    fun deferGeometricMerge_tableDrivenOnlyForFloatingAll() {
        data class Case(
            val renderMode: RenderMode,
            val enabled: Boolean,
            val strength: MergeStrength,
            val expected: Boolean,
        )

        listOf(
            Case(RenderMode.FLOATING_WINDOW, true, MergeStrength.ALL, true),
            Case(RenderMode.FLOATING_WINDOW, false, MergeStrength.ALL, false),
            Case(RenderMode.FLOATING_WINDOW, true, MergeStrength.CONSERVATIVE, false),
            Case(RenderMode.FLOATING_WINDOW, true, MergeStrength.STANDARD, false),
            Case(RenderMode.FLOATING_WINDOW, true, MergeStrength.AGGRESSIVE, false),
            Case(RenderMode.BLOCKS, true, MergeStrength.ALL, false),
            Case(RenderMode.BLOCKS, false, MergeStrength.ALL, false),
        ).forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                OcrMergePresentationPolicy.deferGeometricMerge(
                    renderMode = case.renderMode,
                    mergeAdjacentBlocks = case.enabled,
                    mergeStrength = case.strength,
                ),
            )
        }
    }
}
