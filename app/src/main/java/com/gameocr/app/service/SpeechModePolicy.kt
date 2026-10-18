package com.gameocr.app.service

import com.gameocr.app.asr.SpeechStartPolicy
import com.gameocr.app.data.Settings

internal object SpeechModePolicy {
    /** A running session restarts from idle when the engine, model, language or cloud key changes. */
    fun sessionKey(settings: Settings): String = SpeechStartPolicy.engineKey(settings)
}
