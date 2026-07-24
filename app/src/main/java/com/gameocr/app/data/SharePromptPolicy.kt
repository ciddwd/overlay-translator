package com.gameocr.app.data

internal object SharePromptPolicy {
    const val PROMPT_ON_MAIN_ENTRY = 3

    data class Decision(
        val nextEntryCount: Int,
        val eligibleToShow: Boolean,
    )

    fun onMainScreenEntry(
        storedEntryCount: Int,
        promptAlreadyShown: Boolean,
    ): Decision {
        val normalizedCount = storedEntryCount.coerceIn(0, PROMPT_ON_MAIN_ENTRY)
        if (promptAlreadyShown) {
            return Decision(
                nextEntryCount = normalizedCount,
                eligibleToShow = false,
            )
        }

        val nextCount = (normalizedCount + 1).coerceAtMost(PROMPT_ON_MAIN_ENTRY)
        return Decision(
            nextEntryCount = nextCount,
            eligibleToShow = nextCount >= PROMPT_ON_MAIN_ENTRY,
        )
    }
}
