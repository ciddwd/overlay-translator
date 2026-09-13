package com.gameocr.app.data

import org.junit.Assert.*
import org.junit.Test

class OcrEngineCatalogTest {
    @Test fun everyEngineHasOneCatalogEntryAndOneName() {
        val engines = OcrEngineCatalog.options.map { it.engine }
        assertEquals(OcrEngineKind.entries.toSet(), engines.toSet())
        assertEquals(engines.size, engines.distinct().size)
        OcrEngineCatalog.options.forEach { assertEquals(it, OcrEngineCatalog.option(it.engine)) }
    }

    @Test fun groupsPreserveOuterDisplayOrder_tableDriven() {
        val groups = mapOf(
            OcrEngineGroup.ON_DEVICE to listOf(OcrEngineKind.ML_KIT_AUTO, OcrEngineKind.ML_KIT_JAPANESE,
                OcrEngineKind.ML_KIT_KOREAN, OcrEngineKind.ML_KIT_CHINESE, OcrEngineKind.ML_KIT_LATIN,
                OcrEngineKind.PADDLE_ONNX, OcrEngineKind.MANGA_OCR_JA),
            OcrEngineGroup.LOCAL to listOf(OcrEngineKind.UMI_OCR, OcrEngineKind.LUNA_OCR),
            OcrEngineGroup.CLOUD to listOf(OcrEngineKind.BAIDU, OcrEngineKind.TENCENT,
                OcrEngineKind.YOUDAO, OcrEngineKind.PADDLE_AI_STUDIO),
        )
        assertEquals(OcrEngineGroup.entries.toList(), OcrEngineCatalog.options.map { it.group }.distinct())
        groups.forEach { (group, expected) ->
            assertEquals(group.name, expected, OcrEngineCatalog.optionsIn(group).map { it.engine })
        }
    }

    @Test fun automaticCatalogIncludesEveryOuterOptionAndEveryPaddleVersion() {
        val routes = OcrEngineCatalog.automaticRoutes()
        assertEquals(routes.size, routes.distinct().size)
        OcrEngineCatalog.options.forEach { option ->
            val expected = when (option.engine) {
                OcrEngineKind.ML_KIT_AUTO -> emptyList()
                OcrEngineKind.PADDLE_ONNX -> PaddleModelVersion.entries.map { AutoOcrRoute(option.engine, it) }
                else -> listOf(AutoOcrRoute(option.engine))
            }
            assertEquals(option.engine.name, expected, routes.filter { it.engine == option.engine })
        }
    }
}
