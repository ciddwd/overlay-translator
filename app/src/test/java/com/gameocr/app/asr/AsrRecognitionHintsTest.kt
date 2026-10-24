package com.gameocr.app.asr

import com.gameocr.app.data.*
import com.gameocr.app.glossary.GlossaryTermCategory
import com.gameocr.app.glossary.GlossaryTermEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class AsrRecognitionHintsTest {
    private val base = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD,
        asrCloudProvider = AsrCloudProvider.OPENAI_COMPATIBLE, asrLanguage = AsrLanguage.EN,
        asrHints = AsrHintSettings(openAiMode = AsrHintMode.KEYWORDS))
    private fun term(text: String, scope: String = "", lang: String = "en", enabled: Boolean = true,
        category: GlossaryTermCategory = GlossaryTermCategory.TERM) = GlossaryTermEntity(
        sourceTerm = text, targetTerm = "译名", sourceLang = lang, targetLang = "zh-CN",
        scopePackage = scope, enabled = enabled, category = category, createdAtMs = 1, updatedAtMs = 1)

    @Test fun glossaryUsesOnlyEnabledSourceTermsForCurrentAppAndLanguage() {
        val terms = listOf(term("global"), term("Winterfell", "player"), term("other", "other"),
            term("wrong-language", lang = "ja"), term("disabled", enabled = false),
            term("pattern", category = GlossaryTermCategory.PRESERVE_SOURCE), term("regional", lang = "en-US"))
        val s = base.copy(asrHints = base.asrHints.copy(useGlossary = true, hotwords = "Manual\nwinterfell\nManual"))
        assertEquals(listOf("Manual", "winterfell", "global", "regional"), AsrHintBuilder.build(s, terms, "player").hotwords)
        assertEquals(listOf("Winterfell", "global", "regional"), AsrHintBuilder.build(
            s.copy(asrHints = s.asrHints.copy(hotwords = "")), terms, "player").hotwords)
        assertEquals(listOf("global", "regional"), AsrHintBuilder.build(
            s.copy(asrHints = s.asrHints.copy(hotwords = "")), terms, null).hotwords)
    }

    @Test fun defaultsDoNotSendHintsAndLocalModelsDoNotReceiveUnsupportedFields() {
        for (engine in AsrEngineKind.entries.filterNot { it.isCloud }) {
            val hints = AsrHintBuilder.build(base.copy(asrEngine = engine, asrHints = AsrHintSettings(
                prompt = "medical course", hotwords = "term", useGlossary = true)))
            assertEquals("", hints.prompt); assertTrue(hints.hotwords.isEmpty())
        }
        assertEquals(AsrRecognitionHints(), AsrHintBuilder.build(Settings()))
        assertEquals(AsrHintSettings(), Json.decodeFromString<Settings>("{}").asrHints)
    }

    @Test fun automaticCapabilitiesRequireVerifiedHostAndModelAndRespectManualOverride() {
        data class Case(val host: String, val model: String, val mode: AsrHintMode)
        listOf(Case("api.openai.com", "gpt-transcribe", AsrHintMode.KEYWORDS),
            Case("api.openai.com", "gpt-live-transcribe-2026-10-15", AsrHintMode.KEYWORDS),
            Case("api.openai.com", "whisper-1", AsrHintMode.PROMPT_HOTWORDS),
            Case("api.openai.com", "gpt-4o-transcribe-diarize", AsrHintMode.AUTO),
            Case("api.siliconflow.cn", "FunAudioLLM/SenseVoiceSmall", AsrHintMode.AUTO),
            Case("api.openai.com.example", "gpt-transcribe", AsrHintMode.AUTO)).forEach {
            val c = AsrCloudConnections(openAiBaseUrl = "https://${it.host}/v1", openAiModel = it.model)
            assertEquals(it.toString(), it.mode, AsrHintPolicy.openAi(c).mode)
            assertEquals(AsrHintMode.PROMPT, AsrHintPolicy.openAi(c, AsrHintMode.PROMPT).mode)
        }
    }

    @Test fun wordsAreDeduplicatedBoundedAndFilteredForNativeTencent() {
        val raw = "café\ncafe\u0301\n\nbad\tword\n<bad>\n" + (1..70).joinToString("\n") { "word$it" }
        val hints = AsrHintBuilder.build(base.copy(asrHints = base.asrHints.copy(hotwords = raw)))
        assertEquals(50, hints.hotwords.size); assertEquals("café", hints.hotwords.first())
        assertFalse(hints.hotwords.any { '<' in it || '\t' in it })
        val tencent = base.copy(asrCloudProvider = AsrCloudProvider.TENCENT, asrHints = AsrHintSettings(
            hotwords = "two words\nvalid\na,b\nx|y\n" + "中".repeat(11)))
        assertEquals(listOf("valid"), AsrHintBuilder.build(tencent).hotwords)
    }

    @Test fun configuredPromptAndTermsHaveOneUtf8Budget() {
        val hints = AsrRecognitionHints(prompt = "场景", hotwords = listOf("Winterfell"),
            mode = AsrHintMode.PROMPT_HOTWORDS)
        assertEquals("Winterfell\n场景", hints.promptWith())
        assertTrue(hints.promptWith(20).toByteArray().size <= 20)
        assertEquals("😀", AsrHintBuilder.utf8Prefix("😀😀", 7))
        assertEquals("", AsrHintBuilder.utf8Prefix("中", 2))
    }

    @Test fun portableHintsExcludeConnectionKeysAndOldDraftCannotOverwriteSavedSwitches() {
        val s = base.copy(asrHints = AsrHintSettings("scene", "Winterfell", true, AsrHintMode.KEYWORDS,
            mapOf(AsrCloudModel.PARAFORMER.modelId to "vocab-id")),
            asrCloudConnections = AsrCloudConnections(glmApiKey = "never-export-this-key"))
        val portable = SettingsFieldPolicy.encodePortable(s)
        assertFalse(portable.toString().contains("never-export-this-key"))
        assertEquals(s.asrHints, SettingsFieldPolicy.decodePortable(portable).settings.asrHints)
        val draft = AsrCredentialDraft.from(s).copy(hints = s.asrHints.copy(prompt = "edited"))
        val saved = draft.applyTo(s.copy(asrHints = s.asrHints.copy(useGlossary = false, openAiMode = AsrHintMode.PROMPT)))
        assertEquals("edited", saved.asrHints.prompt); assertFalse(saved.asrHints.useGlossary)
        assertEquals(AsrHintMode.PROMPT, saved.asrHints.openAiMode)
        assertEquals(s.asrHints, Json.decodeFromString<Settings>(Json.encodeToString(s)).asrHints)
    }

    @Test fun eachAliyunModelOnlyExposesSupportedHintFields() {
        for (model in AsrCloudModel.entries) {
            val cap = AsrHintPolicy.capabilities(base.copy(asrCloudProvider = AsrCloudProvider.ALIYUN, asrCloudModel = model))
            assertEquals(model.toString(), model in setOf(AsrCloudModel.PARAFORMER, AsrCloudModel.FUN_ASR,
                AsrCloudModel.QWEN_AUDIO_STREAMING), cap.vocabulary)
            assertEquals(model == AsrCloudModel.QWEN_AUDIO_STREAMING, cap.hotwords)
            assertFalse(cap.prompt)
        }
    }
}
