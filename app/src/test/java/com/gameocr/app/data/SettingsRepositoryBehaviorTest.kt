package com.gameocr.app.data

import kotlinx.coroutines.async

import android.content.Context
import android.content.ContextWrapper
import com.gameocr.app.capture.CaptureRegion
import com.gameocr.app.ocr.TextOrientation
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryBehaviorTest {

    @Test fun autoOcrAutoSavePersistsOnlyMappingAndReopens_tableDriven() = runBlocking {
        val root = Files.createTempDirectory("auto-ocr-auto-save-test").toFile()
        val repository = fileBackedRepository(root)
        repository.update { it.copy(ocrEngine = OcrEngineKind.ML_KIT_KOREAN,
            promptTemplate = "keep prompt", apiKey = "local-test-only", apiTimeoutSeconds = 43) }
        val baseline = repository.get()
        val saver = com.gameocr.app.ui.AutoOcrSettingsSaver(this) { value ->
            repository.update { it.copy(autoOcr = value) }
        }
        val mapped = AutoOcrSettings(routes = mapOf("ja" to AutoOcrRoute(OcrEngineKind.MANGA_OCR_JA)))
        val added = AutoOcrLanguageListPolicy.add(mapped, "fr")
        for (value in listOf(mapped, added, AutoOcrLanguageListPolicy.remove(added, "fr"))) {
            saver.save(value).await()
            assertEquals(baseline.copy(autoOcr = value), repository.get())
            assertEquals(value, fileBackedRepository(root).get().autoOcr)
        }
    }

    @Test fun autoOcrMappings_roundTripAndDoNotChangeManualSelection_tableDriven() = runBlocking {
        for (manual in listOf(OcrEngineKind.ML_KIT_AUTO, OcrEngineKind.ML_KIT_CHINESE, OcrEngineKind.PADDLE_ONNX)) {
            val root = Files.createTempDirectory("auto-ocr-settings-test").toFile()
            val repository = fileBackedRepository(root)
            repository.update { it.copy(ocrEngine = manual, apiKey = "local-test-only", apiTimeoutSeconds = 41) }
            val before = repository.get()
            val config = AutoOcrSettings(
                routes = mapOf("ko" to AutoOcrRoute(OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V5_KOREAN)),
                additionalLanguages = listOf("ar", "fr"),
            )
            repository.update { it.copy(autoOcr = config) }
            assertEquals(before.copy(autoOcr = config), repository.get())
            assertEquals(config, fileBackedRepository(root).get().autoOcr)
            repository.update { it.copy(apiTimeoutSeconds = 42) }
            assertEquals(config, repository.get().autoOcr)
        }
    }

    @Test
    fun captureRegionHideOnCapture_defaultsOnAndPersistsBothChoices_tableDriven() = runBlocking {
        for (saved in listOf(null, false, true)) {
            val root = Files.createTempDirectory("region-autohide-test").toFile()
            val repository = fileBackedRepository(root)
            saved?.let { value -> repository.update { it.copy(captureRegionHideOnCapture = value) } }
            val expected = saved ?: true
            assertEquals("saved=$saved", expected, repository.get().captureRegionHideOnCapture)
            assertEquals("reopen=$saved", expected, fileBackedRepository(root).get().captureRegionHideOnCapture)
            repository.update { it.copy(apiTimeoutSeconds = 43) }
            assertEquals("unrelated save=$saved", expected, repository.get().captureRegionHideOnCapture)
        }
    }

    @Test
    fun dictionaryLookupMode_defaultsOnlineAndPreservesSavedChoice_tableDriven() = runBlocking {
        val cases = listOf(
            null to DictionaryLookupMode.ONLINE,
            DictionaryLookupMode.OFFLINE to DictionaryLookupMode.OFFLINE,
            DictionaryLookupMode.ONLINE to DictionaryLookupMode.ONLINE,
        )
        for ((saved, expected) in cases) {
            val root = Files.createTempDirectory("dictionary-mode-default-test").toFile()
            val repository = fileBackedRepository(root)
            saved?.let { mode -> repository.update { it.copy(dictionaryLookupMode = mode) } }
            assertEquals("saved=$saved", expected, repository.get().dictionaryLookupMode)
            assertEquals("reopen=$saved", expected, fileBackedRepository(root).get().dictionaryLookupMode)
        }
    }

    @Test
    fun floatingButtonAutoSavePersistsOnlyItsFiveFields_tableDriven() = runBlocking {
        val repository = fileBackedRepository(Files.createTempDirectory("floating-button-save-test").toFile())
        repository.update { it.copy(promptTemplate = "keep prompt", apiTimeoutSeconds = 43, floatingButtonX = 101) }
        val values = listOf(
            com.gameocr.app.ui.FloatingButtonSettings(32, 0.1f, false, true, 0),
            com.gameocr.app.ui.FloatingButtonSettings(64, 0.55f, true, false, 22),
            com.gameocr.app.ui.FloatingButtonSettings(96, 1f, true, true, 40),
        )
        val saver = com.gameocr.app.ui.FloatingButtonSettingsSaver(this) { value -> repository.update(value::applyTo) }
        for (value in values) {
            val previous = repository.get()
            saver.save(value).await()
            assertEquals(value.applyTo(previous), repository.get())
            assertEquals("keep prompt", repository.get().promptTemplate)
            assertEquals(43, repository.get().apiTimeoutSeconds)
            assertEquals(101, repository.get().floatingButtonX)
        }
    }

    @Test
    fun settingsDecodeDoesNotRunOnTheCollectorThread() = runBlocking {
        val decryptThreads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val cipher = object : SettingsSecretCipher {
            override fun encrypt(plainText: String) = "test:$plainText"
            override fun decrypt(cipherText: String): String {
                decryptThreads += Thread.currentThread().name
                return cipherText.removePrefix("test:")
            }
        }
        val repository = fileBackedRepository(Files.createTempDirectory("settings-decode-thread-test").toFile(), cipher)
        repository.update { it.copy(apiKey = "test-only-value") }
        decryptThreads.clear()
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "settings-ui-collector")
        }.asCoroutineDispatcher().use { dispatcher ->
            kotlinx.coroutines.withContext(dispatcher) {
                assertEquals("test-only-value", repository.get().apiKey)
            }
        }
        org.junit.Assert.assertTrue(decryptThreads.isNotEmpty())
        org.junit.Assert.assertTrue(decryptThreads.none { it == "settings-ui-collector" })
    }

    @Test
    fun floatingButtonAlpha_defaultAndPersistedValues_tableDriven() = runBlocking {
        val cases = listOf(
            null to 1f, 0.1f to 0.1f, 0.55f to 0.55f, 1f to 1f,
            0f to 0.1f, -1f to 0.1f, 2f to 1f,
            Float.NaN to 1f, Float.POSITIVE_INFINITY to 1f, Float.NEGATIVE_INFINITY to 1f,
        )
        for ((requested, expected) in cases) {
            val root = Files.createTempDirectory("settings-floating-alpha-test").toFile()
            val repository = fileBackedRepository(root)
            requested?.let { value ->
                repository.update { it.copy(floatingButtonAlpha = value) }
            }
            assertEquals("requested=$requested", expected, repository.get().floatingButtonAlpha, 0f)
            assertEquals("reopen=$requested", expected, fileBackedRepository(root).get().floatingButtonAlpha, 0f)
            assertEquals(Settings().overlayAlpha, repository.get().overlayAlpha, 0f)
        }
    }

    @Test
    fun loopTriggerMode_defaultsToSettledAndSavedChoiceIsPreserved_tableDriven() =
        runBlocking {
            data class Case(
                val name: String,
                val savedMode: LoopTriggerMode?,
                val expected: LoopTriggerMode,
            )

            listOf(
                Case("fresh install or missing setting", null, LoopTriggerMode.SETTLED_PAGE),
                Case("saved settled page", LoopTriggerMode.SETTLED_PAGE, LoopTriggerMode.SETTLED_PAGE),
                Case(
                    "saved smart trigger",
                    LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE,
                    LoopTriggerMode.WAIT_FOR_TEXT_COMPLETE,
                ),
                Case(
                    "saved fixed trigger",
                    LoopTriggerMode.FIXED_INTERVAL,
                    LoopTriggerMode.FIXED_INTERVAL,
                ),
            ).forEach { case ->
                val repository = fileBackedRepository(
                    Files.createTempDirectory("settings-loop-trigger-default-test").toFile()
                )
                case.savedMode?.let { mode ->
                    repository.update { settings -> settings.copy(loopTriggerMode = mode) }
                }

                assertEquals(case.name, case.expected, repository.get().loopTriggerMode)
            }
        }

    @Test
    fun llmOutboundEncoding_tableDriven_roundTripsNormalizedState() = runBlocking {
        data class Case(
            val name: String,
            val requested: OpenAiRequestOptions,
            val expected: OpenAiRequestOptions,
        )

        listOf(
            Case("disabled", OpenAiRequestOptions(), OpenAiRequestOptions()),
            Case(
                "Base64",
                OpenAiRequestOptions(encodeUserTextBase64 = true),
                OpenAiRequestOptions(encodeUserTextBase64 = true),
            ),
            Case(
                "Unicode",
                OpenAiRequestOptions(encodeUserTextUnicode = true),
                OpenAiRequestOptions(encodeUserTextUnicode = true),
            ),
            Case(
                "visual context custom detail is trimmed",
                OpenAiRequestOptions(
                    sendScreenImage = true,
                    imageDetail = RemoteImageDetail.CUSTOM,
                    customImageDetail = "  original  ",
                ),
                OpenAiRequestOptions(
                    sendScreenImage = true,
                    imageDetail = RemoteImageDetail.CUSTOM,
                    customImageDetail = "original",
                ),
            ),
            Case(
                "conflict prefers Base64",
                OpenAiRequestOptions(encodeUserTextBase64 = true, encodeUserTextUnicode = true),
                OpenAiRequestOptions(encodeUserTextBase64 = true),
            ),
        ).forEach { case ->
            val repository = fileBackedRepository(
                Files.createTempDirectory("settings-llm-encoding-test").toFile()
            )
            repository.update { it.copy(openAiRequestOptions = case.requested) }

            assertEquals(case.name, case.expected, repository.get().openAiRequestOptions)
        }
    }

    @Test
    fun mainStatusPresetSeen_tableDriven_persistsDiscoveryState() = runBlocking {
        data class Case(
            val name: String,
            val markSeen: Boolean,
            val expectedSeen: Boolean,
        )

        listOf(
            Case("fresh install has not discovered presets", markSeen = false, expectedSeen = false),
            Case("visiting presets is persisted", markSeen = true, expectedSeen = true),
        ).forEach { case ->
            val repository = fileBackedRepository(
                Files.createTempDirectory("settings-main-preset-seen-test").toFile()
            )

            if (case.markSeen) repository.markMainStatusPresetSeen()

            assertEquals(case.name, case.expectedSeen, repository.hasSeenMainStatusPreset())
        }
    }

    @Test
    fun translationLanguagePair_tableDriven_rejectsConflictingUpdates() = runBlocking {
        data class Case(
            val name: String,
            val requestedSource: String,
            val requestedTarget: String,
            val expectedSource: String,
            val expectedTarget: String,
        )

        listOf(
            Case("valid pair is stored", "ja", "zh-CN", "ja", "zh-CN"),
            Case("same source and target are rejected", "en", "en", "auto", "zh-CN"),
            Case("case-only conflict is rejected", " JA ", "ja", "auto", "zh-CN"),
            Case("regional variants remain valid", "zh-CN", "zh-TW", "zh-CN", "zh-TW"),
        ).forEach { case ->
            val repository = fileBackedRepository(
                Files.createTempDirectory("settings-language-pair-test").toFile()
            )

            repository.update {
                it.copy(sourceLang = Languages.AUTO.code, targetLang = Languages.ZH_CN.code)
            }
            repository.update {
                it.copy(
                    sourceLang = case.requestedSource,
                    targetLang = case.requestedTarget,
                )
            }

            val actual = repository.get()
            assertEquals("${case.name} source", case.expectedSource, actual.sourceLang)
            assertEquals("${case.name} target", case.expectedTarget, actual.targetLang)
        }
    }

    @Test
    fun ttsPlaybackGain_tableDriven_isClampedWhenPersisted() = runBlocking {
        data class Case(val name: String, val requestedDb: Int, val expectedDb: Int)

        val repository = fileBackedRepository(
            Files.createTempDirectory("settings-tts-gain-test").toFile()
        )
        listOf(
            Case("below minimum", -1, 0),
            Case("disabled", 0, 0),
            Case("middle", 12, 12),
            Case("maximum", 24, 24),
            Case("above maximum", 30, 24),
        ).forEach { case ->
            repository.update { Settings(ttsGainDb = case.requestedDb) }
            assertEquals(case.name, case.expectedDb, repository.get().ttsGainDb)
        }
    }

    @Test
    fun mangaDetectorVersion_tableDriven_isForcedOnlyForMangaOcr() = runBlocking {
        data class Case(
            val name: String,
            val engine: OcrEngineKind,
            val requestedVersion: PaddleModelVersion,
            val expectedVersion: PaddleModelVersion,
        )
        val cases = listOf(
            Case("legacy Manga V5 migrates", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V5_MOBILE, PaddleModelVersion.V6_SMALL),
            Case("Manga Korean V5 migrates", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V5_KOREAN, PaddleModelVersion.V6_SMALL),
            Case("Manga tiny migrates", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V6_TINY, PaddleModelVersion.V6_SMALL),
            Case("Manga medium migrates", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V6_MEDIUM, PaddleModelVersion.V6_SMALL),
            Case("Manga small remains", OcrEngineKind.MANGA_OCR_JA, PaddleModelVersion.V6_SMALL, PaddleModelVersion.V6_SMALL),
            Case("general Paddle V5 remains", OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V5_MOBILE, PaddleModelVersion.V5_MOBILE),
            Case("general Paddle medium remains", OcrEngineKind.PADDLE_ONNX, PaddleModelVersion.V6_MEDIUM, PaddleModelVersion.V6_MEDIUM),
            Case("ML Kit keeps stored choice", OcrEngineKind.ML_KIT_JAPANESE, PaddleModelVersion.V6_TINY, PaddleModelVersion.V6_TINY),
        )

        cases.forEach { case ->
            val repository = fileBackedRepository(
                Files.createTempDirectory("settings-manga-detector-test").toFile()
            )
            repository.update {
                it.copy(
                    ocrEngine = case.engine,
                    paddleModelVersion = case.requestedVersion,
                )
            }
            assertEquals(case.name, case.expectedVersion, repository.get().paddleModelVersion)
        }
    }

    @Test
    fun rescaleCaptureRegion_tableDriven_migratesWorkspaceAndOrientationCoordinates() = runBlocking {
        data class Case(
            val name: String,
            val region: CaptureRegion,
            val savedWidth: Int,
            val savedHeight: Int,
            val currentWidth: Int,
            val currentHeight: Int,
            val expectedRegion: CaptureRegion,
        )

        val cases = listOf(
            Case(
                name = "old HyperOS workspace width migrates to physical width",
                region = CaptureRegion(0, 0, 3053, 1440),
                savedWidth = 3053,
                savedHeight = 1440,
                currentWidth = 3200,
                currentHeight = 1440,
                expectedRegion = CaptureRegion(0, 0, 3200, 1440),
            ),
            Case(
                name = "same screen keeps coordinates",
                region = CaptureRegion(100, 200, 1000, 900),
                savedWidth = 3200,
                savedHeight = 1440,
                currentWidth = 3200,
                currentHeight = 1440,
                expectedRegion = CaptureRegion(100, 200, 1000, 900),
            ),
            Case(
                name = "orientation change scales both axes",
                region = CaptureRegion(144, 320, 720, 1600),
                savedWidth = 1440,
                savedHeight = 3200,
                currentWidth = 3200,
                currentHeight = 1440,
                expectedRegion = CaptureRegion(320, 144, 1600, 720),
            ),
            Case(
                name = "scaled out of range coordinates clamp to physical screen",
                region = CaptureRegion(-100, -100, 4000, 2000),
                savedWidth = 1600,
                savedHeight = 720,
                currentWidth = 3200,
                currentHeight = 1440,
                expectedRegion = CaptureRegion(0, 0, 3200, 1440),
            ),
            Case(
                name = "missing saved metadata preserves region",
                region = CaptureRegion(120, 240, 960, 1200),
                savedWidth = 0,
                savedHeight = 0,
                currentWidth = 1440,
                currentHeight = 3200,
                expectedRegion = CaptureRegion(120, 240, 960, 1200),
            ),
        )

        val root = Files.createTempDirectory("settings-region-rescale-test").toFile()
        val repository = fileBackedRepository(root)
        cases.forEach { case ->
            repository.update {
                Settings(
                    captureRegion = case.region,
                    captureRegionSavedScreenW = case.savedWidth,
                    captureRegionSavedScreenH = case.savedHeight,
                )
            }
            repository.rescaleCaptureRegionIfNeeded(case.currentWidth, case.currentHeight)
            val actual = repository.get()
            assertEquals("${case.name} region", case.expectedRegion, actual.captureRegion)
            assertEquals("${case.name} saved width", case.currentWidth, actual.captureRegionSavedScreenW)
            assertEquals("${case.name} saved height", case.currentHeight, actual.captureRegionSavedScreenH)
        }
    }

    @Test
    fun repository_roundTripsACompleteNonDefaultSettingsObject() = runBlocking {
        val root = Files.createTempDirectory("settings-repository-test").toFile()
        val repository = fileBackedRepository(root)
        val fontName = "${"b".repeat(64)}.ttf"
        val preset = TranslationPreset(id = "custom_roundtrip", name = "Round trip")
        val requested = Settings(
            baseUrl = "https://roundtrip.example/v1/",
            apiKey = "api-key",
            model = "roundtrip-model",
            anthropicBaseUrl = "https://anthropic.example/v1/",
            anthropicApiKey = "roundtrip-anthropic-key",
            anthropicModel = "claude-roundtrip",
            sourceLang = "ja",
            targetLang = "zh-TW",
            promptTemplate = "roundtrip prompt",
            openAiRequestOptions = OpenAiRequestOptions(
                thinkingModeEnabled = true,
                reasoningEffort = RemoteReasoningEffort.CUSTOM,
                thinkingParameterFormat = RemoteThinkingParameterFormat.CUSTOM_JSON,
                customReasoningEffort = "fast_plus",
                customThinkingEnabledJson = """{"thinking_level":"{effort}"}""",
                customThinkingDisabledJson = """{"thinking_level":"off"}""",
            ),
            ocrEngine = OcrEngineKind.PADDLE_ONNX,
            captureLoopIntervalMs = 4321L,
            loopTriggerMode = LoopTriggerMode.FIXED_INTERVAL,
            loopTextStableDurationMs = 1300L,
            loopSkipSimilarFrames = false,
            loopFrameSimilarityThreshold = 0.83f,
            loopTextRegionMode = LoopTextRegionMode.ANYWHERE,
            loopTranslateRegionOnly = false,
            developerOptionsEnabled = true,
            ocrRedBoxModeEnabled = true,
            ocrRedBoxShowSourceText = false,
            ocrRedBoxShowTranslation = true,
            captureRegion = CaptureRegion(11, 22, 333, 444),
            captureRegionSavedScreenW = 1920,
            captureRegionSavedScreenH = 1080,
            overlayTextSizeSp = 22,
            overlayTextStyle = OverlayTextStyle(
                bold = true,
                italic = true,
                underline = true,
                letterSpacingEm = 0.12f,
                lineSpacingMultiplier = 1.6f,
                alignment = OverlayTextAlignment.END,
                strokeEnabled = true,
                shadowEnabled = true,
            ),
            overlayAlpha = 0.62f,
            overlayFontFileName = fontName,
            overlayFontDisplayName = "Roundtrip.ttf",
            overlayFonts = listOf(OverlayFontEntry(fontName, "Roundtrip.ttf")),
            streamingTranslate = false,
                retryFailedTranslation = true,
            ttsGainDb = 7,
            renderMode = RenderMode.FLOATING_WINDOW,
            translationBlockInteractionMode = TranslationBlockInteractionMode.OPEN_COPY_PANEL,
            overlayPlacement = OverlayPlacement.ABOVE,
            overlayTheme = OverlayTheme.CUSTOM,
            customBgColor = 0xAA102030.toInt(),
            customFgColor = 0xFF405060.toInt(),
            customBorderColor = 0xCC708090.toInt(),
            customBorderWidth = 4,
            overlayOffsetX = 31,
            overlayOffsetY = -17,
            preprocess = PreprocessOptions(upscale2x = true, invert = true, binarize = true),
            textOrientationAutoDetect = false,
            captureContentOrientation = CaptureContentOrientation.LANDSCAPE,
            manualTextOrientation = TextOrientation.VERTICAL_RTL,
            translationOutputFollowRecognition = false,
            translationOutputLayout = TranslationOutputLayout.VERTICAL,
            translationOutputDirection = TranslationOutputDirection.RIGHT_TO_LEFT,
            baiduOcrApiKey = "baidu-key",
            baiduOcrSecretKey = "baidu-secret",
            baiduOcrEndpoint = BaiduOcrEndpoint.ACCURATE_BASIC,
            baiduOcrLanguage = BaiduOcrLanguage.JAP,
            umiOcrBaseUrl = "http://127.0.0.1:1224/api/ocr",
            lunaOcrBaseUrl = "http://127.0.0.1:2333/api/ocr",
            paddleAiStudioToken = "paddle-token",
            tencentSecretId = "tencent-id",
            tencentSecretKey = "tencent-secret",
            tencentRegion = "ap-singapore",
            tencentOcrEndpoint = TencentOcrEndpoint.GENERAL_ACCURATE,
            tencentOcrLanguage = TencentOcrLanguage.ZH_RARE,
            paddleModelVersion = PaddleModelVersion.V5_MOBILE,
            paddleModelMirrorUrl = "https://mirror.example/paddle/",
            mangaOcrModelMirrorUrl = "https://mirror.example/manga/",
            orientationModelMirrorUrl = "https://mirror.example/orientation/",
            a11yVolumeTrigger = true,
            translatorEngine = TranslatorEngine.DEEPL,
            translationGlossaryEnabled = false,
            foregroundAppDetectionMode = ForegroundAppDetectionMode.USAGE_ACCESS,
            sendAppNameToTranslator = true,
            deeplApiKey = "deepl-key",
            deeplPro = true,
            deeplProtocol = DeeplProtocol.DEEPLX,
            deeplBaseUrl = "https://deeplx.example/",
            deeplBearerAuth = true,
            deeplCustomToken = "deepl-token",
            youdaoAppKey = "youdao-key",
            youdaoAppSecret = "youdao-secret",
            volcAccessKeyId = "volc-id",
            volcSecretAccessKey = "volc-secret",
            volcRegion = "cn-south-1",
            baiduFanyiAppId = "baidu-app-id",
            baiduFanyiSecretKey = "baidu-fanyi-secret",
            floatingButtonSizeDp = 53,
            floatingButtonAlpha = 0.63f,
            floatingButtonX = 101,
            floatingButtonY = 202,
            floatingButtonSnapToEdge = false,
            floatingButtonAutoDock = true,
            floatingButtonDockInsetDp = 17,
            floatingWindowX = 303,
            floatingWindowY = 404,
            floatingWindowWidthDp = 455,
            floatingWindowHeightDp = 233,
            floatingWindowContentMode = FloatingWindowContentMode.DST_ONLY,
            floatingWindowLocked = true,
            floatingWindowAutoHideWhenObstructing = true,
            customBorderStyle = BorderStyle.DOTTED,
            overlayAllowWrap = false,
            overlayAvoidCollision = false,
            apiTimeoutSeconds = 47,
            mergeAdjacentBlocks = true,
            mergeStrength = MergeStrength.ALL,
            pinnedLanguages = listOf("ja", "zh-TW", "en"),
            mlKitRecentSourceLanguages = listOf("ru", "en", "ja", "ko"),
            cleartextAllowedHosts = listOf("192.168.0.2", "localhost"),
            floatingMenuItemOrder = FloatingMenu.DEFAULT_ORDER.reversed(),
            arcMenuPageSize = 5,
            floatingButtonSkill = FloatingSkill.LOOP,
            dictionaryLookupMode = DictionaryLookupMode.ONLINE,
            dictionaryTapLookupEnabled = false,
            dictionaryPrompt = "roundtrip dictionary",
            localLlmContextSize = 3072,
            localLlmMaxNewTokens = 333,
            dbnetProbThresh = 0.19f,
            dbnetBoxScoreThresh = 0.44f,
            dbnetUnclipRatio = 1.37f,
            mangaOcrDbnetUnclipRatio = 1.83f,
            bubbleClusterGap = 47,
            mangaOcrCropPaddingPx = 29,
            localLlmMirror = LlmMirrorChoice.CUSTOM,
            localLlmMirrorUrl = "https://mirror.example/llm/",
            translationPresets = listOf(preset),
            activeTranslationPresetId = preset.id,
        )

        repository.update { requested }

        assertEquals(MangaOcrSettingsPolicy.normalize(requested), repository.get())
    }

    @Test fun captureRegion_partial_writes_do_not_process_secrets_and_preserve_other_settings() = runBlocking {
        var cipherCalls = 0
        val cipher = object : SettingsSecretCipher {
            override fun encrypt(plainText: String): String { cipherCalls++; return "test:$plainText" }
            override fun decrypt(cipherText: String): String { cipherCalls++; return cipherText.removePrefix("test:") }
        }
        val repository = fileBackedRepository(Files.createTempDirectory("region-partial-write").toFile(), cipher)
        repository.update { it.copy(apiKey = "local-test-key", overlayAlpha = .62f, mergeStrength = MergeStrength.AGGRESSIVE) }
        val before = repository.get()
        for (region in listOf(CaptureRegion(10, 20, 100, 200), null, CaptureRegion(20, 40, 200, 400))) {
            cipherCalls = 0
            repository.setCaptureRegion(region, 400, 800)
            assertEquals("partial write must skip cipher", 0, cipherCalls)
            assertEquals(region, repository.rescaleCaptureRegionIfNeeded(400, 800))
            assertEquals("region read/rescale must skip cipher", 0, cipherCalls)
            assertEquals(before.copy(captureRegion = region, captureRegionSavedScreenW = 400, captureRegionSavedScreenH = 800), repository.get())
        }
        cipherCalls = 0
        assertEquals(CaptureRegion(40, 80, 400, 800), repository.rescaleCaptureRegionIfNeeded(800, 1600))
        assertEquals(0, cipherCalls)
        assertEquals(null, repository.rescaleCaptureRegionIfNeeded(0, 0))
        assertEquals(CaptureRegion(40, 80, 400, 800), repository.get().captureRegion)
    }

    @Test fun captureRegion_partial_write_is_atomic_with_other_settings_updates() = runBlocking {
        val repository = fileBackedRepository(Files.createTempDirectory("region-concurrent-write").toFile())
        kotlinx.coroutines.coroutineScope {
            val a = async { repository.setCaptureRegion(CaptureRegion(1, 2, 30, 40), 400, 800) }
            val b = async { repository.update { it.copy(overlayAlpha = .55f, apiKey = "concurrent-test") } }
            a.await(); b.await()
        }
        val settings = repository.get()
        assertEquals(CaptureRegion(1, 2, 30, 40), settings.captureRegion)
        assertEquals(.55f, settings.overlayAlpha)
        assertEquals("concurrent-test", settings.apiKey)
    }

    private fun fileBackedRepository(root: File, cipher: SettingsSecretCipher = PlainTestCipher): SettingsRepository =
        SettingsRepository(FileBackedContext(root), cipher).apply {
            setDefaultPromptProvidersForTest(
                prompt = { "default prompt" },
                dictionaryPrompt = { "default dictionary prompt" },
            )
        }

    private class FileBackedContext(private val root: File) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = root
        override fun getPackageName(): String = "com.gameocr.app.repositorytest"
    }

    private object PlainTestCipher : SettingsSecretCipher {
        override fun encrypt(plainText: String): String = "test:$plainText"
        override fun decrypt(cipherText: String): String = cipherText.removePrefix("test:")
    }
}
