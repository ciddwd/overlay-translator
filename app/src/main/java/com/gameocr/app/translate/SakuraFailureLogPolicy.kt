package com.gameocr.app.translate

internal object SakuraFailureLogPolicy {
    fun groupFailure(
        startIndex: Int,
        expectedLines: Int,
        actualLines: Int,
        outputChars: Int,
        outputPieces: Int,
        effectiveMaxTokens: Int,
        reason: SakuraOutputRejectionReason?,
        stage: SakuraRetryStage,
        retryEnabled: Boolean,
    ): String = buildString {
        append("Sakura 翻译失败")
        append(" 阶段=").append(stageLabel(stage))
        append(" 原因=").append(reasonLabel(reason))
        append(" 段落=").append(rangeLabel(startIndex, expectedLines))
        append(" 期望/实际=").append(expectedLines).append('/').append(actualLines)
        append(" 生成Token=").append(outputPieces).append('/').append(effectiveMaxTokens)
        append(" 输出字符=").append(outputChars)
        append(" 失败重试=").append(if (retryEnabled) "开启" else "关闭")
    }

    fun lineFailures(
        startIndex: Int,
        lineCount: Int,
        rejectedLocalIndexes: List<Int>,
        reasons: Map<Int, SakuraOutputRejectionReason?>,
        stage: SakuraRetryStage,
        retryEnabled: Boolean,
    ): String = buildString {
        append("Sakura 翻译失败")
        append(" 阶段=").append(stageLabel(stage))
        append(" 原因=单段结果校验未通过")
        append(" 段落=").append(rangeLabel(startIndex, lineCount))
        append(" 失败项=")
        append(
            rejectedLocalIndexes.joinToString(",") { localIndex ->
                val displayIndex = startIndex + localIndex + 1
                "$displayIndex:${reasonLabel(reasons[localIndex])}"
            }
        )
        append(" 失败重试=").append(if (retryEnabled) "开启" else "关闭")
    }

    private fun rangeLabel(startIndex: Int, lineCount: Int): String {
        val first = startIndex.coerceAtLeast(0) + 1
        val last = first + lineCount.coerceAtLeast(1) - 1
        return if (first == last) first.toString() else "$first-$last"
    }

    private fun stageLabel(stage: SakuraRetryStage): String = when (stage) {
        SakuraRetryStage.INITIAL -> "首次整批生成"
        SakuraRetryStage.SALVAGE -> "拆分恢复"
    }

    private fun reasonLabel(reason: SakuraOutputRejectionReason?): String = when (reason) {
        SakuraOutputRejectionReason.TOKEN_LIMIT -> "达到输出上限(TOKEN_LIMIT)"
        SakuraOutputRejectionReason.LINE_COUNT_MISMATCH -> "返回段数不符(LINE_COUNT_MISMATCH)"
        SakuraOutputRejectionReason.EMPTY -> "空输出(EMPTY)"
        SakuraOutputRejectionReason.MULTILINE -> "单段返回多行(MULTILINE)"
        SakuraOutputRejectionReason.TOO_LONG -> "译文异常过长(TOO_LONG)"
        SakuraOutputRejectionReason.PROMPT_ECHO -> "提示词回显(PROMPT_ECHO)"
        SakuraOutputRejectionReason.SOURCE_COPY -> "近似照抄原文(SOURCE_COPY)"
        SakuraOutputRejectionReason.JAPANESE_RESIDUE -> "日文残留过多(JAPANESE_RESIDUE)"
        SakuraOutputRejectionReason.DEGENERATE_REPETITION -> "异常重复(DEGENERATE_REPETITION)"
        null -> "未知(UNKNOWN)"
    }
}
