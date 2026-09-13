package com.gameocr.app.translate

import com.gameocr.app.data.Languages
import com.gameocr.app.data.RuntimeTranslationPromptContext
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine

enum class InputTranslationPreparationError {
    EMPTY_INPUT,
    AUTO_TARGET,
    UNSUPPORTED_ENGINE,
}

sealed interface InputTranslationPreparation {
    data class Ready(
        val sourceText: String,
        val requestSettings: Settings,
    ) : InputTranslationPreparation

    data class Rejected(
        val error: InputTranslationPreparationError,
    ) : InputTranslationPreparation
}

/** Builds an isolated reverse-direction request for the focused-input action. */
object InputTranslationPolicy {
    fun prepare(text: String, settings: Settings): InputTranslationPreparation {
        if (text.isBlank()) {
            return InputTranslationPreparation.Rejected(
                InputTranslationPreparationError.EMPTY_INPUT
            )
        }
        val outputLanguage = settings.sourceLang.trim()
        if (outputLanguage.isEmpty() || outputLanguage.equals(
                Languages.AUTO.code,
                ignoreCase = true,
            )
        ) {
            return InputTranslationPreparation.Rejected(
                InputTranslationPreparationError.AUTO_TARGET
            )
        }

        val inputLanguage = settings.targetLang.trim().ifEmpty { Languages.AUTO.code }
        val unsupported = when (settings.translatorEngine) {
            TranslatorEngine.YOUDAO_PICTRANS -> true
            TranslatorEngine.GOOGLE_ML_KIT ->
                inputLanguage.equals(Languages.AUTO.code, ignoreCase = true)
            TranslatorEngine.LOCAL_SAKURA ->
                !RoutingTranslator.supportsSakuraSource(inputLanguage) ||
                    !RoutingTranslator.supportsSakuraTarget(outputLanguage)
            else -> false
        }
        if (unsupported) {
            return InputTranslationPreparation.Rejected(
                InputTranslationPreparationError.UNSUPPORTED_ENGINE
            )
        }

        return InputTranslationPreparation.Ready(
            sourceText = text,
            requestSettings = settings.copy(
                sourceLang = inputLanguage,
                targetLang = outputLanguage,
                translationContextMode = TranslationContextMode.FAST_PER_SEGMENT,
                openAiRequestOptions = settings.openAiRequestOptions.copy(
                    sendScreenImage = false,
                ),
                runtimeTranslationContext = "",
                runtimeTranslationPromptContext = RuntimeTranslationPromptContext(),
                runtimeTranslationVisualContext = null,
            ),
        )
    }
}
