package com.gameocr.app.service

import androidx.annotation.StringRes
import com.gameocr.app.R
import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.data.InputTranslationDoubleAction

/** Only input translation has a separate double-tap action. */
@StringRes
internal fun floatingSkillSwitchHintRes(
    skill: FloatingSkill,
    inputDoubleAction: InputTranslationDoubleAction? = null,
): Int = when (skill) {
    FloatingSkill.FULL_SCREEN -> R.string.toast_skill_switched_full_screen
    FloatingSkill.WORD_SELECT -> R.string.toast_skill_switched_word_select
    FloatingSkill.LOOP -> R.string.toast_skill_switched_loop
    FloatingSkill.INPUT_TRANSLATE -> when (inputDoubleAction) {
        InputTranslationDoubleAction.WORD_SELECT -> R.string.toast_skill_switched_input_translate_word_select
        InputTranslationDoubleAction.FULL_SCREEN, null -> R.string.toast_skill_switched_input_translate
    }
    FloatingSkill.GESTURE -> R.string.toast_skill_switched_gesture
    FloatingSkill.SPEECH -> R.string.toast_skill_switched_speech
}
