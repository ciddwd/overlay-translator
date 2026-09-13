package com.gameocr.app.ui

import com.gameocr.app.data.AutoOcrRoute
import com.gameocr.app.data.AutoOcrRoutingPolicy
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OcrEngineCatalog
import com.gameocr.app.data.OcrEngineGroup
import com.gameocr.app.data.Settings
import com.gameocr.app.download.ModelDownloadDependencies
import com.gameocr.app.download.ModelDownloadSpec

internal typealias AutoOcrOptionGroup = OcrEngineGroup
internal enum class AutoOcrOptionState { CHECKING, READY, DOWNLOAD, DOWNLOADING, UNCONFIGURED, UNSUPPORTED }

internal fun autoOcrOptionGroup(route: AutoOcrRoute?): AutoOcrOptionGroup =
    OcrEngineCatalog.option(route?.engine ?: OcrEngineKind.ML_KIT_AUTO).group

internal fun autoOcrCanSelect(settings: Settings, language: String, route: AutoOcrRoute?,
    state: AutoOcrOptionState): Boolean = state == AutoOcrOptionState.READY &&
    (route == null || AutoOcrRoutingPolicy.supports(settings, language, route))

internal fun autoOcrModelSpec(route: AutoOcrRoute?): ModelDownloadSpec? = when (route?.engine) {
    OcrEngineKind.PADDLE_ONNX -> ModelDownloadSpec.paddle(route.paddleVersion)
    OcrEngineKind.MANGA_OCR_JA -> ModelDownloadSpec.mangaOcr()
    else -> null
}

internal fun autoOcrOptionState(settings: Settings, language: String, route: AutoOcrRoute?,
    available: Set<AutoOcrRoute>?, activeModels: Set<ModelDownloadSpec>): AutoOcrOptionState {
    if (route == null) return AutoOcrOptionState.READY
    if (!AutoOcrRoutingPolicy.supports(settings, language, route)) return AutoOcrOptionState.UNSUPPORTED
    if (!AutoOcrRoutingPolicy.configured(settings, route)) return AutoOcrOptionState.UNCONFIGURED
    val model = autoOcrModelSpec(route) ?: return AutoOcrOptionState.READY
    if (available?.contains(route) == true) return AutoOcrOptionState.READY
    if (ModelDownloadDependencies.expand(listOf(model)).any { it in activeModels }) return AutoOcrOptionState.DOWNLOADING
    return if (available == null) AutoOcrOptionState.CHECKING else AutoOcrOptionState.DOWNLOAD
}
