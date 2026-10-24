package com.gameocr.app.data

/** A preset owns only the connections used by its selected engines. Secrets are handled separately. */
internal object PresetConnectionPolicy {
    fun activeConnections(preset: TranslationPreset, current: Settings): TranslationPreset = with(preset) {
        val openAi = translatorEngine == TranslatorEngine.OPENAI
        val anthropic = translatorEngine == TranslatorEngine.ANTHROPIC
        val deepl = translatorEngine == TranslatorEngine.DEEPL
        val niuTrans = translatorEngine == TranslatorEngine.NIUTRANS
        val baidu = ocrEngine == OcrEngineKind.BAIDU
        val tencentOcr = ocrEngine == OcrEngineKind.TENCENT
        val tencent = tencentOcr || translatorEngine == TranslatorEngine.TENCENT
        val paddle = ocrEngine == OcrEngineKind.PADDLE_ONNX || ocrEngine == OcrEngineKind.MANGA_OCR_JA
        copy(
            baseUrl = if (openAi) baseUrl else current.baseUrl,
            model = if (openAi) model else current.model,
            openAiProvider = if (openAi) openAiProvider else current.openAiProvider,
            openAiRequestOptions = if (openAi || anthropic) openAiRequestOptions else current.openAiRequestOptions,
            anthropicBaseUrl = if (anthropic) anthropicBaseUrl else current.anthropicBaseUrl,
            anthropicModel = if (anthropic) anthropicModel else current.anthropicModel,
            deeplPro = if (deepl) deeplPro else current.deeplPro,
            deeplProtocol = if (deepl) deeplProtocol else current.deeplProtocol,
            deeplBaseUrl = if (deepl) deeplBaseUrl else current.deeplBaseUrl,
            deeplBearerAuth = if (deepl) deeplBearerAuth else current.deeplBearerAuth,
            niuTransMode = if (niuTrans) niuTransMode else current.niuTransMode,
            niuTransTermLibraryId = if (niuTrans) niuTransTermLibraryId else current.niuTransTermLibraryId,
            niuTransMemoryLibraryId = if (niuTrans) niuTransMemoryLibraryId else current.niuTransMemoryLibraryId,
            baiduOcrEndpoint = if (baidu) baiduOcrEndpoint else current.baiduOcrEndpoint,
            baiduOcrLanguage = if (baidu) baiduOcrLanguage else current.baiduOcrLanguage,
            umiOcrBaseUrl = if (ocrEngine == OcrEngineKind.UMI_OCR) umiOcrBaseUrl else current.umiOcrBaseUrl,
            lunaOcrBaseUrl = if (ocrEngine == OcrEngineKind.LUNA_OCR) lunaOcrBaseUrl else current.lunaOcrBaseUrl,
            tencentRegion = if (tencent) tencentRegion else current.tencentRegion,
            tencentOcrEndpoint = if (tencentOcr) tencentOcrEndpoint else current.tencentOcrEndpoint,
            tencentOcrLanguage = if (tencentOcr) tencentOcrLanguage else current.tencentOcrLanguage,
            paddleModelVersion = if (paddle) paddleModelVersion else current.paddleModelVersion,
            paddleDetectionProfile = if (paddle) paddleDetectionProfile else current.paddleDetectionProfile,
            ttsProvider = if (ttsEnabled) ttsProvider else current.ttsProvider,
        )
    }
}
