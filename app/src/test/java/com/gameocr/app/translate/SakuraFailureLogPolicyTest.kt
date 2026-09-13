package com.gameocr.app.translate

import org.junit.Assert.assertTrue
import org.junit.Test

class SakuraFailureLogPolicyTest {

    @Test
    fun groupFailure_containsActionableDetails_tableDriven() {
        data class Case(
            val name: String,
            val reason: SakuraOutputRejectionReason?,
            val expectedReason: String,
        )

        listOf(
            Case("token limit", SakuraOutputRejectionReason.TOKEN_LIMIT, "达到输出上限(TOKEN_LIMIT)"),
            Case("line mismatch", SakuraOutputRejectionReason.LINE_COUNT_MISMATCH, "返回段数不符(LINE_COUNT_MISMATCH)"),
            Case("empty", SakuraOutputRejectionReason.EMPTY, "空输出(EMPTY)"),
            Case("unknown", null, "未知(UNKNOWN)"),
        ).forEach { case ->
            val message = SakuraFailureLogPolicy.groupFailure(
                startIndex = 0,
                expectedLines = 14,
                actualLines = 8,
                outputChars = 287,
                outputPieces = 256,
                effectiveMaxTokens = 256,
                reason = case.reason,
                stage = SakuraRetryStage.INITIAL,
                retryEnabled = false,
            )

            assertTrue(case.name, message.contains(case.expectedReason))
            assertTrue(case.name, message.contains("段落=1-14"))
            assertTrue(case.name, message.contains("期望/实际=14/8"))
            assertTrue(case.name, message.contains("生成Token=256/256"))
            assertTrue(case.name, message.contains("失败重试=关闭"))
        }
    }

    @Test
    fun lineFailures_mapsIndexesAndReasons_tableDriven() {
        val message = SakuraFailureLogPolicy.lineFailures(
            startIndex = 4,
            lineCount = 3,
            rejectedLocalIndexes = listOf(0, 2),
            reasons = mapOf(
                0 to SakuraOutputRejectionReason.PROMPT_ECHO,
                2 to SakuraOutputRejectionReason.JAPANESE_RESIDUE,
            ),
            stage = SakuraRetryStage.SALVAGE,
            retryEnabled = false,
        )

        listOf(
            "阶段=拆分恢复",
            "段落=5-7",
            "5:提示词回显(PROMPT_ECHO)",
            "7:日文残留过多(JAPANESE_RESIDUE)",
            "失败重试=关闭",
        ).forEach { expected ->
            assertTrue(expected, message.contains(expected))
        }
    }
}
