package com.gameocr.app.gallery

import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.TranslatorEngine
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTranslationWorkPolicyTest {

    @Test
    fun `selection normalization is table driven`() {
        data class Case(
            val name: String,
            val input: List<String>,
            val expected: List<String>,
        )

        listOf(
            Case("empty selection", emptyList(), emptyList()),
            Case("blank values removed", listOf("", "  ", "content://a"), listOf("content://a")),
            Case(
                "duplicates keep first order",
                listOf("content://a", "content://b", "content://a"),
                listOf("content://a", "content://b"),
            ),
            Case("values are trimmed", listOf(" content://a "), listOf("content://a")),
            Case(
                "selection is capped",
                (0..GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK).map { "content://$it" },
                (0 until GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK).map { "content://$it" },
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                GalleryTranslationWorkPolicy.normalizeSelection(case.input),
            )
        }
    }

    @Test
    fun `selection editing is table driven`() {
        data class Case(
            val name: String,
            val current: List<String>,
            val additions: List<String> = emptyList(),
            val removal: String? = null,
            val expected: List<String>,
        )

        listOf(
            Case(
                name = "new images append in picker order",
                current = listOf("content://a"),
                additions = listOf("content://b", "content://c"),
                expected = listOf("content://a", "content://b", "content://c"),
            ),
            Case(
                name = "adding an existing image does not duplicate it",
                current = listOf("content://a", "content://b"),
                additions = listOf("content://b", "content://c"),
                expected = listOf("content://a", "content://b", "content://c"),
            ),
            Case(
                name = "blank additions are ignored",
                current = listOf("content://a"),
                additions = listOf("", "  "),
                expected = listOf("content://a"),
            ),
            Case(
                name = "adding stops at task limit",
                current = (0 until GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK)
                    .map { "content://$it" },
                additions = listOf("content://overflow"),
                expected = (0 until GalleryTranslationWorkPolicy.MAX_IMAGES_PER_TASK)
                    .map { "content://$it" },
            ),
            Case(
                name = "selected image can be removed",
                current = listOf("content://a", "content://b", "content://c"),
                removal = "content://b",
                expected = listOf("content://a", "content://c"),
            ),
            Case(
                name = "removing an unknown image keeps selection",
                current = listOf("content://a"),
                removal = "content://missing",
                expected = listOf("content://a"),
            ),
            Case(
                name = "last selected image can be removed",
                current = listOf("content://a"),
                removal = "content://a",
                expected = emptyList(),
            ),
        ).forEach { case ->
            val actual = case.removal?.let {
                GalleryTranslationWorkPolicy.removeSelection(case.current, it)
            } ?: GalleryTranslationWorkPolicy.mergeSelection(case.current, case.additions)
            assertEquals(case.name, case.expected, actual)
        }
    }

    @Test
    fun `network constraints are table driven across engine combinations`() {
        data class Case(
            val name: String,
            val ocr: OcrEngineKind,
            val translator: TranslatorEngine,
            val expected: Boolean,
        )

        listOf(
            Case(
                "fully local ML Kit",
                OcrEngineKind.ML_KIT_AUTO,
                TranslatorEngine.GOOGLE_ML_KIT,
                false,
            ),
            Case(
                "local Paddle and local LLM",
                OcrEngineKind.PADDLE_ONNX,
                TranslatorEngine.LOCAL_HY_MT2,
                false,
            ),
            Case(
                "cloud OCR with local translation",
                OcrEngineKind.BAIDU,
                TranslatorEngine.GOOGLE_ML_KIT,
                true,
            ),
            Case(
                "local OCR with cloud translation",
                OcrEngineKind.MANGA_OCR_JA,
                TranslatorEngine.BAIDU_FANYI,
                true,
            ),
            Case(
                "LAN OCR still needs connectivity",
                OcrEngineKind.UMI_OCR,
                TranslatorEngine.LOCAL_HY_MT2,
                true,
            ),
            Case(
                "end to end picture translation",
                OcrEngineKind.ML_KIT_AUTO,
                TranslatorEngine.YOUDAO_PICTRANS,
                true,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                GalleryTranslationWorkPolicy.requiresNetwork(case.ocr, case.translator),
            )
        }
    }

    @Test
    fun `terminal task state is table driven`() {
        data class Case(
            val name: String,
            val progress: GalleryTaskProgress,
            val canceled: Boolean = false,
            val expected: GalleryTaskStatus,
        )

        listOf(
            Case("empty task", GalleryTaskProgress(0, 0, 0), expected = GalleryTaskStatus.FAILED),
            Case("still running", GalleryTaskProgress(5, 2, 1), expected = GalleryTaskStatus.RUNNING),
            Case("all succeeded", GalleryTaskProgress(5, 5, 0), expected = GalleryTaskStatus.SUCCEEDED),
            Case("all failed", GalleryTaskProgress(5, 0, 5), expected = GalleryTaskStatus.FAILED),
            Case("partial success", GalleryTaskProgress(5, 3, 2), expected = GalleryTaskStatus.PARTIAL),
            Case(
                "cancel overrides progress",
                GalleryTaskProgress(5, 3, 2),
                canceled = true,
                expected = GalleryTaskStatus.CANCELED,
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                GalleryTranslationWorkPolicy.terminalStatus(case.progress, case.canceled),
            )
        }
    }

    @Test
    fun `retry classification is table driven`() {
        data class Case(
            val name: String,
            val error: Throwable,
            val attempt: Int,
            val expected: Boolean,
        )

        listOf(
            Case("IO exception", IOException("offline"), 0, true),
            Case("wrapped IO exception", IllegalStateException("request", IOException("reset")), 1, true),
            Case("rate limited", IllegalStateException("HTTP 429"), 0, true),
            Case("server unavailable", IllegalStateException("HTTP 503"), 2, true),
            Case("authentication failure", IllegalStateException("HTTP 401"), 0, false),
            Case("invalid configuration", IllegalArgumentException("missing key"), 0, false),
            Case("retry budget exhausted", IOException("offline"), 3, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                GalleryTranslationWorkPolicy.shouldRetry(case.error, case.attempt),
            )
        }
    }

    @Test
    fun `foreground policy covers long and local model work`() {
        data class Case(
            val name: String,
            val count: Int,
            val translator: TranslatorEngine,
            val expected: Boolean,
        )

        listOf(
            Case("small cloud batch", 3, TranslatorEngine.BAIDU_FANYI, false),
            Case("large cloud batch", 10, TranslatorEngine.BAIDU_FANYI, true),
            Case("single Sakura image", 1, TranslatorEngine.LOCAL_SAKURA, true),
            Case("single HyMT2 image", 1, TranslatorEngine.LOCAL_HY_MT2, true),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                GalleryTranslationWorkPolicy.shouldUseForeground(case.count, case.translator),
            )
        }
    }

    @Test
    fun `empty translation retry follows setting and retry budget`() {
        data class Case(
            val name: String,
            val enabled: Boolean,
            val attempt: Int,
            val expected: Boolean,
        )

        listOf(
            Case("disabled", false, 0, false),
            Case("first retry", true, 1, true),
            Case("second retry", true, 2, true),
            Case("budget exhausted", true, 3, false),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                GalleryTranslationWorkPolicy.shouldRetryEmptyTranslation(
                    case.enabled,
                    case.attempt,
                ),
            )
        }
    }
}
