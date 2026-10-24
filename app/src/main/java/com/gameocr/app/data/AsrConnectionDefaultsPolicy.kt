package com.gameocr.app.data

/** Remove the old whole-form autofill only when it has never been configured with a key. */
internal object AsrConnectionDefaultsPolicy {
    fun clearUnconfiguredLegacyDefaults(c: AsrCloudConnections): AsrCloudConnections =
        if (c.openAiApiKey.isBlank() && c.openAiBaseUrl == "https://api.siliconflow.cn/v1" &&
            c.openAiModel == "FunAudioLLM/SenseVoiceSmall") c.copy(openAiBaseUrl = "", openAiModel = "") else c
}
