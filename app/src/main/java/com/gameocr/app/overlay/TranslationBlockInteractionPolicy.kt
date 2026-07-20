package com.gameocr.app.overlay

import com.gameocr.app.data.TranslationBlockInteractionMode

internal data class TranslationBlockInteractionPlan(
    val enableNativeTextSelection: Boolean,
    val enableSelectedTextSpeech: Boolean,
    val openCopyPanelOnBlockTap: Boolean,
    val windowFocusable: Boolean,
    val useDecorViewActionModeHost: Boolean,
)

internal fun translationBlockInteractionPlan(
    mode: TranslationBlockInteractionMode,
): TranslationBlockInteractionPlan = when (mode) {
    TranslationBlockInteractionMode.COPY_BUTTON -> TranslationBlockInteractionPlan(
        enableNativeTextSelection = true,
        enableSelectedTextSpeech = true,
        openCopyPanelOnBlockTap = false,
        windowFocusable = true,
        useDecorViewActionModeHost = true,
    )

    TranslationBlockInteractionMode.OPEN_COPY_PANEL -> TranslationBlockInteractionPlan(
        enableNativeTextSelection = false,
        enableSelectedTextSpeech = false,
        openCopyPanelOnBlockTap = true,
        windowFocusable = false,
        useDecorViewActionModeHost = false,
    )
}

internal fun isTranslationBlockTextActionable(text: String?): Boolean =
    !text.isNullOrBlank() && text != "..." && text != "…"
