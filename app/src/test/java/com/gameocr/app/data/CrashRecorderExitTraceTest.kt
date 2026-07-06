package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashRecorderExitTraceTest {

    @Test
    fun formatExitTraceForLog_handlesTextBinaryEmptyAndLongTraces() {
        data class Case(
            val name: String,
            val bytes: ByteArray,
            val assertResult: (String?) -> Unit,
        )

        val longText = "a".repeat(17 * 1024)
        val cases = listOf(
            Case(
                name = "plain ANR-style text trace",
                bytes = "main tid=1\n  at com.gameocr.app.MainActivity.onCreate\n".toByteArray(),
                assertResult = { actual ->
                    assertEquals(
                        "main tid=1\n  at com.gameocr.app.MainActivity.onCreate",
                        actual
                    )
                },
            ),
            Case(
                name = "native tombstone protobuf-like binary trace",
                bytes = byteArrayOf(
                    0x08, 0x01, 0x12, 0x51, 0x58, 0x69, 0x61, 0x6f,
                    0x6d, 0x69, 0x2f, 0x73, 0x68, 0x65, 0x6e, 0x6e,
                    0x6f, 0x6e, 0x67, 0x1a, 0x01, 0x30
                ),
                assertResult = { actual ->
                    requireNotNull(actual)
                    assertTrue(actual.contains("<binary trace omitted: 22 bytes"))
                    assertTrue(actual.contains("sha256="))
                    assertTrue(actual.contains("head=08 01 12 51"))
                    assertFalse(actual.contains("Xiaomi/shennong"))
                },
            ),
            Case(
                name = "empty trace",
                bytes = byteArrayOf(),
                assertResult = { actual ->
                    assertNull(actual)
                },
            ),
            Case(
                name = "long text trace is truncated",
                bytes = longText.toByteArray(),
                assertResult = { actual ->
                    requireNotNull(actual)
                    assertTrue(actual.length < longText.length)
                    assertTrue(actual.contains("<truncated 1024 chars>"))
                },
            ),
        )

        cases.forEach { case ->
            case.assertResult(CrashRecorder.formatExitTraceForLog(case.bytes))
        }
    }
}
