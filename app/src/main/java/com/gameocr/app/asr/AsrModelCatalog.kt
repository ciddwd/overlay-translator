package com.gameocr.app.asr

import com.gameocr.app.data.AsrEngineKind
import com.gameocr.app.data.AsrWhisperVariant

/**
 * Pinned sherpa-onnx speech models. Sources (verified HTTP 200 and SHA-256 on 2026-10-17):
 * - https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17
 * - https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20
 * - https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny
 * - https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base
 *
 * URLs use the repository commit, never `main`, so the hashes below cannot drift.
 */
data class AsrModelFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

enum class AsrModelId(
    val displayName: String,
    val repo: String,
    val revision: String,
    val files: List<AsrModelFile>,
) {
    SENSE_VOICE(
        displayName = "SenseVoice Small INT8",
        repo = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
        revision = "2365baeacb507f821a0c8120fcee3d484dba7a07",
        files = listOf(
            AsrModelFile("model.int8.onnx", 239_233_841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
            AsrModelFile("tokens.txt", 315_894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
        ),
    ),

    /** INT8 encoder/joiner with the float decoder, matching the upstream Android example. */
    ZIPFORMER(
        displayName = "Streaming Zipformer INT8",
        repo = "csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
        revision = "98590b7ed6443e77b714204da2757d75e1a642f4",
        files = listOf(
            AsrModelFile("encoder-epoch-99-avg-1.int8.onnx", 181_895_032L, "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b"),
            AsrModelFile("decoder-epoch-99-avg-1.onnx", 13_876_452L, "2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d"),
            AsrModelFile("joiner-epoch-99-avg-1.int8.onnx", 3_228_404L, "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0"),
            AsrModelFile("tokens.txt", 56_317L, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"),
        ),
    ),
    WHISPER_TINY(
        displayName = "Whisper tiny",
        repo = "csukuangfj/sherpa-onnx-whisper-tiny",
        revision = "65176e2deb88badc814a94058666cadccc29b61c",
        files = listOf(
            AsrModelFile("tiny-encoder.int8.onnx", 12_937_772L, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"),
            AsrModelFile("tiny-decoder.int8.onnx", 89_855_401L, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"),
            AsrModelFile("tiny-tokens.txt", 816_730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
        ),
    ),
    WHISPER_BASE(
        displayName = "Whisper base",
        repo = "csukuangfj/sherpa-onnx-whisper-base",
        revision = "bb53ee204431c90d314c1cc08d28d23e5b7927cc",
        files = listOf(
            AsrModelFile("base-encoder.int8.onnx", 29_120_534L, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
            AsrModelFile("base-decoder.int8.onnx", 130_672_026L, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
            AsrModelFile("base-tokens.txt", 816_730L, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
        ),
    );

    val totalBytes: Long get() = files.sumOf { it.bytes }

    fun officialUrl(file: AsrModelFile): String = "https://huggingface.co/$repo/resolve/$revision/${file.name}"

    fun mirrorUrl(file: AsrModelFile): String = "https://hf-mirror.com/$repo/resolve/$revision/${file.name}"

    companion object {
        /** The on-device model for an engine selection, or null for a cloud engine. */
        fun forSettings(engine: AsrEngineKind, variant: AsrWhisperVariant): AsrModelId? = when (engine) {
            AsrEngineKind.SENSE_VOICE -> SENSE_VOICE
            AsrEngineKind.ZIPFORMER -> ZIPFORMER
            AsrEngineKind.WHISPER -> when (variant) {
                AsrWhisperVariant.TINY -> WHISPER_TINY
                AsrWhisperVariant.BASE -> WHISPER_BASE
            }
            AsrEngineKind.PARAFORMER_CLOUD -> null
        }
    }
}

/** Bundled with the app (sherpa-onnx `asr-models` release asset), so it is never downloaded. */
internal object AsrVadModel {
    const val ASSET_PATH = "asr/silero_vad.onnx"
}
