package com.gameocr.app.ui

import com.gameocr.app.data.AutoOcrRoute
import com.gameocr.app.data.AutoOcrRoutingPolicy
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OcrEngineCatalog
import com.gameocr.app.data.PaddleModelVersion
import com.gameocr.app.data.Settings
import com.gameocr.app.download.ModelDownloadDependencies
import com.gameocr.app.download.ModelDownloadSpec
import org.junit.Assert.*
import org.junit.Test

class AutoOcrOptionPolicyTest {
    @Test fun namesMatchOuterChipsAndPaddleVersionPicker_tableDriven() {
        AutoOcrRoutingPolicy.candidates.forEach { route ->
            val expected = if (route.engine == OcrEngineKind.PADDLE_ONNX) route.paddleVersion.displayNameRes
                else OcrEngineCatalog.option(route.engine).labelRes
            assertEquals(route.toString(), expected, autoOcrRouteLabelRes(route))
        }
    }

    @Test fun fullListIsIndependentOfLanguageReadinessAndConfiguration_tableDriven() {
        for (language in listOf("ko", "ja", "en", "zh-CN", "fr", "ar")) {
            for (installed in listOf(emptySet(), AutoOcrRoutingPolicy.candidates.toSet())) {
                val settings = Settings()
                val routes = AutoOcrRoutingPolicy.candidates
                assertEquals(OcrEngineCatalog.automaticRoutes(), routes)
                routes.forEach { route ->
                    val state = autoOcrOptionState(settings, language, route, installed, emptySet())
                    if (!AutoOcrRoutingPolicy.supports(settings, language, route)) {
                        assertEquals("$language $route", AutoOcrOptionState.UNSUPPORTED, state)
                    } else if (autoOcrModelSpec(route) != null) {
                        assertEquals("$language $route", if (route in installed) AutoOcrOptionState.READY
                            else AutoOcrOptionState.DOWNLOAD, state)
                    }
                    assertEquals("$language $route $state", state == AutoOcrOptionState.READY &&
                        AutoOcrRoutingPolicy.supports(settings, language, route),
                        autoOcrCanSelect(settings, language, route, state))
                }
            }
        }
    }

    @Test fun unsupportedOrUnavailableCannotBeSelected_tableDriven() {
        data class Case(val language: String, val route: AutoOcrRoute?, val supported: Boolean)
        val cases = listOf(
            Case("ko", AutoOcrRoute(OcrEngineKind.ML_KIT_KOREAN), true),
            Case("ko", AutoOcrRoute(OcrEngineKind.MANGA_OCR_JA), false),
            Case("ko", AutoOcrRoute(OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_SMALL), false),
            Case("ko", AutoOcrRoute(OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V5_KOREAN), true),
            Case("ja", AutoOcrRoute(OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_SMALL), true),
            Case("ja", AutoOcrRoute(OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_TINY), false),
            Case("ar", null, true),
            Case("ko", AutoOcrRoute(OcrEngineKind.ML_KIT_AUTO), false),
        )
        cases.forEach { case -> AutoOcrOptionState.entries.forEach { state ->
            assertEquals("$case $state", case.supported && state == AutoOcrOptionState.READY,
                autoOcrCanSelect(Settings(), case.language, case.route, state))
        } }
    }

