package com.gameocr.app.ui

import com.gameocr.app.data.*
import com.gameocr.app.llm.LlmModelKind
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SettingsModelReadinessPolicyTest {
    private fun preset(engine: TranslatorEngine, ocr: OcrEngineKind, paddle: PaddleModelVersion) =
        TranslationPresetCatalog.fromSettings("test", "test", "test", Settings(
            translatorEngine = engine, ocrEngine = ocr, paddleModelVersion = paddle,
        ))

    @Test fun selectedAndPresetModelsAreDeduplicated_tableDriven() {
        for (engine in listOf(TranslatorEngine.OPENAI, TranslatorEngine.GOOGLE_ML_KIT, TranslatorEngine.LOCAL_SAKURA, TranslatorEngine.LOCAL_HY_MT2)) {
            for (version in PaddleModelVersion.entries) {
                val presets = listOf(
                    preset(TranslatorEngine.LOCAL_SAKURA, OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V6_SMALL),
                    preset(TranslatorEngine.LOCAL_HY_MT2, OcrEngineKind.PADDLE_ONNX, version),
                ).let { it + it }
                val request = settingsModelReadinessRequest(engine, version, presets)
                assertEquals(setOf(LlmModelKind.SAKURA_1_5B_Q4, LlmModelKind.HY_MT2_1_8B_Q4_K_M), request.llmKinds)
                assertEquals(setOf(version, PaddleModelVersion.V6_SMALL), request.paddleVersions)
                val counts = mutableMapOf<String, Int>()
                fun check(key: String): String { counts[key] = counts.getOrDefault(key, 0) + 1; return key }
                val result = checkSettingsModels(request,
                    { check("llm:$it") }, { check("paddle:$it") },
                    { check("manga") }, { check("orientation") },
                )
                assertEquals(request.llmKinds.size + request.paddleVersions.size + 2, counts.size)
                assertTrue(counts.values.all { it == 1 })
                assertEquals(request.llmKinds, result.llm.keys)
                assertEquals(request.paddleVersions, result.paddle.keys)
            }
        }
    }

    @Test fun cloudWithoutPresetsDoesNotCheckLlmAndLaterRefreshIsFresh() {
        val request = settingsModelReadinessRequest(TranslatorEngine.OPENAI, PaddleModelVersion.V6_SMALL, emptyList())
        assertTrue(request.llmKinds.isEmpty())
        for (installed in listOf(false, true, false)) {
            val result = checkSettingsModels(request, { installed }, { installed }, { installed }, { installed })
            assertEquals(installed, result.paddle.getValue(PaddleModelVersion.V6_SMALL))
            assertEquals(installed, result.manga)
        }
    }

    @Test fun cloudPresetWithoutPaddleDoesNotAddUnusedPaddleVersion() {
        val request = settingsModelReadinessRequest(TranslatorEngine.OPENAI, PaddleModelVersion.V6_SMALL,
            listOf(preset(TranslatorEngine.OPENAI, OcrEngineKind.ML_KIT_JAPANESE, PaddleModelVersion.V5_MOBILE)))
        assertEquals(setOf(PaddleModelVersion.V6_SMALL), request.paddleVersions)
        assertTrue(request.llmKinds.isEmpty())
    }

    @Test fun oneScreenEffectOwnsInitialAndSelectionChecks() {
        val screen = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        assertEquals(1, Regex("viewModel.loadModelStates\\(modelReadinessRequest\\)").findAll(screen).count())
        assertFalse(screen.contains("LaunchedEffect(settingsLoaded, paddleModelVersion)"))
        assertFalse(screen.contains("LaunchedEffect(settingsLoaded, translationPresets)"))
        val engineEffect = screen.substringAfter("LaunchedEffect(translatorEngine) {").substringBefore("// 端侧 LLM")
        assertFalse(engineEffect.contains("refreshLlmModelState"))
        val effect = screen.substringBefore("val states = viewModel.loadModelStates").substringAfterLast("LaunchedEffect(")
        assertTrue(effect.contains("modelDownloadStateKey, modelDownloadStageKey"))
        assertTrue(effect.contains("translatorEngine, paddleModelVersion, modelReadinessRequest"))
        assertTrue(screen.contains("translatorEngine != checkedEngine || paddleModelVersion != checkedPaddleVersion"))
        assertTrue(screen.contains("translationPresets != checkedPresets) return@LaunchedEffect"))
        assertTrue(screen.contains("states.paddle.getValue(checkedPaddleVersion)"))
    }
}
