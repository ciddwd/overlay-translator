package com.gameocr.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class PresetGesture(val symbol: String) {
    UP("↑"), DOWN("↓"), LEFT("←"), RIGHT("→"),
    RIGHT_DOWN("→↓"), DOWN_RIGHT("↓→"), S("S"), O("O"),
}

@Serializable
enum class GestureAction(val targetSkill: FloatingSkill? = null) {
    NONE,
    SWITCH_FULL_SCREEN(FloatingSkill.FULL_SCREEN),
    SWITCH_WORD_SELECT(FloatingSkill.WORD_SELECT),
    SWITCH_LOOP(FloatingSkill.LOOP),
    SWITCH_INPUT_TRANSLATE(FloatingSkill.INPUT_TRANSLATE),
    SWITCH_GESTURE(FloatingSkill.GESTURE),
    TRANSLATE_SCREEN, SELECT_WORD, TOGGLE_LOOP, TRANSLATE_INPUT,
    PICK_REGION, LANGUAGE_PAIR, PRESET_SWITCH, SETTINGS, HOME,
}

object GestureBindings {
    // Keep the old serialized mode values readable, but never offer or execute them.
    val actions = GestureAction.entries.filter { it.targetSkill == null }
    val defaults: Map<PresetGesture, GestureAction> = linkedMapOf(
        PresetGesture.UP to GestureAction.TRANSLATE_SCREEN,
        PresetGesture.DOWN to GestureAction.SELECT_WORD,
        PresetGesture.LEFT to GestureAction.LANGUAGE_PAIR,
        PresetGesture.RIGHT to GestureAction.PRESET_SWITCH,
        PresetGesture.RIGHT_DOWN to GestureAction.PICK_REGION,
        PresetGesture.DOWN_RIGHT to GestureAction.TRANSLATE_INPUT,
        PresetGesture.S to GestureAction.SETTINGS,
        PresetGesture.O to GestureAction.TOGGLE_LOOP,
    )

    fun normalize(bindings: Map<PresetGesture, GestureAction>): Map<PresetGesture, GestureAction> =
        defaults + bindings.mapValues { (_, action) ->
            if (action.targetSkill == null) action else GestureAction.NONE
        }

    fun fromNames(bindings: Map<String, String>): Map<PresetGesture, GestureAction> =
        normalize(bindings.entries.mapNotNull { (gesture, action) ->
            val id = PresetGesture.entries.find { it.name == gesture } ?: return@mapNotNull null
            val target = GestureAction.entries.find { it.name == action } ?: return@mapNotNull null
            id to target
        }.toMap())
}
