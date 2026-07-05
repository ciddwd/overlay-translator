package com.gameocr.app.ocr

import com.gameocr.app.data.LlmMirrorChoice
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrientationModelInstallerValidationTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun validateModelFile_coversExpectedFailures() {
        data class Case(
            val name: String,
            val modelName: String,
            val write: (File) -> Unit,
            val expectedError: String?,
        )

        val cases = listOf(
            Case(
                name = "valid onnx binary",
                modelName = OrientationModelInstaller.FILE_MODEL,
                write = { it.writeSparse(byteArrayOf(0x08, 0x01, 0x12, 0x00), 2 * 1024 * 1024L) },
                expectedError = null,
            ),
            Case(
                name = "valid textline onnx binary",
                modelName = OrientationModelInstaller.FILE_TEXTLINE_MODEL,
                write = { it.writeSparse(byteArrayOf(0x08, 0x01, 0x12, 0x00), 512 * 1024L) },
                expectedError = null,
            ),
            Case(
                name = "html response",
                modelName = OrientationModelInstaller.FILE_MODEL,
                write = { it.writeSparse("<html>bad</html>".toByteArray(), 2 * 1024 * 1024L) },
                expectedError = "error response",
            ),
            Case(
                name = "git lfs pointer",
                modelName = OrientationModelInstaller.FILE_MODEL,
                write = {
                    it.writeSparse(
                        "version https://git-lfs.github.com/spec/v1".toByteArray(),
                        2 * 1024 * 1024L
                    )
                },
                expectedError = "error response",
            ),
            Case(
                name = "too small",
                modelName = OrientationModelInstaller.FILE_MODEL,
                write = { it.writeBytes(byteArrayOf(0x08, 0x01, 0x12, 0x00)) },
                expectedError = "too small",
            ),
            Case(
                name = "unexpected file",
                modelName = "weights.onnx",
                write = { it.writeSparse(byteArrayOf(0x08, 0x01), 2 * 1024 * 1024L) },
                expectedError = "unexpected",
            ),
        )

        cases.forEach { case ->
            val file = File(temp.root, case.name.replace(' ', '_'))
            case.write(file)

            val error = OrientationModelInstaller.validateModelFile(case.modelName, file)

            if (case.expectedError == null) {
                assertNull(case.name, error)
            } else {
                assertTrue(
                    "${case.name}: expected ${case.expectedError}, got $error",
                    error?.contains(case.expectedError, ignoreCase = true) == true
                )
            }
        }
    }

    @Test
    fun localImportTargetFileName_acceptsOfficialAndSingleOnnxNames() {
        data class Case(
            val displayName: String,
            val selectedCount: Int,
            val expected: String?,
        )

        val cases = listOf(
            Case("inference.onnx", 4, OrientationModelInstaller.FILE_MODEL),
            Case("PP-LCNet_x0_25_textline_ori.onnx", 4, OrientationModelInstaller.FILE_TEXTLINE_MODEL),
            Case("text_line_orientation.onnx", 4, OrientationModelInstaller.FILE_TEXTLINE_MODEL),
            Case("PP-LCNet_x1_0_doc_ori.onnx", 4, OrientationModelInstaller.FILE_MODEL),
            Case("screen-orientation.onnx", 4, OrientationModelInstaller.FILE_MODEL),
            Case("weights.onnx", 1, OrientationModelInstaller.FILE_MODEL),
            Case("weights.onnx", 4, null),
            Case("notes.txt", 1, null),
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                OrientationModelInstaller.localImportTargetFileName(case.displayName, case.selectedCount)
            )
        }
    }

    @Test
    fun urlsFor_ordersNetworkDownloadSourceAndMirrorShapes() {
        data class Case(
            val name: String,
            val choice: LlmMirrorChoice,
            val networkMirror: String?,
            val legacyMirror: String?,
            val expected: List<String>,
        )

        val cases = listOf(
            Case(
                name = "official first",
                choice = LlmMirrorChoice.HF_OFFICIAL,
                networkMirror = null,
                legacyMirror = null,
                expected = listOf(
                    OrientationModelInstaller.DEFAULT_MODEL_URL,
                    OrientationModelInstaller.HF_MIRROR_MODEL_URL,
                ),
            ),
            Case(
                name = "hf mirror first",
                choice = LlmMirrorChoice.HF_MIRROR,
                networkMirror = null,
                legacyMirror = null,
                expected = listOf(
                    OrientationModelInstaller.HF_MIRROR_MODEL_URL,
                    OrientationModelInstaller.DEFAULT_MODEL_URL,
                ),
            ),
            Case(
                name = "network custom directory adds canonical and upstream names",
                choice = LlmMirrorChoice.CUSTOM,
                networkMirror = "https://cdn.example/orientation",
                legacyMirror = null,
                expected = listOf(
                    "https://cdn.example/orientation/${OrientationModelInstaller.FILE_MODEL}",
                    "https://cdn.example/orientation/${OrientationModelInstaller.UPSTREAM_FILE_MODEL}",
                    OrientationModelInstaller.DEFAULT_MODEL_URL,
                ),
            ),
            Case(
                name = "legacy exact onnx wins over network custom directory",
                choice = LlmMirrorChoice.CUSTOM,
                networkMirror = "https://cdn.example/orientation",
                legacyMirror = "https://legacy.example/doc_ori.onnx",
                expected = listOf(
                    "https://legacy.example/doc_ori.onnx",
                    OrientationModelInstaller.DEFAULT_MODEL_URL,
                ),
            ),
            Case(
                name = "textline exact onnx is ignored for doc model",
                choice = LlmMirrorChoice.CUSTOM,
                networkMirror = null,
                legacyMirror = "https://legacy.example/textline_ori.onnx",
                expected = listOf(OrientationModelInstaller.DEFAULT_MODEL_URL),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                OrientationModelInstaller.urlsFor(
                    OrientationModelInstaller.FILE_MODEL,
                    case.choice,
                    case.networkMirror,
                    case.legacyMirror
                )
            )
        }
    }

    @Test
    fun urlsFor_textlineModelUsesTextlineReposAndCanonicalMirrorFileName() {
        data class Case(
            val name: String,
            val choice: LlmMirrorChoice,
            val networkMirror: String?,
            val legacyMirror: String?,
            val expected: List<String>,
        )

        val cases = listOf(
            Case(
                name = "official first",
                choice = LlmMirrorChoice.HF_OFFICIAL,
                networkMirror = null,
                legacyMirror = null,
                expected = listOf(
                    OrientationModelInstaller.DEFAULT_TEXTLINE_MODEL_URL,
                    OrientationModelInstaller.HF_MIRROR_TEXTLINE_MODEL_URL,
                ),
            ),
            Case(
                name = "hf mirror first",
                choice = LlmMirrorChoice.HF_MIRROR,
                networkMirror = null,
                legacyMirror = null,
                expected = listOf(
                    OrientationModelInstaller.HF_MIRROR_TEXTLINE_MODEL_URL,
                    OrientationModelInstaller.DEFAULT_TEXTLINE_MODEL_URL,
                ),
            ),
            Case(
                name = "custom directory uses canonical textline file",
                choice = LlmMirrorChoice.CUSTOM,
                networkMirror = "https://cdn.example/orientation",
                legacyMirror = null,
                expected = listOf(
                    "https://cdn.example/orientation/${OrientationModelInstaller.FILE_TEXTLINE_MODEL}",
                    OrientationModelInstaller.DEFAULT_TEXTLINE_MODEL_URL,
                ),
            ),
            Case(
                name = "legacy exact textline onnx wins",
                choice = LlmMirrorChoice.CUSTOM,
                networkMirror = "https://cdn.example/orientation",
                legacyMirror = "https://legacy.example/textline_ori.onnx",
                expected = listOf(
                    "https://legacy.example/textline_ori.onnx",
                    OrientationModelInstaller.DEFAULT_TEXTLINE_MODEL_URL,
                ),
            ),
            Case(
                name = "doc exact onnx is ignored for textline model",
                choice = LlmMirrorChoice.CUSTOM,
                networkMirror = null,
                legacyMirror = "https://legacy.example/doc_ori.onnx",
                expected = listOf(OrientationModelInstaller.DEFAULT_TEXTLINE_MODEL_URL),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                OrientationModelInstaller.urlsFor(
                    OrientationModelInstaller.FILE_TEXTLINE_MODEL,
                    case.choice,
                    case.networkMirror,
                    case.legacyMirror
                )
            )
        }
    }

    @Test
    fun bundledAssetPathUsesCanonicalModelFileName() {
        assertEquals(
            "models/doc_orientation/${OrientationModelInstaller.FILE_MODEL}",
            OrientationModelInstaller.BUNDLED_ASSET_MODEL
        )
        assertEquals(
            "models/doc_orientation/${OrientationModelInstaller.FILE_TEXTLINE_MODEL}",
            OrientationModelInstaller.BUNDLED_ASSET_TEXTLINE_MODEL
        )
        assertEquals(".bundled_disabled", OrientationModelInstaller.BUNDLED_DISABLED_MARKER)
        assertTrue(OrientationModelInstaller.MODEL_SPECS.any {
            it.fileName == OrientationModelInstaller.FILE_TEXTLINE_MODEL &&
                it.defaultUrl == OrientationModelInstaller.DEFAULT_TEXTLINE_MODEL_URL
        })
    }

    @Test
    fun bundledAssetsExistAndPassValidation() {
        data class Case(
            val modelName: String,
            val assetPath: String,
        )

        val cases = listOf(
            Case(OrientationModelInstaller.FILE_MODEL, OrientationModelInstaller.BUNDLED_ASSET_MODEL),
            Case(
                OrientationModelInstaller.FILE_TEXTLINE_MODEL,
                OrientationModelInstaller.BUNDLED_ASSET_TEXTLINE_MODEL
            ),
        )

        cases.forEach { case ->
            val file = mainAssetFile(case.assetPath)

            assertTrue("missing bundled asset: ${file.absolutePath}", file.exists())
            assertNull(
                "invalid bundled asset: ${case.assetPath}",
                OrientationModelInstaller.validateModelFile(case.modelName, file)
            )
        }
    }

    private fun File.writeSparse(prefix: ByteArray, totalLength: Long) {
        outputStream().use { it.write(prefix) }
        RandomAccessFile(this, "rw").use { it.setLength(totalLength) }
    }

    private fun mainAssetFile(assetPath: String): File {
        val appModule = File("src/main/assets", assetPath)
        if (appModule.exists()) return appModule
        return File("app/src/main/assets", assetPath)
    }
}
