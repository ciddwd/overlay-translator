package com.gameocr.app.asr

internal object SpeechTextPolicy {
    private val modelTag = Regex("<\\|[^|>]*\\|>")
    private val whitespace = Regex("\\s+")

    /** Removes model control tags (SenseVoice language/emotion/event) and normalizes spacing. */
    fun clean(raw: String): String = raw.replace(modelTag, " ").replace(whitespace, " ").trim()

    /**
     * Whisper invents text over music and silence. Reject output that is far denser than speech or
     * that is one short phrase repeated; real lines are kept even when they repeat once.
     */
    fun isLikelyHallucination(text: String, audioSeconds: Float): Boolean {
        if (text.isBlank()) return true
        val seconds = audioSeconds.coerceAtLeast(0.1f)
        if (text.length / seconds > MAX_CHARS_PER_SECOND) return true
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.size >= REPEAT_LIMIT && words.distinct().size == 1) return true
        return hasRepeatedRun(text.replace(" ", ""))
    }

    /** True when any 1..16 character unit repeats [REPEAT_LIMIT] or more times back to back. */
    private fun hasRepeatedRun(text: String): Boolean {
        for (unit in 1..16) {
            if (text.length < unit * REPEAT_LIMIT) break
            var run = 1
            var i = unit
            while (i + unit <= text.length) {
                if (text.regionMatches(i, text, i - unit, unit)) {
                    run++
                    if (run >= REPEAT_LIMIT) return true
                    i += unit
                } else {
                    run = 1
                    i++
                }
            }
        }
        return false
    }

    private const val MAX_CHARS_PER_SECOND = 30f
    private const val REPEAT_LIMIT = 4
}
