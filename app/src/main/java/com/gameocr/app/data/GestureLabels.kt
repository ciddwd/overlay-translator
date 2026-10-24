package com.gameocr.app.data

import com.gameocr.app.R

val GestureAction.labelRes: Int get() = when (this) {
    GestureAction.TRANSLATE_SCREEN -> R.string.gesture_action_translate_screen
    GestureAction.SELECT_WORD -> R.string.gesture_action_select_word
    GestureAction.TOGGLE_LOOP -> R.string.gesture_action_toggle_loop
    GestureAction.TOGGLE_SPEECH -> R.string.gesture_action_toggle_speech
    GestureAction.TRANSLATE_INPUT -> R.string.gesture_action_translate_input
    GestureAction.PICK_REGION -> R.string.menu_pick_region
    GestureAction.LANGUAGE_PAIR -> R.string.menu_language_pair
    GestureAction.PRESET_SWITCH -> R.string.menu_preset_switch
    GestureAction.SETTINGS -> R.string.gesture_action_settings
    GestureAction.HOME -> R.string.menu_open_main
    else -> R.string.gesture_action_none
}

val PresetGesture.labelRes: Int get() = when (this) {
    PresetGesture.UP -> R.string.gesture_up
    PresetGesture.DOWN -> R.string.gesture_down
    PresetGesture.LEFT -> R.string.gesture_left
    PresetGesture.RIGHT -> R.string.gesture_right
    PresetGesture.RIGHT_DOWN -> R.string.gesture_right_down
    PresetGesture.DOWN_RIGHT -> R.string.gesture_down_right
    PresetGesture.S -> R.string.gesture_s
    PresetGesture.O -> R.string.gesture_o
}
