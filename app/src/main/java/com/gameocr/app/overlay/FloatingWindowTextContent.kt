package com.gameocr.app.overlay

import com.gameocr.app.data.FloatingWindowContentMode

internal enum class FloatingWindowTextRole {
    SOURCE,
    TRANSLATION,
    SEPARATOR,
}

internal data class FloatingWindowTextSegment(
    val text: String,
    val role: FloatingWindowTextRole,
)

internal fun floatingWindowTextSegments(
    pairs: List<Pair<String, String>>,
    mode: FloatingWindowContentMode,
): List<FloatingWindowTextSegment> = buildList {
    pairs.forEachIndexed { index, (source, translation) ->
        if (mode == FloatingWindowContentMode.SRC_AND_DST) {
            add(FloatingWindowTextSegment("・$source\n", FloatingWindowTextRole.SOURCE))
        }
        add(FloatingWindowTextSegment(translation, FloatingWindowTextRole.TRANSLATION))
        if (index < pairs.lastIndex) {
            add(FloatingWindowTextSegment("\n\n", FloatingWindowTextRole.SEPARATOR))
        }
    }
}

internal fun hasSelectableFloatingWindowContent(
    pairs: List<Pair<String, String>>,
    mode: FloatingWindowContentMode,
): Boolean = pairs.any { (source, translation) ->
    (mode == FloatingWindowContentMode.SRC_AND_DST &&
        isTranslationBlockTextActionable(source)) ||
        isTranslationBlockTextActionable(translation)
}
