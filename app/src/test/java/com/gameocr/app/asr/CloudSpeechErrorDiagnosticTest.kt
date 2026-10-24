package com.gameocr.app.asr

import org.junit.Assert.*
import org.junit.Test

class CloudSpeechErrorDiagnosticTest {
    @Test fun preservesReasonAndRedactsCredentials_tableDriven() {
        data class Case(val message: String, val key: String, val absent: String)
        listOf(
            Case("Invalid target language", "", "never-present"),
            Case("Invalid key custom-secret", "custom-secret", "custom-secret"),
            Case("Rejected sk-example123", "", "sk-example123"),
            Case("Rejected Bearer arbitrary-token", "", "arbitrary-token"),
        ).forEach { case ->
            val detail = CloudSpeechErrorDiagnostic.describe("COMMON_ERROR", case.message, case.key)
            assertTrue(detail.contains("COMMON_ERROR"))
            assertFalse(detail.contains(case.absent))
            if (case.key.isEmpty() && case.message == "Invalid target language") {
                assertTrue(detail.contains(case.message))
            }
        }
    }

    @Test fun emptyAndOversizedMessagesAreBounded() {
        assertTrue(CloudSpeechErrorDiagnostic.describe("", "", "").isNotEmpty())
        assertEquals(1024, CloudSpeechErrorDiagnostic.describe("ERROR", "x".repeat(5000), "").length)
        assertFalse(CloudSpeechErrorDiagnostic.describe("ERROR", "line1\nline2", "").contains('\n'))
    }
}
