package com.gameocr.app.overlay

private const val RIGHT_TO_LEFT_OVERRIDE = '\u202E'
private const val POP_DIRECTIONAL_FORMATTING = '\u202C'

/**
 * Keeps logical text order intact and asks Android's bidi layout to reorder each rendered line.
 * Explicit line separators are kept outside the override so paragraph order remains top-to-bottom.
 */
internal fun horizontalRtlDisplayText(text: String): String {
    if (text.isEmpty()) return text
    val output = StringBuilder(text.length + 2)
    var lineStart = 0
    var index = 0
    while (index < text.length) {
        val separatorLength = when {
            text[index] == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> 2
            text[index] == '\r' || text[index] == '\n' -> 1
            else -> 0
        }
        if (separatorLength == 0) {
            index++
            continue
        }
        appendRtlOverride(output, text.substring(lineStart, index))
        output.append(text, index, index + separatorLength)
        index += separatorLength
        lineStart = index
    }
    appendRtlOverride(output, text.substring(lineStart))
    return output.toString()
}

private fun appendRtlOverride(output: StringBuilder, line: String) {
    if (line.isEmpty()) return
    output.append(RIGHT_TO_LEFT_OVERRIDE)
    output.append(line)
    output.append(POP_DIRECTIONAL_FORMATTING)
}
