package com.gameocr.app.translate

internal fun shouldPassthroughNumericTranslation(source: String): Boolean {
    if (source.isBlank()) return false
    var hasNumber = false
    var offset = 0
    while (offset < source.length) {
        val codePoint = Character.codePointAt(source, offset)
        val type = Character.getType(codePoint)
        when (type) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt() -> hasNumber = true

            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt(),
            Character.MATH_SYMBOL.toInt(),
            Character.CURRENCY_SYMBOL.toInt(),
            Character.MODIFIER_SYMBOL.toInt(),
            Character.OTHER_SYMBOL.toInt(),
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.FORMAT.toInt(),
            Character.SPACE_SEPARATOR.toInt(),
            Character.LINE_SEPARATOR.toInt(),
            Character.PARAGRAPH_SEPARATOR.toInt() -> Unit

            else -> if (!Character.isWhitespace(codePoint)) return false
        }
        offset += Character.charCount(codePoint)
    }
    return hasNumber
}

internal data class TranslationPassthroughBatchPlan(
    val sourceCount: Int,
    val translatableIndexes: List<Int>,
    val translatableSources: List<String>,
    val passthroughUpdates: List<BatchTranslationUpdate>,
) {
    fun originalIndexFor(translatableIndex: Int): Int? = translatableIndexes.getOrNull(translatableIndex)

    fun merge(translated: List<String?>): List<String?> {
        val merged = MutableList<String?>(sourceCount) { null }
        passthroughUpdates.forEach { update -> merged[update.index] = update.text }
        translatableIndexes.forEachIndexed { translatedIndex, sourceIndex ->
            merged[sourceIndex] = translated.getOrNull(translatedIndex)
        }
        return merged
    }
}

internal fun planNumericTranslationPassthrough(
    sources: List<String>,
): TranslationPassthroughBatchPlan {
    val translatableIndexes = mutableListOf<Int>()
    val translatableSources = mutableListOf<String>()
    val passthroughUpdates = mutableListOf<BatchTranslationUpdate>()
    sources.forEachIndexed { index, source ->
        if (shouldPassthroughNumericTranslation(source)) {
            passthroughUpdates += BatchTranslationUpdate(index = index, text = source)
        } else {
            translatableIndexes += index
            translatableSources += source
        }
    }
    return TranslationPassthroughBatchPlan(
        sourceCount = sources.size,
        translatableIndexes = translatableIndexes,
        translatableSources = translatableSources,
        passthroughUpdates = passthroughUpdates,
    )
}
