package com.gameocr.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class InputTranslationAppPolicyTest {
    @Test
    fun packageBlacklist_isExactAndTableDriven() {
        data class Case(val packageName: String?, val blocked: Boolean)

        listOf(
            Case("com.tencent.mm", true),
            Case("com.tencent.wework", false),
            Case("com.tencent.mm.plugin", false),
            Case("", false),
            Case(null, false),
        ).forEach { case ->
            assertEquals(
                case.packageName ?: "null",
                case.blocked,
                InputTranslationAppPolicy.isBlocked(case.packageName),
            )
        }
    }
}
