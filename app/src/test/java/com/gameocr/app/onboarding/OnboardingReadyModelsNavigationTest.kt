package com.gameocr.app.onboarding

import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.download.ModelReadiness
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingReadyModelsNavigationTest {
    private val cloudManga = OnboardingDraft(
        sourceLang = "ja",
        targetLang = "zh-CN",
        usage = OnboardingUsage.MANGA,
        translationMethod = OnboardingTranslationMethod.CLOUD_LLM,
    )

    @Test
    fun steps_tableDriven_onlyReadyCloudJapaneseMangaSkipsPreparation() {
        for (source in listOf("ja", "ko", "en")) {
            for (target in listOf("zh-CN", "en")) {
                for (usage in OnboardingUsage.entries) {
                    for (method in OnboardingTranslationMethod.entries) {
                        for (supported in listOf(false, true)) {
                            for (ready in listOf(false, true)) {
                                val draft = cloudManga.copy(
                                    sourceLang = source, targetLang = target,
                                    usage = usage, translationMethod = method,
                                )
                                val before = OnboardingPolicy.stepsFor(draft, supported)
                                val expected = if (ready && source == "ja" && target == "zh-CN" &&
                                    usage == OnboardingUsage.MANGA && method == OnboardingTranslationMethod.CLOUD_LLM
                                ) before - OnboardingStep.MANGA_OFFLINE_DOWNLOAD else before
                                assertEquals("$source/$target/$usage/$method/$supported/$ready",
                                    expected, OnboardingPolicy.stepsFor(draft, supported, ready))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun readiness_tableDriven_requiresBothRecognitionAndDetectionComponents() {
        for (paddleInstalled in listOf(false, true)) {
            for (mangaInstalled in listOf(false, true)) {
                for (supported in listOf(false, true)) {
                    val readiness = MangaOfflineModelReadiness(
                        paddle = ModelReadiness(ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL), paddleInstalled, supported),
                        mangaOcr = ModelReadiness(ModelDownloadSpec.mangaOcr(), mangaInstalled, supported),
                        sakura = null,
                    )
                    val expectedReady = paddleInstalled && mangaInstalled && supported
                    assertEquals(expectedReady, readiness.ocrReady)
                    val steps = OnboardingPolicy.stepsFor(cloudManga, true, readiness.ocrReady)
                    assertEquals(!expectedReady, OnboardingStep.MANGA_OFFLINE_DOWNLOAD in steps)
                }
            }
        }
    }

    @Test
    fun navigation_tableDriven_handlesForwardBackAndChangingReadiness() {
        data class Case(val step: OnboardingStep, val ready: Boolean, val forward: Boolean, val target: OnboardingStep)
        listOf(
            Case(OnboardingStep.TRANSLATION_METHOD, true, true, OnboardingStep.CLOUD_CONFIG),
            Case(OnboardingStep.TRANSLATION_METHOD, false, true, OnboardingStep.MANGA_OFFLINE_DOWNLOAD),
            Case(OnboardingStep.CLOUD_CONFIG, true, false, OnboardingStep.TRANSLATION_METHOD),
            Case(OnboardingStep.CLOUD_CONFIG, false, false, OnboardingStep.MANGA_OFFLINE_DOWNLOAD),
            Case(OnboardingStep.CLOUD_CONFIG, true, true, OnboardingStep.TTS),
            // The current page can disappear after a successful download or an external install.
            Case(OnboardingStep.MANGA_OFFLINE_DOWNLOAD, true, true, OnboardingStep.CLOUD_CONFIG),
            Case(OnboardingStep.MANGA_OFFLINE_DOWNLOAD, true, false, OnboardingStep.TRANSLATION_METHOD),
            Case(OnboardingStep.MANGA_OFFLINE_DOWNLOAD, false, true, OnboardingStep.CLOUD_CONFIG),
            Case(OnboardingStep.TTS, true, false, OnboardingStep.CLOUD_CONFIG),
            Case(OnboardingStep.WELCOME, true, false, OnboardingStep.WELCOME),
            Case(OnboardingStep.SUMMARY, true, true, OnboardingStep.SUMMARY),
        ).forEach { case ->
            val steps = OnboardingPolicy.stepsFor(cloudManga, true, case.ready)
            val index = OnboardingPolicy.adjacentStepIndex(cloudManga, true, case.ready, case.step, case.forward)
            assertTrue(case.toString(), index in steps.indices)
            assertEquals(case.toString(), case.target, steps[index])
        }
    }

    @Test
    fun navigation_smoke_downloadThenBackThenSwitchOfflineKeepsRequiredModels() {
        fun move(draft: OnboardingDraft, ready: Boolean, step: OnboardingStep, forward: Boolean): OnboardingStep {
            val steps = OnboardingPolicy.stepsFor(draft, true, ready)
            return steps[OnboardingPolicy.adjacentStepIndex(draft, true, ready, step, forward)]
        }
        var step = move(cloudManga, false, OnboardingStep.TRANSLATION_METHOD, true)
        assertEquals(OnboardingStep.MANGA_OFFLINE_DOWNLOAD, step)
        step = move(cloudManga, true, step, true)
        assertEquals(OnboardingStep.CLOUD_CONFIG, step)
        assertEquals(9, OnboardingPolicy.stepsFor(cloudManga, true, true).size)
        step = move(cloudManga, true, step, false)
        assertEquals(OnboardingStep.TRANSLATION_METHOD, step)
        val offline = cloudManga.copy(translationMethod = OnboardingTranslationMethod.OFFLINE)
        assertEquals(OnboardingStep.MANGA_OFFLINE_DOWNLOAD, move(offline, true, step, true))
        val korean = cloudManga.copy(sourceLang = "ko")
        assertEquals(OnboardingStep.RECOMMENDED_MODELS_DOWNLOAD, move(korean, true, step, true))
    }

    @Test
    fun screenWiring_checksBeforeNavigationAndSharesPortraitLandscapeAndBackPath() {
        val path = "src/main/java/com/gameocr/app/onboarding/OnboardingScreen.kt"
        val source = listOf(File(path), File("app/$path")).first(File::isFile).readText()
        assertEquals(2, Regex("onNext = ::continueSetup").findAll(source).count())
        assertTrue(source.contains("if (stepIndex > 0) moveStep(forward = false)"))
        assertTrue(source.contains("if (saving || navigating) return"))
        assertTrue(source.contains("if (draft != currentDraft) return@launch"))
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("viewModel.mangaOfflineModelReadiness(includeSakura = false).ocrReady"))
        assertTrue(source.contains("var cloudMangaOcrReady by rememberSaveable"))
        val navigation = source.substringAfter("fun moveStep(forward: Boolean)").substringBefore("fun goBack()")
        assertTrue(navigation.indexOf("mangaOfflineModelReadiness") < navigation.indexOf("stepIndex = nextIndex"))
        assertTrue(navigation.contains("catch (cancelled: CancellationException)"))
        assertFalse(navigation.contains("downloadMissing"))
        assertFalse(source.contains("stepIndex++"))
    }
}
