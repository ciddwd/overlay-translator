package com.gameocr.app.ocr

import com.gameocr.app.data.BaiduOcrEndpoint
import com.gameocr.app.data.BaiduOcrLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaiduOcrEngineTest {

    @Test
    fun baiduLanguageTypeFor_matchesEndpointSupport() {
        data class Case(
            val name: String,
            val endpoint: BaiduOcrEndpoint,
            val language: BaiduOcrLanguage,
            val expected: String?,
        )

        val cases = listOf(
            Case(
                name = "general keeps supported japanese",
                endpoint = BaiduOcrEndpoint.GENERAL,
                language = BaiduOcrLanguage.JAP,
                expected = "JAP",
            ),
            Case(
                name = "general coerces unsupported auto detect to chinese english",
                endpoint = BaiduOcrEndpoint.GENERAL,
                language = BaiduOcrLanguage.AUTO_DETECT,
                expected = "CHN_ENG",
            ),
            Case(
                name = "accurate keeps auto detect",
                endpoint = BaiduOcrEndpoint.ACCURATE,
                language = BaiduOcrLanguage.AUTO_DETECT,
                expected = "auto_detect",
            ),
            Case(
                name = "webimage omits language type",
                endpoint = BaiduOcrEndpoint.WEBIMAGE,
                language = BaiduOcrLanguage.JAP,
                expected = null,
            ),
        )

        cases.forEach { case ->
            val actual = baiduLanguageTypeFor(case.endpoint, case.language)
            if (case.expected == null) {
                assertNull(case.name, actual)
            } else {
                assertEquals(case.name, case.expected, actual)
            }
        }
    }
}
