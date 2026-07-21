package com.gameocr.app.tts

import android.content.Context
import com.gameocr.app.R

internal fun Context.miniMaxApiErrorMessage(error: MiniMaxApiException): String =
    miniMaxApiErrorMessage(error.statusCode, error.statusMessage)

internal fun Context.miniMaxApiErrorMessage(statusCode: Int, statusMessage: String): String {
    val stringRes = when (miniMaxApiErrorReason(statusCode)) {
        MiniMaxApiErrorReason.RETRY_LATER -> R.string.settings_tts_minimax_error_retry_later
        MiniMaxApiErrorReason.REQUEST_TIMEOUT -> R.string.settings_tts_minimax_error_request_timeout
        MiniMaxApiErrorReason.RATE_LIMIT -> R.string.settings_tts_minimax_error_rate_limit
        MiniMaxApiErrorReason.INVALID_API_KEY -> R.string.settings_tts_minimax_error_api_key
        MiniMaxApiErrorReason.INSUFFICIENT_BALANCE -> R.string.settings_tts_minimax_error_balance
        MiniMaxApiErrorReason.INPUT_SENSITIVE -> R.string.settings_tts_minimax_error_input_sensitive
        MiniMaxApiErrorReason.OUTPUT_SENSITIVE -> R.string.settings_tts_minimax_error_output_sensitive
        MiniMaxApiErrorReason.TOKEN_LIMIT -> R.string.settings_tts_minimax_error_token_limit
        MiniMaxApiErrorReason.CONNECTION_LIMIT -> R.string.settings_tts_minimax_error_connection_limit
        MiniMaxApiErrorReason.INVALID_CHARACTERS -> R.string.settings_tts_minimax_error_invalid_characters
        MiniMaxApiErrorReason.ASR_SIMILARITY_MISMATCH ->
            R.string.settings_tts_minimax_error_asr_similarity
        MiniMaxApiErrorReason.PROMPT_SIMILARITY_MISMATCH ->
            R.string.settings_tts_minimax_error_prompt_similarity
        MiniMaxApiErrorReason.INVALID_PARAMETERS -> R.string.settings_tts_minimax_error_parameters
        MiniMaxApiErrorReason.INVALID_CLONE_SAMPLE_OR_VOICE_ID ->
            R.string.settings_tts_minimax_error_clone_sample_or_voice_id
        MiniMaxApiErrorReason.INVALID_VOICE_DURATION ->
            R.string.settings_tts_minimax_error_voice_duration
        MiniMaxApiErrorReason.VOICE_CLONING_DISABLED ->
            R.string.settings_tts_minimax_error_cloning_disabled
        MiniMaxApiErrorReason.DUPLICATE_VOICE_ID ->
            R.string.settings_tts_minimax_error_duplicate_voice_id
        MiniMaxApiErrorReason.VOICE_ID_ACCESS_DENIED ->
            R.string.settings_tts_minimax_error_voice_id_access
        MiniMaxApiErrorReason.BURST_RATE_LIMIT -> R.string.settings_tts_minimax_error_burst_rate
        MiniMaxApiErrorReason.PROMPT_AUDIO_TOO_LONG ->
            R.string.settings_tts_minimax_prompt_audio_too_long
        MiniMaxApiErrorReason.PLAN_RESOURCE_LIMIT -> R.string.settings_tts_minimax_error_plan_limit
        null -> return if (statusMessage.isBlank()) {
            getString(R.string.settings_tts_minimax_error_unknown, statusCode)
        } else {
            getString(
                R.string.settings_tts_minimax_error_unknown_detail,
                statusCode,
                statusMessage,
            )
        }
    }
    return getString(stringRes)
}
