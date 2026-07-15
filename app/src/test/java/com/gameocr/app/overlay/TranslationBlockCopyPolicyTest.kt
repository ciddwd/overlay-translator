package com.gameocr.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationBlockCopyPolicyTest {
    @Test
    fun textRoles_useEqualSizeWhileOnlyTranslationUsesDisplayStyle() {
        data class Case(
            val role: TranslationBlockCopyTextRole,
            val expectedSizeSp: Float,
            val expectedDisplayStyle: Boolean,
        )

        listOf(
            Case(TranslationBlockCopyTextRole.SOURCE, 16f, false),
            Case(TranslationBlockCopyTextRole.TRANSLATION, 16f, true),
        ).forEach { case ->
            val spec = translationBlockCopyTextSpec(case.role)
            assertEquals(case.role.name, case.expectedSizeSp, spec.textSizeSp, 0f)
            assertEquals(case.role.name, case.expectedDisplayStyle, spec.applyTranslationDisplayStyle)
        }
    }
}
