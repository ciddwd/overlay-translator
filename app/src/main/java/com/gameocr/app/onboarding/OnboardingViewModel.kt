package com.gameocr.app.onboarding

import androidx.lifecycle.ViewModel
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.download.ModelDownloadManager
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.llm.LlmModelInstaller
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.ocr.MangaOcrModelInstaller
import com.gameocr.app.ocr.PaddleModelInstaller
import com.gameocr.app.translate.RoutingTranslator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val routingTranslator: RoutingTranslator,
    private val paddleModelInstaller: PaddleModelInstaller,
    private val mangaOcrModelInstaller: MangaOcrModelInstaller,
    private val llmModelInstaller: LlmModelInstaller,
    private val modelDownloadManager: ModelDownloadManager,
) : ViewModel() {
    suspend fun loadDraft(firstRun: Boolean): OnboardingDraft =
        if (firstRun) OnboardingDraft()
        else OnboardingPolicy.fromSettings(settingsRepository.get())

    suspend fun save(draft: OnboardingDraft) {
        settingsRepository.update { current -> OnboardingPolicy.apply(current, draft) }
    }

    suspend fun downloadMlKitLanguagePair(sourceLang: String, targetLang: String) {
        routingTranslator.downloadMlKitLanguagePair(sourceLang, targetLang)
    }

    fun recommendedModelsReadiness(draft: OnboardingDraft): RecommendedModelsReadiness {
        val paddleVersion = OnboardingPolicy.recommendedPaddleModelVersion(draft)
            ?.takeIf { OnboardingPolicy.shouldRecommendPaddleOcr(draft) }
        val includeHyMt2 = OnboardingPolicy.usesHyMt2MangaTranslation(draft)
        return RecommendedModelsReadiness(
            paddleVersion = paddleVersion,
            paddleReady = paddleVersion == null ||
                paddleModelInstaller.checkInstalled(paddleVersion) != null,
            includeHyMt2 = includeHyMt2,
            hyMt2Ready = !includeHyMt2 ||
                llmModelInstaller.checkInstalled(LlmModelKind.HY_MT2_1_8B_Q4_K_M) != null,
        )
    }

    suspend fun downloadMissingRecommendedModels(
        draft: OnboardingDraft,
        onProgress: (String) -> Unit,
    ) {
        val specs = recommendedModelsDownloadSpecs(recommendedModelsReadiness(draft))
        if (specs.isNotEmpty()) {
            modelDownloadManager.enqueueIndependentlyAndAwait(specs, onProgress)
        }
    }

    suspend fun missingMlKitLanguageModels(
        sourceLang: String,
        targetLang: String,
    ): Set<String> = routingTranslator.getMissingMlKitLanguageModels(sourceLang, targetLang)

    fun mangaOfflineModelReadiness(includeSakura: Boolean): MangaOfflineModelReadiness =
        MangaOfflineModelReadiness(
            paddleReady = paddleModelInstaller.checkInstalled(PaddleModelVersion.V6_SMALL) != null,
            mangaOcrReady = mangaOcrModelInstaller.checkInstalled() != null,
            sakuraReady =
                llmModelInstaller.checkInstalled(LlmModelKind.SAKURA_1_5B_Q4) != null,
            includeSakura = includeSakura,
        )

    suspend fun downloadMissingMangaOfflineModels(
        includeSakura: Boolean,
        onProgress: (String) -> Unit,
    ) {
        val specs = mangaOfflineDownloadSpecs(mangaOfflineModelReadiness(includeSakura))
        if (specs.isNotEmpty()) {
            modelDownloadManager.enqueueIndependentlyAndAwait(specs, onProgress)
        }
    }

}

data class RecommendedModelsReadiness(
    val paddleVersion: PaddleModelVersion?,
    val paddleReady: Boolean,
    val includeHyMt2: Boolean,
    val hyMt2Ready: Boolean,
) {
    val allReady: Boolean
        get() = paddleReady && hyMt2Ready

    /** Preserve the old flow: Hy-MT2 is required, while a standalone OCR download may be skipped. */
    val requiredModelsReady: Boolean
        get() = !includeHyMt2 || hyMt2Ready
}

data class MangaOfflineModelReadiness(
    val paddleReady: Boolean,
    val mangaOcrReady: Boolean,
    val sakuraReady: Boolean,
    val includeSakura: Boolean,
) {
    val ocrReady: Boolean
        get() = paddleReady && mangaOcrReady

    val allReady: Boolean
        get() = ocrReady && (!includeSakura || sakuraReady)
}

internal fun mangaOfflineDownloadSpecs(
    readiness: MangaOfflineModelReadiness,
): List<ModelDownloadSpec> = buildList {
    if (!readiness.paddleReady) {
        add(ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL))
    }
    if (!readiness.mangaOcrReady) {
        add(ModelDownloadSpec.mangaOcr())
    }
    if (readiness.includeSakura && !readiness.sakuraReady) {
        add(ModelDownloadSpec.llm(LlmModelKind.SAKURA_1_5B_Q4))
    }
}

internal fun recommendedModelsDownloadSpecs(
    readiness: RecommendedModelsReadiness,
): List<ModelDownloadSpec> = buildList {
    if (!readiness.paddleReady) {
        readiness.paddleVersion?.let { add(ModelDownloadSpec.paddle(it)) }
    }
    if (readiness.includeHyMt2 && !readiness.hyMt2Ready) {
        add(ModelDownloadSpec.llm(LlmModelKind.HY_MT2_1_8B_Q4_K_M))
    }
}
