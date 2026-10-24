package com.gameocr.app.download

import com.gameocr.app.BuildConfig
import com.gameocr.app.asr.AsrModelId
import com.gameocr.app.asr.AsrModelInstaller
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.llm.LlmModelInstaller
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.llm.LocalLlmDeviceCapability
import com.gameocr.app.ocr.MangaOcrModelInstaller
import com.gameocr.app.ocr.OrientationModelInstaller
import com.gameocr.app.ocr.PaddleModelInstaller
import javax.inject.Inject
import javax.inject.Singleton

enum class ModelReadinessState {
    READY,
    MISSING,
    UNSUPPORTED,
}

data class ModelReadiness(
    val spec: ModelDownloadSpec,
    val installed: Boolean,
    val supported: Boolean,
    val totalBytes: Long = 0L,
    /**
     * Installed and usable, but an optional file added later is missing. Downloading the same
     * spec again fetches only the missing files; nothing about [installed] or [ready] changes.
     */
    val upgradeAvailable: Boolean = false,
) {
    val state: ModelReadinessState
        get() = when {
            !supported -> ModelReadinessState.UNSUPPORTED
            installed -> ModelReadinessState.READY
            else -> ModelReadinessState.MISSING
        }

    val ready: Boolean
        get() = state == ModelReadinessState.READY

    val downloadable: Boolean
        get() = state == ModelReadinessState.MISSING
}

/**
 * Shared model readiness boundary for onboarding, settings and download completion checks.
 * Installers remain responsible for validating their own file formats; this class makes every
 * caller consume those validations and the same runtime compatibility rule.
 */
@Singleton
class ModelReadinessChecker @Inject constructor(
    private val paddleInstaller: PaddleModelInstaller,
    private val mangaOcrInstaller: MangaOcrModelInstaller,
    private val orientationModelInstaller: OrientationModelInstaller,
    private val llmInstaller: LlmModelInstaller,
    private val localLlmDeviceCapability: LocalLlmDeviceCapability,
    private val asrModelInstaller: AsrModelInstaller,
) {
    fun check(spec: ModelDownloadSpec): ModelReadiness =
        modelReadinessWithDependencies(spec, ::checkArtifact)

    /** Downloads skip valid files independently even when another required component is missing. */
    fun checkArtifact(spec: ModelDownloadSpec): ModelReadiness = when (spec.type) {
        ModelDownloadType.PADDLE -> {
            val files = paddleInstaller.checkInstalled(PaddleModelVersion.valueOf(spec.variant))
            ModelReadiness(
                spec = spec,
                installed = files != null,
                supported = true,
                totalBytes = files?.totalBytes ?: 0L,
            )
        }
        ModelDownloadType.MANGA_OCR -> {
            val files = mangaOcrInstaller.checkInstalled()
            ModelReadiness(
                spec = spec,
                installed = files != null,
                supported = true,
                totalBytes = files?.totalBytes ?: 0L,
                // Installs from before the int8 draft decoder existed lack only that file.
                upgradeAvailable = files != null && BuildConfig.MANGA_OCR_SPECULATIVE && files.draftDecoder == null,
            )
        }
        ModelDownloadType.ORIENTATION -> {
            val files = orientationModelInstaller.checkFullyInstalled()
            ModelReadiness(
                spec = spec,
                installed = files != null,
                supported = true,
                totalBytes = files?.totalBytes ?: 0L,
            )
        }
        ModelDownloadType.LLM -> {
            val file = llmInstaller.checkInstalled(LlmModelKind.valueOf(spec.variant))
            ModelReadiness(
                spec = spec,
                installed = file != null,
                supported = localLlmDeviceCapability.isSupported(),
                totalBytes = file?.length() ?: 0L,
            )
        }
        ModelDownloadType.ASR -> {
            val model = AsrModelId.valueOf(spec.variant)
            ModelReadiness(
                spec = spec,
                installed = asrModelInstaller.checkInstalled(model) != null,
                supported = true,
                totalBytes = asrModelInstaller.installedBytes(model),
            )
        }
    }

    fun asr(model: AsrModelId): ModelReadiness = check(ModelDownloadSpec.asr(model))

    fun paddle(version: PaddleModelVersion): ModelReadiness =
        check(ModelDownloadSpec.paddle(version))

    fun mangaOcr(): ModelReadiness = check(ModelDownloadSpec.mangaOcr())

    fun orientation(): ModelReadiness = check(ModelDownloadSpec.orientation())

    fun llm(kind: LlmModelKind): ModelReadiness = check(ModelDownloadSpec.llm(kind))

    fun isLocalLlmSupported(): Boolean = localLlmDeviceCapability.isSupported()
}
