package com.gameocr.app.overlay

internal enum class TranslationBlockCopyTextRole {
    SOURCE,
    TRANSLATION,
}

internal data class TranslationBlockCopyTextSpec(
    val textSizeSp: Float,
    val applyTranslationDisplayStyle: Boolean,
)

internal fun translationBlockCopyTextSpec(
    role: TranslationBlockCopyTextRole,
): TranslationBlockCopyTextSpec = when (role) {
    TranslationBlockCopyTextRole.SOURCE -> TranslationBlockCopyTextSpec(
        textSizeSp = 16f,
        applyTranslationDisplayStyle = false,
    )
    TranslationBlockCopyTextRole.TRANSLATION -> TranslationBlockCopyTextSpec(
        textSizeSp = 16f,
        applyTranslationDisplayStyle = true,
    )
}
