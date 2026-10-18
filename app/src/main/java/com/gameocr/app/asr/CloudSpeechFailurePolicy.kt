package com.gameocr.app.asr

import com.gameocr.app.R
import com.gameocr.app.data.AsrCloudProvider

/** Classify explicit provider codes; never infer billing failures from connection failures. */
internal object CloudSpeechFailurePolicy {
    fun native(provider: AsrCloudProvider, code: Int, translatesSpeech: Boolean = false): CloudAsrFailure {
        if (provider == AsrCloudProvider.TENCENT && translatesSpeech) return when (code) {
            6000, 6006 -> CloudAsrFailure.RATE_LIMIT
            6001, 6010, 6011 -> CloudAsrFailure.CONFIGURATION
            6002 -> CloudAsrFailure.AUTH
            6003 -> CloudAsrFailure.NOT_ACTIVATED
            6004 -> CloudAsrFailure.QUOTA
            6005 -> CloudAsrFailure.OVERDUE
            6007 -> CloudAsrFailure.AUDIO
            6008, 6009 -> CloudAsrFailure.CONNECTION
            6012, 6013, 6014, 7000 -> CloudAsrFailure.SERVICE
            else -> CloudAsrFailure.UNKNOWN
        }
        return when (provider) {
        AsrCloudProvider.TENCENT -> when (code) {
            4000, 4006 -> CloudAsrFailure.RATE_LIMIT
            4001, 4010 -> CloudAsrFailure.CONFIGURATION
            4002 -> CloudAsrFailure.AUTH
            4003 -> CloudAsrFailure.NOT_ACTIVATED
            4004 -> CloudAsrFailure.QUOTA
            4005 -> CloudAsrFailure.OVERDUE
            4007 -> CloudAsrFailure.AUDIO
            4008, 4009 -> CloudAsrFailure.CONNECTION
            5000, 5001, 5002 -> CloudAsrFailure.SERVICE
            6001 -> CloudAsrFailure.REGION
            else -> CloudAsrFailure.UNKNOWN
        }
        AsrCloudProvider.VOLCENGINE -> when (code) {
            45000001, 45000003 -> CloudAsrFailure.CONFIGURATION
            45000002, 45000151 -> CloudAsrFailure.AUDIO
            45000081 -> CloudAsrFailure.CONNECTION
            in 55000000..55999999 -> CloudAsrFailure.SERVICE
            else -> CloudAsrFailure.UNKNOWN
        }
        else -> CloudAsrFailure.UNKNOWN
    }

    }

    fun http(status: Int?): CloudAsrFailure = when (status) {
        null, 408, 504 -> CloudAsrFailure.CONNECTION
        401, 403 -> CloudAsrFailure.AUTH
        402 -> CloudAsrFailure.QUOTA
        429 -> CloudAsrFailure.RATE_LIMIT
        400, 404, 405, 413, 415, 422 -> CloudAsrFailure.CONFIGURATION
        in 500..599 -> CloudAsrFailure.SERVICE
        else -> CloudAsrFailure.UNKNOWN
    }

    fun aliyun(code: String): CloudAsrFailure = when (code.lowercase()) {
        "invalidapikey", "invalid_api_key", "authenticationerror", "accessdenied", "access_denied" -> CloudAsrFailure.AUTH
        "arrearage", "prepaidbilloverdue", "postpaidbilloverdue" -> CloudAsrFailure.OVERDUE
        "commoditynotpurchased" -> CloudAsrFailure.NOT_ACTIVATED
        "invalidparameter", "invalid_parameter", "invalid_request_error" -> CloudAsrFailure.CONFIGURATION
        "audio.decodererror" -> CloudAsrFailure.AUDIO
        else -> when {
            code.startsWith("Throttling", ignoreCase = true) -> CloudAsrFailure.RATE_LIMIT
            code.startsWith("InternalError", ignoreCase = true) -> CloudAsrFailure.SERVICE
            else -> CloudAsrFailure.UNKNOWN
        }
    }

    fun message(failure: CloudAsrFailure): Int = when (failure) {
        CloudAsrFailure.AUTH -> R.string.asr_error_cloud_auth
        CloudAsrFailure.CONNECTION -> R.string.asr_error_cloud_connection
        CloudAsrFailure.QUOTA -> R.string.asr_error_cloud_quota
        CloudAsrFailure.NOT_ACTIVATED -> R.string.asr_error_cloud_not_activated
        CloudAsrFailure.OVERDUE -> R.string.asr_error_cloud_overdue
        CloudAsrFailure.RATE_LIMIT -> R.string.asr_error_cloud_rate_limit
        CloudAsrFailure.CONFIGURATION -> R.string.asr_error_cloud_parameters
        CloudAsrFailure.AUDIO -> R.string.asr_error_cloud_audio
        CloudAsrFailure.SERVICE -> R.string.asr_error_cloud_service
        CloudAsrFailure.REGION -> R.string.asr_error_cloud_region
        CloudAsrFailure.UNKNOWN -> R.string.asr_error_cloud_unknown
    }
}