    @Test fun readiness_tableDriven_neverConfusesMissingModelsConfigurationAndChecking() {
        val paddle = AutoOcrRoute(OcrEngineKind.PADDLE_ONNX)
        val manga = AutoOcrRoute(OcrEngineKind.MANGA_OCR_JA)
        val baidu = AutoOcrRoute(OcrEngineKind.BAIDU)
        data class Case(val name: String, val route: AutoOcrRoute?, val settings: Settings = Settings(),
            val available: Set<AutoOcrRoute>? = emptySet(), val active: Set<ModelDownloadSpec> = emptySet(),
            val language: String = "ja", val expected: AutoOcrOptionState)
        val cases = listOf(
            Case("built in ready", AutoOcrRoute(OcrEngineKind.ML_KIT_KOREAN), available = null, language = "ko", expected = AutoOcrOptionState.READY),
            Case("checking not missing", paddle, available = null, expected = AutoOcrOptionState.CHECKING),
            Case("missing model", paddle, expected = AutoOcrOptionState.DOWNLOAD),
            Case("installed", paddle, available = setOf(paddle), expected = AutoOcrOptionState.READY),
            Case("other version installed", paddle, available = setOf(AutoOcrRoute(OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V5_MOBILE)), expected = AutoOcrOptionState.DOWNLOAD),
            Case("downloading", paddle, active = setOf(ModelDownloadSpec.paddle(paddle.paddleVersion)), expected = AutoOcrOptionState.DOWNLOADING),
            Case("Manga dependency downloading", manga, active = setOf(ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL)), expected = AutoOcrOptionState.DOWNLOADING),
            Case("Manga dependency missing", manga, expected = AutoOcrOptionState.DOWNLOAD),
            Case("cloud no credentials", baidu, expected = AutoOcrOptionState.UNCONFIGURED),
            Case("cloud missing one credential", baidu, settings = Settings(baiduOcrApiKey = "test"), expected = AutoOcrOptionState.UNCONFIGURED),
            Case("credentials removed after load", baidu, available = setOf(baidu), expected = AutoOcrOptionState.UNCONFIGURED),
            Case("configured cloud", baidu, settings = Settings(baiduOcrApiKey = "test", baiduOcrSecretKey = "test"), expected = AutoOcrOptionState.READY),
            Case("local missing URL", AutoOcrRoute(OcrEngineKind.UMI_OCR), settings = Settings(umiOcrBaseUrl = ""), expected = AutoOcrOptionState.UNCONFIGURED),
            Case("no override option", null, available = null, expected = AutoOcrOptionState.READY),
            Case("already installed while another task runs", paddle, available = setOf(paddle), active = setOf(ModelDownloadSpec.mangaOcr()), expected = AutoOcrOptionState.READY),
        )
        cases.forEach { assertEquals(it.name, it.expected, autoOcrOptionState(it.settings, it.language, it.route, it.available, it.active)) }
    }

    @Test fun categoriesAndDownloadSpecs_tableDriven_preserveVersionAndDependencies() {
        AutoOcrRoutingPolicy.candidates.forEach { route ->
            val expected = when (route.engine) {
                OcrEngineKind.UMI_OCR, OcrEngineKind.LUNA_OCR -> AutoOcrOptionGroup.LOCAL
                OcrEngineKind.BAIDU, OcrEngineKind.TENCENT, OcrEngineKind.YOUDAO, OcrEngineKind.PADDLE_AI_STUDIO -> AutoOcrOptionGroup.CLOUD
                else -> AutoOcrOptionGroup.ON_DEVICE
            }
            assertEquals(route.toString(), expected, autoOcrOptionGroup(route))
            when (route.engine) {
                OcrEngineKind.PADDLE_ONNX -> assertEquals(ModelDownloadSpec.paddle(route.paddleVersion), autoOcrModelSpec(route))
                OcrEngineKind.MANGA_OCR_JA -> assertEquals(listOf(ModelDownloadSpec.paddle(PaddleModelVersion.V6_SMALL), ModelDownloadSpec.mangaOcr()),
                    ModelDownloadDependencies.expand(listOf(autoOcrModelSpec(route)!!)))
                else -> assertNull(autoOcrModelSpec(route))
            }
        }
        assertEquals(AutoOcrOptionGroup.entries.toList(), AutoOcrRoutingPolicy.candidates.map(::autoOcrOptionGroup).distinct())
    }

    @Test fun completionCancellationFailure_tableDriven_refreshesWithoutSelecting() {
        val route = AutoOcrRoute(OcrEngineKind.PADDLE_ONNX)
        for (success in listOf(true, false)) {
            val settings = Settings()
            assertEquals(AutoOcrOptionState.DOWNLOADING, autoOcrOptionState(settings, "ja", route, emptySet(), setOf(autoOcrModelSpec(route)!!)))
            val expected = if (success) AutoOcrOptionState.READY else AutoOcrOptionState.DOWNLOAD
            assertEquals(expected, autoOcrOptionState(settings, "ja", route, if (success) setOf(route) else emptySet(), emptySet()))
            assertEquals(AutoOcrRoute(OcrEngineKind.ML_KIT_KOREAN), settings.autoOcr.routeFor("ko"))
        }
    }

    @Test fun unsupportedOverridesCheckingMissingInstalledDownloadingAndConfiguration_tableDriven() {
        val all = AutoOcrRoutingPolicy.candidates.toSet()
        val active = all.mapNotNull(::autoOcrModelSpec).toSet()
        val configuredCloud = Settings(paddleAiStudioToken = "test-token", youdaoAppKey = "test-key",
            youdaoAppSecret = "test-secret", umiOcrBaseUrl = "http://localhost:1224")
        val cases = listOf(
            "ko" to AutoOcrRoute(OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_SMALL),
            "ja" to AutoOcrRoute(OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_TINY),
            "ko" to AutoOcrRoute(OcrEngineKind.MANGA_OCR_JA),
            "en" to AutoOcrRoute(OcrEngineKind.ML_KIT_KOREAN),
            "ko" to AutoOcrRoute(OcrEngineKind.PADDLE_AI_STUDIO),
            "ar" to AutoOcrRoute(OcrEngineKind.YOUDAO),
            "ar" to AutoOcrRoute(OcrEngineKind.UMI_OCR),
        )
        for ((language, route) in cases) {
            for (settings in listOf(Settings(), configuredCloud)) {
                for (installed in listOf(null, emptySet(), all)) {
                    for (downloading in listOf(emptySet(), active)) {
                        val state = autoOcrOptionState(settings, language, route, installed, downloading)
                        assertEquals("$language $route installed=$installed downloading=$downloading",
                            AutoOcrOptionState.UNSUPPORTED, state)
                        assertFalse(autoOcrCanSelect(settings, language, route, state))
                    }
                }
            }
        }
    }

    @Test fun switchingLanguageRecomputesUnsupportedWithoutChangingModelReadiness() {
        val settings = Settings()
        val route = AutoOcrRoute(OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_SMALL)
        for (installed in listOf(emptySet(), setOf(route))) {
            val supportedState = if (installed.isEmpty()) AutoOcrOptionState.DOWNLOAD else AutoOcrOptionState.READY
            listOf("ja" to supportedState, "ko" to AutoOcrOptionState.UNSUPPORTED, "zh-CN" to supportedState)
                .forEach { (language, expected) ->
                    assertEquals(language, expected, autoOcrOptionState(settings, language, route, installed, emptySet()))
                }
        }
    }
}
