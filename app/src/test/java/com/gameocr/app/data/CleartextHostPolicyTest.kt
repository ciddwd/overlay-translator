package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleartextHostPolicyTest {

    @Test
    fun validateMultiline_tableDriven_acceptsAndCanonicalizesHostOnlyValues() {
        data class Case(
            val name: String,
            val input: String,
            val expected: List<String>,
        )

        listOf(
            Case("hostname", "api.example.com", listOf("api.example.com")),
            Case("hostname spelling is preserved", "API.Example.COM", listOf("API.Example.COM")),
            Case("IPv4", "192.168.0.159", listOf("192.168.0.159")),
            Case("raw IPv6", "2001:db8::1", listOf("2001:db8::1")),
            Case("bracketed IPv6", "[2001:db8::1]", listOf("2001:db8::1")),
            Case("localhost", "localhost", listOf("localhost")),
            Case(
                "blank lines and duplicates",
                "\n Example.com \nexample.com\n192.168.1.2\n",
                listOf("Example.com", "192.168.1.2"),
            ),
            Case("international domain", "例子.测试", listOf("xn--fsqu00a.xn--0zwm56d")),
        ).forEach { case ->
            val result = CleartextHostPolicy.validateMultiline(case.input)

            assertTrue(case.name, result.isValid)
            assertEquals(case.name, case.expected, result.hosts)
        }
    }

    @Test
    fun validateMultiline_tableDriven_rejectsUrlsPortsPathsAndMalformedHosts() {
        data class Case(
            val name: String,
            val input: String,
            val expectedKind: CleartextHostIssueKind,
        )

        listOf(
            Case("HTTP scheme", "http://example.com", CleartextHostIssueKind.SCHEME),
            Case("HTTPS scheme uppercase", "HTTPS://example.com", CleartextHostIssueKind.SCHEME),
            Case("other URL scheme", "ftp://example.com", CleartextHostIssueKind.SCHEME),
            Case("root path", "example.com/", CleartextHostIssueKind.PATH_OR_PARAMETERS),
            Case("API path", "example.com/v1/chat", CleartextHostIssueKind.PATH_OR_PARAMETERS),
            Case("backslash path", "example.com\\v1", CleartextHostIssueKind.PATH_OR_PARAMETERS),
            Case("query", "example.com?token=x", CleartextHostIssueKind.PATH_OR_PARAMETERS),
            Case("fragment", "example.com#api", CleartextHostIssueKind.PATH_OR_PARAMETERS),
            Case("hostname port", "example.com:8080", CleartextHostIssueKind.PORT),
            Case("IPv4 port", "192.168.0.2:8080", CleartextHostIssueKind.PORT),
            Case("IPv6 port", "[2001:db8::1]:8080", CleartextHostIssueKind.PORT),
            Case("space", "example .com", CleartextHostIssueKind.INVALID_HOST),
            Case("userinfo", "user@example.com", CleartextHostIssueKind.INVALID_HOST),
            Case("malformed IPv4", "999.168.0.1", CleartextHostIssueKind.INVALID_HOST),
            Case("wildcard", "*.example.com", CleartextHostIssueKind.INVALID_HOST),
        ).forEach { case ->
            val result = CleartextHostPolicy.validateMultiline(case.input)

            assertEquals(case.name, case.expectedKind, result.firstIssue?.kind)
            assertTrue(case.name, result.hosts.isEmpty())
        }
    }

    @Test
    fun validateMultiline_reportsTheOriginalLineAndKeepsOtherValidHosts() {
        val result = CleartextHostPolicy.validateMultiline(
            "example.com\n\nhttps://invalid.example/v1\n192.168.0.2"
        )

        assertEquals(listOf("example.com", "192.168.0.2"), result.hosts)
        assertEquals(3, result.firstIssue?.lineNumber)
        assertEquals(CleartextHostIssueKind.SCHEME, result.firstIssue?.kind)
    }

    @Test
    fun normalize_dropsLegacyInvalidEntriesBeforePersistence() {
        val normalized = CleartextHostPolicy.normalize(
            listOf(
                " Example.COM ",
                "http://invalid.example",
                "example.com/path",
                "example.com:8080",
                "192.168.0.2",
            )
        )

        assertEquals(listOf("Example.COM", "192.168.0.2"), normalized)
    }
}
