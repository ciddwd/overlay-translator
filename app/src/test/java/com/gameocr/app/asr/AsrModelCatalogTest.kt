package com.gameocr.app.asr

import com.gameocr.app.data.AsrEngineKind
import com.gameocr.app.data.AsrLanguage
import com.gameocr.app.data.AsrLanguagePolicy
import com.gameocr.app.data.AsrWhisperVariant
import com.gameocr.app.data.LlmMirrorChoice
import com.gameocr.app.download.ModelDownloadSpec
import com.gameocr.app.download.ModelDownloadType
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrModelCatalogTest {
    @Test fun settingsSelectExactlyOneModel_tableDriven() {
        data class Case(val engine: AsrEngineKind, val variant: AsrWhisperVariant, val expected: AsrModelId?)
        listOf(
            // The cloud engine has no on-device model to download, import or load.
            Case(AsrEngineKind.PARAFORMER_CLOUD, AsrWhisperVariant.TINY, null),
            Case(AsrEngineKind.SENSE_VOICE, AsrWhisperVariant.BASE, AsrModelId.SENSE_VOICE),
            Case(AsrEngineKind.ZIPFORMER, AsrWhisperVariant.TINY, AsrModelId.ZIPFORMER),
            Case(AsrEngineKind.WHISPER, AsrWhisperVariant.TINY, AsrModelId.WHISPER_TINY),
            Case(AsrEngineKind.WHISPER, AsrWhisperVariant.BASE, AsrModelId.WHISPER_BASE),
        ).forEach { assertEquals(it.toString(), it.expected, AsrModelId.forSettings(it.engine, it.variant)) }
    }

    @Test fun everyFileIsPinnedToACommitWithAHash() {
        val commit = Regex("[0-9a-f]{40}")
        AsrModelId.entries.forEach { model ->
            assertTrue(model.name, commit.matches(model.revision))
            model.files.forEach { file ->
                assertTrue("${model.name}/${file.name}", Regex("[0-9a-f]{64}").matches(file.sha256))
                assertTrue(file.bytes > 0)
                assertFalse(model.officialUrl(file).contains("/resolve/main/"))
                assertTrue(model.officialUrl(file).startsWith("https://huggingface.co/${model.repo}/resolve/${model.revision}/"))
                assertTrue(model.mirrorUrl(file).startsWith("https://hf-mirror.com/"))
            }
        }
    }

    @Test fun mirrorChoiceOrdersSources_tableDriven() {
        val model = AsrModelId.WHISPER_TINY
        val file = model.files.first()
        listOf(
            LlmMirrorChoice.HF_MIRROR to listOf(model.mirrorUrl(file), model.officialUrl(file)),
            LlmMirrorChoice.HF_OFFICIAL to listOf(model.officialUrl(file), model.mirrorUrl(file)),
            LlmMirrorChoice.CUSTOM to listOf(model.officialUrl(file), model.mirrorUrl(file)),
        ).forEach { (choice, expected) -> assertEquals(choice.name, expected, AsrModelInstaller.urlsFor(model, file, choice)) }
    }

    @Test fun validationRequiresExactSizeAndHash_tableDriven() {
        val dir = Files.createTempDirectory("asr-validate").toFile()
        val content = "tokens".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val file = File(dir, "tokens.txt").apply { writeBytes(content) }
        data class Case(val name: String, val spec: AsrModelFile, val ok: Boolean)
        listOf(
            Case("match", AsrModelFile("tokens.txt", content.size.toLong(), hash), true),
            Case("size", AsrModelFile("tokens.txt", content.size + 1L, hash), false),
            Case("hash", AsrModelFile("tokens.txt", content.size.toLong(), "0".repeat(64)), false),
        ).forEach { case ->
            assertEquals(case.name, case.ok, AsrModelInstaller.validate(case.spec, file) == null)
        }
        dir.deleteRecursively()
    }

    @Test fun languageOptionsFollowModelCapabilities_tableDriven() {
        data class Case(val engine: AsrEngineKind, val saved: AsrLanguage, val effective: AsrLanguage)
        listOf(
            Case(AsrEngineKind.SENSE_VOICE, AsrLanguage.YUE, AsrLanguage.YUE),
            Case(AsrEngineKind.SENSE_VOICE, AsrLanguage.JA, AsrLanguage.JA),
            Case(AsrEngineKind.WHISPER, AsrLanguage.YUE, AsrLanguage.AUTO),
            Case(AsrEngineKind.WHISPER, AsrLanguage.KO, AsrLanguage.KO),
            Case(AsrEngineKind.ZIPFORMER, AsrLanguage.JA, AsrLanguage.AUTO),
            Case(AsrEngineKind.ZIPFORMER, AsrLanguage.AUTO, AsrLanguage.AUTO),
        ).forEach { assertEquals(it.toString(), it.effective, AsrLanguagePolicy.effective(it.engine, it.saved)) }
        assertEquals(setOf("auto", "zh", "en", "ja", "ko", "yue"), AsrLanguagePolicy.options(AsrEngineKind.SENSE_VOICE).map { it.code }.toSet())
        assertTrue(AsrLanguagePolicy.options(AsrEngineKind.ZIPFORMER).isEmpty())
        assertFalse(AsrLanguage.YUE in AsrLanguagePolicy.options(AsrEngineKind.WHISPER))
    }

    @Test fun downloadSpecRoundTripsAndRejectsUnknownModels_tableDriven() {
        AsrModelId.entries.forEach { model ->
            val spec = ModelDownloadSpec.asr(model)
            assertEquals(ModelDownloadType.ASR, spec.type)
            assertEquals(spec, ModelDownloadSpec.decode(spec.encode()))
        }
        listOf("ASR|", "ASR|NOPE", "ASR").forEach { assertNull(it, ModelDownloadSpec.decode(it)) }
    }
}
