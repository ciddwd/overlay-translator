package com.gameocr.app.ui

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenCommunityLinkTest {

    @Test
    fun qqGroupLink_hasExpectedDestinationAndParameters() {
        data class Case(
            val name: String,
            val actual: String?,
            val expected: String,
        )

        val uri = URI(QQ_GROUP_URL)
        val query = uri.rawQuery
            .split("&")
            .associate { pair ->
                val parts = pair.split("=", limit = 2)
                parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }

        listOf(
            Case("scheme", uri.scheme, "https"),
            Case("host", uri.host, "qun.qq.com"),
            Case("path", uri.path, "/universal-share/share"),
            Case("action", query["ac"], "1"),
            Case("service type", query["svctype"], "4"),
            Case("template", query["tempid"], "h5_group_info"),
        ).forEach { case ->
            assertEquals(case.name, case.expected, case.actual.orEmpty())
        }

        assertTrue("authKey", query["authKey"].orEmpty().isNotBlank())
        assertTrue("business data", query["busi_data"].orEmpty().isNotBlank())
        assertTrue("share data", query["data"].orEmpty().isNotBlank())
        assertEquals("group number", "1059655926", QQ_GROUP_NUMBER)
    }
}
