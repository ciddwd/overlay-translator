package com.gameocr.app.onboarding

import androidx.lifecycle.ViewModel
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.data.SettingsRepository
import com.gameocr.app.download.ModelDownloadManager
import com.gameocr.app.download.ModelReadiness
import com.gameocr.app.download.ModelReadinessChecker
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.llm.LlmModelKind
import com.gameocr.app.translate.RoutingTranslator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val routingTranslator: RoutingTranslator,
    private val modelReadinessChecker: ModelReadinessChecker,
    private val modelDownloadManager: ModelDownloadManager,
) : ViewModel() {
    suspend fun loadDraft(firstRun: Boolean): OnboardingDraft =
        if (firstRun) OnboardingDraft()
        else OnboardingPolicy.fromSettings(settingsRepository.get())

    suspend fun save(draft: OnboardingDraft) {
        settingsRepository.update { current ->
            OnboardingPolicy.apply(
                settings = current,
                draft = draft,
                localLlmSupported = modelReadinessChecker.isLocalLlmSupported(),
            )
        }
    }

    suspend fun downloadMlKitLanguagePair(sourceLang: String, targetLang: String) {
        routingTranslator.downloadMlKitLanguagePair(sourceLang, targetLang)
    }

    fun recommendedModelsReadiness(draft: OnboardingDraft): RecommendedModelsReadiness {
        val paddleVersion = OnboardingPolicy.recommendedPaddleModelVersion(draft)
            ?.takeIf { OnboardingPolicy.shouldRecommendPaddleOcr(draft) }
        val includeHyMt2 = modelReadinessChecker.isLocalLlmSupported() &&
            OnboardingPolicy.usesHyMt2MangaTranslation(draft)
        return RecommendedModelsReadiness(
            paddleVersion = paddleVersion,
            paddle = paddleVersion?.let(modelReadinessChecker::paddle),
            hyMt2 = if (includeHyMt2) {
                modelReadinessChecker.llm(LlmModelKind.HY_MT2_1_8B_Q4_K_M)
            } else {
                null
            },
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
            paddle = modelReadinessChecker.paddle(PaddleModelVersion.V6_SMALL),
            mangaOcr = modelReadinessChecker.mangaOcr(),
            sakura = if (includeSakura) {
                modelReadinessChecker.llm(LlmModelKind.SAKURA_1_5B_Q4)
            } else {
                null
            },
        )

    fun isLocalLlmSupported(): Boolean = modelReadinessChecker.isLocalLlmSupported()

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
    val paddle: ModelReadiness?,
    val hyMt2: ModelReadiness?,
) {
    val paddleReady: Boolean
        get() = paddle?.ready != false

    val includeHyMt2: Boolean
        get() = hyMt2 != null

    val hyMt2Ready: Boolean
        get() = hyMt2?.ready != false

    val hyMt2Supported: Boolean
        get() = hyMt2?.supported != false

    val allReady: Boolean
        get() = paddleReady && hyMt2Ready

    val hasDownloadableModels: Boolean
        get() = listOfNotNull(paddle, hyMt2).any(ModelReadiness::downloadable)

    /** Preserve the old flow: Hy-MT2 is required, while a standalone OCR download may be skipped. */
    val requiredModelsReady: Boolean
        get() = !includeHyMt2 || hyMt2Ready
}

data class MangaOfflineModelReadiness(
    val paddle: ModelReadiness,
    val mangaOcr: ModelReadiness,
    val sakura: ModelReadiness?,
) {
    val paddleReady: Boolean
        get() = paddle.ready

    val mangaOcrReady: Boolean
        get() = mangaOcr.ready

    val includeSakura: Boolean
        get() = sakura != null

    val sakuraReady: Boolean
        get() = sakura?.ready != false

    val sakuraSupported: Boolean
        get() = sakura?.supported != false

    val ocrReady: Boolean
        get() = paddleReady && mangaOcrReady

    val allReady: Boolean
        get() = ocrReady && (!includeSakura || sakuraReady)

    val hasDownloadableModels: Boolean
        get() = listOfNotNull(paddle, mangaOcr, sakura).any(ModelReadiness::downloadable)
}

internal fun mangaOfflineDownloadSpecs(
    readiness: MangaOfflineModelReadiness,
): List<ModelDownloadSpec> = buildList {
    if (readiness.paddle.downloadable) {
        add(readiness.paddle.spec)
    }
    if (readiness.mangaOcr.downloadable) {
        add(readiness.mangaOcr.spec)
    }
    if (readiness.sakura?.downloadable == true) {
        add(readiness.sakura.spec)
    }
}

internal fun recommendedModelsDownloadSpecs(
    readiness: RecommendedModelsReadiness,
): List<ModelDownloadSpec> = buildList {
    if (readiness.paddle?.downloadable == true) {
        add(readiness.paddle.spec)
    }
    if (readiness.hyMt2?.downloadable == true) {
        add(readiness.hyMt2.spec)
    }
}
