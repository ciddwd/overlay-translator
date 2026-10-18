package com.gameocr.app.asr

import androidx.annotation.StringRes
import com.gameocr.app.R
import com.gameocr.app.data.AsrLanguagePolicy
import com.gameocr.app.data.Settings
import com.gameocr.app.data.AsrCloudProvider

internal enum class SpeechStartBlock(@StringRes val message: Int) {
    CLOUD_LANGUAGE_UNSUPPORTED(R.string.asr_error_cloud_parameters),
    MODEL_MISSING(R.string.asr_error_model_unavailable),
    CLOUD_NOT_CONFIGURED(R.string.asr_error_cloud_not_configured),
    CLOUD_CONFIGURATION_INCOMPLETE(R.string.asr_error_cloud_configuration_incomplete),
}

internal object SpeechStartPolicy {
    /**
     * Checked before any permission or consent prompt, and again when the session starts:
     * on-device engines need an installed model, the cloud engine needs its API key.
     * [modelInstalled] is null when the selected engine has no on-device model.
     */
    fun blockReason(settings: Settings, modelInstalled: Boolean?): SpeechStartBlock? = when {
        settings.asrEngine.isCloud && settings.asrCloudProvider == AsrCloudProvider.TENCENT &&
            settings.asrCloudConnections.tencentModel.translatesSpeech &&
            !com.gameocr.app.data.TencentSpeechLanguagePolicy.supports(
                AsrLanguagePolicy.effective(settings.asrEngine, settings.asrLanguage, settings.asrCloudModel, settings.asrCloudProvider, settings.asrCloudConnections), settings.targetLang) ->
            SpeechStartBlock.CLOUD_LANGUAGE_UNSUPPORTED
        settings.asrEngine.isCloud && settings.asrCloudProvider != AsrCloudProvider.ALIYUN ->
            SpeechStartBlock.CLOUD_CONFIGURATION_INCOMPLETE.takeIf { !configurationReady(settings) }
        settings.asrEngine.isCloud ->
            SpeechStartBlock.CLOUD_NOT_CONFIGURED.takeIf { settings.asrDashScopeApiKey.isBlank() }
        modelInstalled != true -> SpeechStartBlock.MODEL_MISSING
        else -> null
    }

    private fun configurationReady(settings: Settings): Boolean = settings.asrCloudConnections.let {
        when (settings.asrCloudProvider) {
            AsrCloudProvider.ALIYUN -> settings.asrDashScopeApiKey.isNotBlank()
            AsrCloudProvider.TENCENT -> it.tencentAppId.trim().matches(Regex("[0-9]+")) &&
                settings.tencentSecretId.isNotBlank() && settings.tencentSecretKey.isNotBlank()
            AsrCloudProvider.VOLCENGINE -> it.volcApiKey.isNotBlank()
            AsrCloudProvider.OPENAI_COMPATIBLE -> it.openAiApiKey.isNotBlank() && it.openAiModel.isNotBlank() &&
                OpenAiAsrProtocol.endpoint(it.openAiBaseUrl) != null
        }
    }

    /** Identifies the recognizer a session uses; a change restarts a running session. */
    fun engineKey(settings: Settings): String {
        val model = AsrModelId.forSettings(settings.asrEngine, settings.asrWhisperVariant)
        val language = AsrLanguagePolicy.effective(settings.asrEngine, settings.asrLanguage, settings.asrCloudModel, settings.asrCloudProvider, settings.asrCloudConnections).code
        if (settings.asrEngine.isCloud && settings.asrCloudProvider != AsrCloudProvider.ALIYUN) {
            val connection = settings.asrCloudConnections
            val selected = when (settings.asrCloudProvider) {
                AsrCloudProvider.TENCENT -> listOf(connection.tencentAppId.trim(), settings.tencentSecretId.trim(), settings.tencentSecretKey.trim(), connection.tencentModel.modelId,
                    if (connection.tencentModel.translatesSpeech) com.gameocr.app.data.TencentSpeechLanguagePolicy.target(settings.targetLang) else "")
                AsrCloudProvider.VOLCENGINE -> listOf(connection.volcApiKey.trim(), connection.volcResource.resourceId)
                AsrCloudProvider.OPENAI_COMPATIBLE -> listOf(connection.openAiBaseUrl.trim(), connection.openAiApiKey.trim(), connection.openAiModel.trim())
                else -> emptyList()
            }
            return "${settings.asrCloudProvider.name}:$language:${selected.hashCode()}"
        }
        // The key is only compared, never logged with its value; a hash marks a changed key.
        val credential = if (settings.asrEngine.isCloud) ":" + settings.asrDashScopeApiKey.trim().hashCode() else ""
        val cloudModel = if (settings.asrEngine.isCloud) ":${settings.asrCloudModel.modelId}" else ""
        val target = if (settings.asrEngine.isCloud && settings.asrCloudModel.translatesSpeech)
            ":${settings.targetLang.trim()}" else ""
        return "${model?.name ?: settings.asrEngine.name}$cloudModel:$language$credential$target"
    }
}
