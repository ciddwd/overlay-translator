package com.gameocr.app.ui

import com.gameocr.app.data.OcrEngineKind
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleAiStudioSettingsHelpTest {

    @Test
    fun helpVisibility_isLimitedToPaddleAiStudioEngine() {
        data class Case(
            val engine: OcrEngineKind,
            val expected: Boolean,
        )

        OcrEngineKind.entries
            .map { engine -> Case(engine, engine == OcrEngineKind.PADDLE_AI_STUDIO) }
            .forEach { case ->
                assertEquals(
                    case.engine.name,
                    case.expected,
                    shouldShowPaddleAiStudioHelp(case.engine),
                )
            }
    }

    @Test
    fun externalPageUrl_hasExpectedHttpsLocation() {
        data class Case(
            val field: String,
            val actual: String?,
            val expected: String,
        )

        val uri = URI(PADDLE_AI_STUDIO_PAGE_URL)
        listOf(
            Case("scheme", uri.scheme, "https"),
            Case("host", uri.host, "aistudio.baidu.com"),
            Case("path", uri.path, "/paddleocr"),
            Case("query", uri.query, ""),
        ).forEach { case ->
            assertEquals(case.field, case.expected, case.actual.orEmpty())
        }
    }
}
