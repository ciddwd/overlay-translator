package com.gameocr.app.ui

import com.gameocr.app.data.Settings

/** One initial read; migration reuses that snapshot and never replaces a concurrent custom prompt. */
internal suspend fun loadSettingsForScreen(
    read: suspend () -> Settings,
    update: suspend ((Settings) -> Settings) -> Unit,
    currentDefault: String,
    knownDefaults: () -> List<String>,
): Settings {
    val initial = read()
    if (initial.promptTemplate == currentDefault || initial.promptTemplate !in knownDefaults()) {
        return initial
    }
    var result = initial
    update { latest ->
        val next = if (latest.promptTemplate == initial.promptTemplate) {
            latest.copy(promptTemplate = currentDefault)
        } else {
            latest
        }
        result = next
        next
    }
    return result
}
