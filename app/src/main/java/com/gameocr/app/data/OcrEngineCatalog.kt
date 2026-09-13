package com.gameocr.app.data

import androidx.annotation.StringRes
import com.gameocr.app.R

enum class OcrEngineGroup { ON_DEVICE, LOCAL, CLOUD }

data class OcrEngineOption(
    val engine: OcrEngineKind,
    @param:StringRes val labelRes: Int,
    val group: OcrEngineGroup,
)

/** Shared names and display order for manual OCR and automatic language routing. */
object OcrEngineCatalog {
    val options = listOf(
        OcrEngineOption(OcrEngineKind.ML_KIT_AUTO, R.string.settings_ocr_chip_auto, OcrEngineGroup.ON_DEVICE),
        OcrEngineOption(OcrEngineKind.ML_KIT_JAPANESE, R.string.settings_ocr_chip_japanese, OcrEngineGroup.ON_DEVICE),
        OcrEngineOption(OcrEngineKind.ML_KIT_KOREAN, R.string.settings_ocr_chip_korean, OcrEngineGroup.ON_DEVICE),
        OcrEngineOption(OcrEngineKind.ML_KIT_CHINESE, R.string.settings_ocr_chip_chinese, OcrEngineGroup.ON_DEVICE),
        OcrEngineOption(OcrEngineKind.ML_KIT_LATIN, R.string.settings_ocr_chip_latin, OcrEngineGroup.ON_DEVICE),
        OcrEngineOption(OcrEngineKind.PADDLE_ONNX, R.string.settings_ocr_chip_paddle, OcrEngineGroup.ON_DEVICE),
        OcrEngineOption(OcrEngineKind.MANGA_OCR_JA, R.string.settings_ocr_chip_manga_ocr_ja, OcrEngineGroup.ON_DEVICE),
        OcrEngineOption(OcrEngineKind.UMI_OCR, R.string.settings_ocr_chip_umi, OcrEngineGroup.LOCAL),
        OcrEngineOption(OcrEngineKind.LUNA_OCR, R.string.settings_ocr_chip_luna, OcrEngineGroup.LOCAL),
        OcrEngineOption(OcrEngineKind.BAIDU, R.string.settings_ocr_chip_baidu, OcrEngineGroup.CLOUD),
        OcrEngineOption(OcrEngineKind.TENCENT, R.string.settings_ocr_chip_tencent, OcrEngineGroup.CLOUD),
        OcrEngineOption(OcrEngineKind.YOUDAO, R.string.settings_ocr_chip_youdao, OcrEngineGroup.CLOUD),
        OcrEngineOption(OcrEngineKind.PADDLE_AI_STUDIO, R.string.settings_ocr_chip_paddle_ai_studio, OcrEngineGroup.CLOUD),
    )

    fun option(engine: OcrEngineKind): OcrEngineOption = options.first { it.engine == engine }

    fun optionsIn(group: OcrEngineGroup): List<OcrEngineOption> = options.filter { it.group == group }

    // Automatic routing cannot select itself. Every other outer option stays visible,
    // including missing models; language compatibility only affects selection/runtime.
    fun automaticRoutes(): List<AutoOcrRoute> = options
        .filterNot { it.engine == OcrEngineKind.ML_KIT_AUTO }
        .flatMap { option ->
            if (option.engine == OcrEngineKind.PADDLE_ONNX) {
                PaddleModelVersion.entries.map { AutoOcrRoute(option.engine, it) }
            } else listOf(AutoOcrRoute(option.engine))
        }
}
