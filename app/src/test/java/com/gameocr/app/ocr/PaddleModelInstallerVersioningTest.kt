package com.gameocr.app.ocr

import com.gameocr.app.data.LlmMirrorChoice
import com.gameocr.app.data.PaddleModelVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleModelInstallerVersioningTest {

    @Test
    fun localImportTargetFileName_mapsVersionSpecificAuxiliaryFiles() {
        data class Case(
            val displayName: String,
            val version: PaddleModelVersion,
            val expected: String?,
        )

        val cases = listOf(
            Case("ppocrv5-mobile-det.onnx", PaddleModelVersion.V5_MOBILE, PaddleModelInstaller.FILE_DET),
            Case("ppocrv5-mobile-rec.onnx", PaddleModelVersion.V5_MOBILE, PaddleModelInstaller.FILE_REC),
            Case("inference.yml", PaddleModelVersion.V5_MOBILE, PaddleModelInstaller.FILE_KEYS_YAML),
            Case("keys.yaml", PaddleModelVersion.V5_MOBILE, PaddleModelInstaller.FILE_KEYS_YAML),
            Case("ppocrv5_dict.txt", PaddleModelVersion.V5_MOBILE, null),
            Case("inference.yml", PaddleModelVersion.V6_TINY, PaddleModelInstaller.FILE_KEYS_YAML),
            Case("keys.yaml", PaddleModelVersion.V6_TINY, PaddleModelInstaller.FILE_KEYS_YAML),
            Case("ppocrv6_dict.txt", PaddleModelVersion.V6_TINY, null),
            Case("inference.yml", PaddleModelVersion.V6_SMALL, PaddleModelInstaller.FILE_KEYS_YAML),
            Case("keys.yaml", PaddleModelVersion.V6_SMALL, PaddleModelInstaller.FILE_KEYS_YAML),
            Case("ppocrv6_dict.txt", PaddleModelVersion.V6_SMALL, null),
            Case("inference.yml", PaddleModelVersion.V6_MEDIUM, PaddleModelInstaller.FILE_KEYS_YAML),
            Case("keys.yaml", PaddleModelVersion.V6_MEDIUM, PaddleModelInstaller.FILE_KEYS_YAML),
            Case("ppocrv6_dict.txt", PaddleModelVersion.V6_MEDIUM, null),
            Case("notes.txt", PaddleModelVersion.V5_MOBILE, null),
            Case("notes.txt", PaddleModelVersion.V6_SMALL, null),
            Case("notes.txt", PaddleModelVersion.V6_MEDIUM, null),
            Case("det.bin", PaddleModelVersion.V5_MOBILE, null),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                PaddleModelInstaller.localImportTargetFileName(case.displayName, case.version)
            )
        }
    }

    @Test
    fun keysFileName_usesOfficialYamlForEveryVersion() {
        data class Case(
            val version: PaddleModelVersion,
            val expected: String,
        )

        val cases = listOf(
            Case(PaddleModelVersion.V5_MOBILE, PaddleModelInstaller.FILE_KEYS_YAML),
            Case(PaddleModelVersion.V6_TINY, PaddleModelInstaller.FILE_KEYS_YAML),
            Case(PaddleModelVersion.V6_SMALL, PaddleModelInstaller.FILE_KEYS_YAML),
            Case(PaddleModelVersion.V6_MEDIUM, PaddleModelInstaller.FILE_KEYS_YAML),
        )

        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, PaddleModelInstaller.keysFileName(case.version))
        }
    }

    @Test
    fun defaultModelUrls_useVersionSpecificOfficialAndMirrorRepos() {
        data class Case(
            val version: PaddleModelVersion,
            val detRepo: String,
            val recRepo: String,
            val keysRepo: String,
            val keysSuffix: String,
            val detRevision: String = "main",
            val recRevision: String = "main",
        )

        val cases = listOf(
            Case(
                version = PaddleModelVersion.V5_MOBILE,
                detRepo = "PaddlePaddle/PP-OCRv5_mobile_det_onnx",
                recRepo = "PaddlePaddle/PP-OCRv5_mobile_rec_onnx",
                keysRepo = "PaddlePaddle/PP-OCRv5_mobile_rec_onnx",
                keysSuffix = "inference.yml",
                detRevision = "e6f4fa85f00e168c862bc462aebca69eef9b3d3d",
                recRevision = "ed152b8b495f84de93cda5709d768548a9127622",
            ),
            Case(
                version = PaddleModelVersion.V6_TINY,
                detRepo = "PaddlePaddle/PP-OCRv6_tiny_det_onnx",
                recRepo = "PaddlePaddle/PP-OCRv6_tiny_rec_onnx",
                keysRepo = "PaddlePaddle/PP-OCRv6_tiny_rec_onnx",
                keysSuffix = "inference.yml",
            ),
            Case(
                version = PaddleModelVersion.V6_SMALL,
                detRepo = "PaddlePaddle/PP-OCRv6_small_det_onnx",
                recRepo = "PaddlePaddle/PP-OCRv6_small_rec_onnx",
                keysRepo = "PaddlePaddle/PP-OCRv6_small_rec_onnx",
                keysSuffix = "inference.yml",
            ),
            Case(
                version = PaddleModelVersion.V6_MEDIUM,
                detRepo = "PaddlePaddle/PP-OCRv6_medium_det_onnx",
                recRepo = "PaddlePaddle/PP-OCRv6_medium_rec_onnx",
                keysRepo = "PaddlePaddle/PP-OCRv6_medium_rec_onnx",
                keysSuffix = "inference.yml",
            ),
        )

        cases.forEach { case ->
            val urls = PaddleModelInstaller.defaultModelUrls(case.version)
            val officialDet = "huggingface.co/${case.detRepo}/resolve/${case.detRevision}/"
            val mirrorDet = "hf-mirror.com/${case.detRepo}/resolve/${case.detRevision}/"
            val officialRec = "huggingface.co/${case.recRepo}/resolve/${case.recRevision}/"
            val mirrorRec = "hf-mirror.com/${case.recRepo}/resolve/${case.recRevision}/"
            val officialKeys = "huggingface.co/${case.keysRepo}/resolve/${case.recRevision}/"
            val mirrorKeys = "hf-mirror.com/${case.keysRepo}/resolve/${case.recRevision}/"
            assertTrue(case.toString(), urls.det.all { it.contains(case.detRepo) })
            assertTrue(case.toString(), urls.rec.all { it.contains(case.recRepo) })
            assertTrue(case.toString(), urls.keys.all { it.contains(case.keysRepo) })
            assertTrue(case.toString(), urls.det.any { it.contains(officialDet) })
            assertTrue(case.toString(), urls.det.any { it.contains(mirrorDet) })
            assertTrue(case.toString(), urls.rec.any { it.contains(officialRec) })
            assertTrue(case.toString(), urls.rec.any { it.contains(mirrorRec) })
            assertTrue(case.toString(), urls.keys.any { it.contains(officialKeys) })
            assertTrue(case.toString(), urls.keys.any { it.contains(mirrorKeys) })
            assertTrue(case.toString(), urls.det.all { it.endsWith("inference.onnx") })
            assertTrue(case.toString(), urls.rec.all { it.endsWith("inference.onnx") })
            assertTrue(case.toString(), urls.keys.all { it.endsWith(case.keysSuffix) })
            assertTrue(case.toString(), (urls.det + urls.rec + urls.keys).none { "bukuroo" in it })
        }
    }

    @Test
    fun modelDirectoryName_isVersionedSoLegacyRootFilesAreNotReused() {
        PaddleModelVersion.entries.forEach { version ->
            assertEquals(version.name, version.dirName, PaddleModelInstaller.modelDirectoryName(version))
        }
    }

    @Test
    fun urlsFor_ordersNetworkDownloadSourceAndLegacyMirror() {
        val official = "https://huggingface.co/example/repo/resolve/main/det.onnx"
        val hfMirror = "https://hf-mirror.com/example/repo/resolve/main/det.onnx"
        val defaults = listOf(official, hfMirror)

        data class Case(
            val name: String,
            val userMirror: String?,
            val choice: LlmMirrorChoice,
            val expected: List<String>,
        )

        val cases = listOf(
            Case(
                name = "official first",
                userMirror = null,
                choice = LlmMirrorChoice.HF_OFFICIAL,
                expected = listOf(official, hfMirror),
            ),
            Case(
                name = "hf mirror first",
                userMirror = null,
                choice = LlmMirrorChoice.HF_MIRROR,
                expected = listOf(hfMirror, official),
            ),
            Case(
                name = "network custom base first",
                userMirror = "https://cdn.example/models",
                choice = LlmMirrorChoice.CUSTOM,
                expected = listOf("https://cdn.example/models/det.onnx", official, hfMirror),
            ),
            Case(
                name = "legacy per-model mirror still wins",
                userMirror = "https://legacy.example/paddle/",
                choice = LlmMirrorChoice.HF_MIRROR,
                expected = listOf("https://legacy.example/paddle/det.onnx", hfMirror, official),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                PaddleModelInstaller.urlsFor(case.userMirror, PaddleModelInstaller.FILE_DET, defaults, case.choice)
            )
        }
    }
}
