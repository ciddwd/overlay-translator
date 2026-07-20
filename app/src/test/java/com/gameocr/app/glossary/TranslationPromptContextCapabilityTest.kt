package com.gameocr.app.glossary

import com.gameocr.app.data.TranslatorEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationPromptContextCapabilityTest {
    @Test
    fun promptContextSupport_coversEveryTranslatorEngine() {
        data class Case(val engine: TranslatorEngine, val expected: Boolean)

        val cases = listOf(
            Case(TranslatorEngine.OPENAI, true),
            Case(TranslatorEngine.LOCAL_SAKURA, true),
            Case(TranslatorEngine.LOCAL_HY_MT2, true),
            Case(TranslatorEngine.DEEPL, false),
            Case(TranslatorEngine.YOUDAO_PICTRANS, false),
            Case(TranslatorEngine.GOOGLE, false),
            Case(TranslatorEngine.GOOGLE_ML_KIT, false),
            Case(TranslatorEngine.VOLC, false),
            Case(TranslatorEngine.BAIDU_FANYI, false),
            Case(TranslatorEngine.TENCENT, false),
        )

        assertEquals(TranslatorEngine.entries.toSet(), cases.map(Case::engine).toSet())
        cases.forEach { case ->
            assertEquals(case.engine.name, case.expected, supportsTranslationPromptContext(case.engine))
        }
    }
}
