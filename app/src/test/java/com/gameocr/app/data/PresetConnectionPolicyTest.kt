package com.gameocr.app.data

import org.junit.Assert.*
import org.junit.Test

class PresetConnectionPolicyTest {
    @Test fun selectedProviderOwnsItsConnection_tableDriven() {
        val current = Settings(baseUrl = "https://current.test", model = "current-model",
            anthropicBaseUrl = "https://current-anthropic.test", anthropicModel = "current-anthropic",
            deeplBaseUrl = "https://current-deepl.test", niuTransTermLibraryId = "current-term",
            umiOcrBaseUrl = "http://current-umi.test", lunaOcrBaseUrl = "http://current-luna.test",
            tencentRegion = "current-region")
        TranslatorEngine.entries.forEach { engine ->
            val source = current.copy(translatorEngine = engine, baseUrl = "https://saved.test", model = "saved-model",
                anthropicBaseUrl = "https://saved-anthropic.test", anthropicModel = "saved-anthropic",
                deeplBaseUrl = "https://saved-deepl.test", niuTransTermLibraryId = "saved-term",
                tencentRegion = "saved-region")
            val preset = TranslationPresetCatalog.fromSettings("id", "name", "name", source)
            val applied = preset.applyTo(current)
            assertEquals(engine.name, if (engine == TranslatorEngine.OPENAI) source.baseUrl else current.baseUrl, applied.baseUrl)
            assertEquals(engine.name, if (engine == TranslatorEngine.OPENAI) source.model else current.model, applied.model)
            assertEquals(engine.name, if (engine == TranslatorEngine.ANTHROPIC) source.anthropicBaseUrl else current.anthropicBaseUrl, applied.anthropicBaseUrl)
            assertEquals(engine.name, if (engine == TranslatorEngine.DEEPL) source.deeplBaseUrl else current.deeplBaseUrl, applied.deeplBaseUrl)
            assertEquals(engine.name, if (engine == TranslatorEngine.NIUTRANS) source.niuTransTermLibraryId else current.niuTransTermLibraryId, applied.niuTransTermLibraryId)
            assertEquals(engine.name, if (engine == TranslatorEngine.TENCENT) source.tencentRegion else current.tencentRegion, applied.tencentRegion)
            assertTrue(engine.name, TranslationPresetCatalog.matchesSettings(preset, applied))
        }
        OcrEngineKind.entries.forEach { engine ->
            val source = current.copy(ocrEngine = engine, umiOcrBaseUrl = "http://saved-umi.test",
                lunaOcrBaseUrl = "http://saved-luna.test", tencentRegion = "saved-region")
            val preset = TranslationPresetCatalog.fromSettings("id", "name", "name", source)
            val applied = preset.applyTo(current)
            assertEquals(engine.name, if (engine == OcrEngineKind.UMI_OCR) source.umiOcrBaseUrl else current.umiOcrBaseUrl, applied.umiOcrBaseUrl)
            assertEquals(engine.name, if (engine == OcrEngineKind.LUNA_OCR) source.lunaOcrBaseUrl else current.lunaOcrBaseUrl, applied.lunaOcrBaseUrl)
            assertEquals(engine.name, if (engine == OcrEngineKind.TENCENT) source.tencentRegion else current.tencentRegion, applied.tencentRegion)
            assertEquals(engine, applied.ocrEngine)
            assertTrue(engine.name, TranslationPresetCatalog.matchesSettings(preset, applied))
        }
    }

    @Test fun missingCredentialAndExplicitBlankAreNeverBorrowedFromAnotherPreset() {
        val current = Settings(baseUrl = "https://same.test", apiKey = "other-preset-key")
        val offline = TranslationPresetCatalog.builtIns().single().applyTo(current)
        assertEquals(current.apiKey, PresetCredentialPolicy.apply("offline", offline, null).apiKey)
        val blankRecord = PresetCredentialPolicy.capture("blank", current.copy(apiKey = ""))
        assertEquals("", PresetCredentialPolicy.apply("blank", current, blankRecord).apiKey)
        // A cloud preset without a local credential record cannot silently use another account.
        assertEquals("", PresetCredentialPolicy.apply("missing", current, null).apiKey)
        assertFalse(PresetCredentialPolicy.matches("missing", current, null))
        assertTrue(PresetCredentialPolicy.matches("blank", current.copy(apiKey = ""), blankRecord))
    }
}
