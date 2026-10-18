package com.gameocr.app.data

/** Settings-screen draft only. It never persists until the user saves the whole form. */
data class AsrCredentialDraft(
    val connections: AsrCloudConnections,
    val dashScopeApiKey: String,
) {
    fun applyTo(settings: Settings, normalize: Boolean = false): Settings {
        fun value(raw: String) = if (normalize) raw.trim() else raw
        return settings.copy(
            asrDashScopeApiKey = value(dashScopeApiKey),
            // Model selectors save independently; an older credential draft must not reset them.
            asrCloudConnections = settings.asrCloudConnections.copy(
                tencentAppId = value(connections.tencentAppId),
                volcApiKey = value(connections.volcApiKey),
                openAiBaseUrl = value(connections.openAiBaseUrl),
                openAiApiKey = value(connections.openAiApiKey),
                openAiModel = value(connections.openAiModel),
            ),
        )
    }

    override fun toString(): String = "AsrCredentialDraft(private)"

    companion object {
        fun from(settings: Settings) = AsrCredentialDraft(settings.asrCloudConnections, settings.asrDashScopeApiKey)
    }
}
