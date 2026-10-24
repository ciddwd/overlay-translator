package com.gameocr.app.data

import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
enum class AsrHintMode { AUTO, PROMPT, KEYWORDS, PROMPT_HOTWORDS }

/** Optional recognition hints, independent from the translator's prompt and context. */
@Serializable
data class AsrHintSettings(
    val prompt: String = "",
    val hotwords: String = "",
    val useGlossary: Boolean = false,
    val openAiMode: AsrHintMode = AsrHintMode.AUTO,
    val vocabularyIds: Map<String, String> = emptyMap(),
)

data class AsrHintCapabilities(
    val mode: AsrHintMode = AsrHintMode.AUTO,
    val prompt: Boolean = false,
    val hotwords: Boolean = false,
    val vocabulary: Boolean = false,
) { val available get() = prompt || hotwords || vocabulary }

object AsrHintPolicy {
    fun capabilities(s: Settings): AsrHintCapabilities {
        if (!s.asrEngine.isCloud) return AsrHintCapabilities()
        return when (s.asrCloudProvider) {
            AsrCloudProvider.OPENAI_COMPATIBLE -> openAi(s.asrCloudConnections, s.asrHints.openAiMode)
            AsrCloudProvider.TENCENT -> AsrHintCapabilities(hotwords = !s.asrCloudConnections.tencentModel.translatesSpeech)
            AsrCloudProvider.VOLCENGINE -> AsrHintCapabilities(prompt = true, hotwords = true)
            AsrCloudProvider.ALIYUN -> when (s.asrCloudModel) {
                AsrCloudModel.PARAFORMER, AsrCloudModel.FUN_ASR -> AsrHintCapabilities(vocabulary = true)
                AsrCloudModel.QWEN_AUDIO_STREAMING -> AsrHintCapabilities(hotwords = true, vocabulary = true)
                else -> AsrHintCapabilities()
            }
        }
    }

    fun openAi(c: AsrCloudConnections, configured: AsrHintMode = AsrHintMode.AUTO): AsrHintCapabilities {
        val model = c.openAiModel.trim()
        val mode = if (configured != AsrHintMode.AUTO) configured else {
            val host = runCatching { URI(c.openAiBaseUrl.trim()).host }.getOrNull()
            if (!host.equals("api.openai.com", ignoreCase = true)) AsrHintMode.AUTO
            else when {
                model == "gpt-transcribe" || model.startsWith("gpt-transcribe-") ||
                    model == "gpt-live-transcribe" || model.startsWith("gpt-live-transcribe-") -> AsrHintMode.KEYWORDS
                model == "whisper-1" || model.startsWith("gpt-4o-transcribe") && !model.contains("diarize") ||
                    model.startsWith("gpt-4o-mini-transcribe") -> AsrHintMode.PROMPT_HOTWORDS
                else -> AsrHintMode.AUTO
            }
        }
        return AsrHintCapabilities(mode, mode != AsrHintMode.AUTO,
            mode in setOf(AsrHintMode.KEYWORDS, AsrHintMode.PROMPT_HOTWORDS))
    }
}
