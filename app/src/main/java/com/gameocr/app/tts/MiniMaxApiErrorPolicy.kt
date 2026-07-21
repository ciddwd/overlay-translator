package com.gameocr.app.tts

internal enum class MiniMaxApiErrorReason {
    RETRY_LATER,
    REQUEST_TIMEOUT,
    RATE_LIMIT,
    INVALID_API_KEY,
    INSUFFICIENT_BALANCE,
    INPUT_SENSITIVE,
    OUTPUT_SENSITIVE,
    TOKEN_LIMIT,
    CONNECTION_LIMIT,
    INVALID_CHARACTERS,
    ASR_SIMILARITY_MISMATCH,
    PROMPT_SIMILARITY_MISMATCH,
    INVALID_PARAMETERS,
    INVALID_CLONE_SAMPLE_OR_VOICE_ID,
    INVALID_VOICE_DURATION,
    VOICE_CLONING_DISABLED,
    DUPLICATE_VOICE_ID,
    VOICE_ID_ACCESS_DENIED,
    BURST_RATE_LIMIT,
    PROMPT_AUDIO_TOO_LONG,
    PLAN_RESOURCE_LIMIT,
}

internal class MiniMaxApiException(
    val statusCode: Int,
    statusMessage: String,
) : RuntimeException(miniMaxApiExceptionMessage(statusCode, statusMessage)) {
    val statusMessage: String = normalizedMiniMaxStatusMessage(statusMessage)
}

internal fun miniMaxApiErrorReason(statusCode: Int): MiniMaxApiErrorReason? = when (statusCode) {
    1000, 1024, 1033 -> MiniMaxApiErrorReason.RETRY_LATER
    1001 -> MiniMaxApiErrorReason.REQUEST_TIMEOUT
    1002 -> MiniMaxApiErrorReason.RATE_LIMIT
    1004, 2049 -> MiniMaxApiErrorReason.INVALID_API_KEY
    1008 -> MiniMaxApiErrorReason.INSUFFICIENT_BALANCE
    1026 -> MiniMaxApiErrorReason.INPUT_SENSITIVE
    1027 -> MiniMaxApiErrorReason.OUTPUT_SENSITIVE
    1039 -> MiniMaxApiErrorReason.TOKEN_LIMIT
    1041 -> MiniMaxApiErrorReason.CONNECTION_LIMIT
    1042 -> MiniMaxApiErrorReason.INVALID_CHARACTERS
    1043 -> MiniMaxApiErrorReason.ASR_SIMILARITY_MISMATCH
    1044 -> MiniMaxApiErrorReason.PROMPT_SIMILARITY_MISMATCH
    2013 -> MiniMaxApiErrorReason.INVALID_PARAMETERS
    20132 -> MiniMaxApiErrorReason.INVALID_CLONE_SAMPLE_OR_VOICE_ID
    2037 -> MiniMaxApiErrorReason.INVALID_VOICE_DURATION
    2038 -> MiniMaxApiErrorReason.VOICE_CLONING_DISABLED
    2039 -> MiniMaxApiErrorReason.DUPLICATE_VOICE_ID
    2042 -> MiniMaxApiErrorReason.VOICE_ID_ACCESS_DENIED
    2045 -> MiniMaxApiErrorReason.BURST_RATE_LIMIT
    2048 -> MiniMaxApiErrorReason.PROMPT_AUDIO_TOO_LONG
    2056 -> MiniMaxApiErrorReason.PLAN_RESOURCE_LIMIT
    else -> null
}

internal fun Throwable.miniMaxApiExceptionOrNull(): MiniMaxApiException? =
    generateSequence(this) { current -> current.cause }
        .filterIsInstance<MiniMaxApiException>()
        .firstOrNull()

private fun miniMaxApiExceptionMessage(statusCode: Int, statusMessage: String): String = buildString {
    append("MiniMax API error ")
    append(statusCode)
    normalizedMiniMaxStatusMessage(statusMessage).takeIf(String::isNotBlank)?.let { message ->
        append(": ")
        append(message)
    }
}

private fun normalizedMiniMaxStatusMessage(raw: String): String =
    raw.replace(Regex("\\s+"), " ").trim().take(200)
