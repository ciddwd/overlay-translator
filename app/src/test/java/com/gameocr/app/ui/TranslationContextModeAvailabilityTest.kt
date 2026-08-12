package com.gameocr.app.ui

import com.gameocr.app.data.TranslationContextMode
import com.gameocr.app.data.TranslatorEngine
import com.gameocr.app.glossary.supportsTranslationPromptContext
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationContextModeAvailabilityTest {
    @Test
    fun modeSelection_isTableDrivenForEveryTranslatorEngine() {
        data class Case(
            val engine: TranslatorEngine,
            val supportsContext: Boolean,
        )

        val cases = listOf(
            Case(TranslatorEngine.OPENAI, true),
            Case(TranslatorEngine.ANTHROPIC, true),
            Case(TranslatorEngine.LOCAL_SAKURA, true),
            Case(TranslatorEngine.LOCAL_HY_MT2, true),
            Case(TranslatorEngine.GOOGLE_ML_KIT, false),
            Case(TranslatorEngine.GOOGLE, false),
            Case(TranslatorEngine.DEEPL, false),
            Case(TranslatorEngine.YOUDAO_PICTRANS, false),
            Case(TranslatorEngine.VOLC, false),
            Case(TranslatorEngine.BAIDU_FANYI, false),
            Case(TranslatorEngine.TENCENT, false),
        )

        assertEquals(TranslatorEngine.entries.toSet(), cases.map { it.engine }.toSet())
        cases.forEach { case ->
            assertEquals(case.engine.name, case.supportsContext, supportsTranslationPromptContext(case.engine))
            TranslationContextMode.entries.forEach { mode ->
                val expected = case.supportsContext || mode == TranslationContextMode.FAST_PER_SEGMENT
                assertEquals(
                    "${case.engine}/$mode",
                    expected,
                    canSelectTranslationContextMode(case.supportsContext, mode),
                )
            }
        }
    }

    @Test
    fun unsupportedModeClick_showsTheSameUserFacingMessageWithoutChangingSelection() {
        val source = sourceFile(
            "src/main/java/com/gameocr/app/ui/SettingsScreen.kt"
        ).readText()
        val selector = source
            .substringAfter("private fun TranslationContextModeSelector(")
            .substringBefore("private fun translationContextModeLabelRes(")

        listOf(
            "if (!canSelectTranslationContextMode(supportsContext, mode))",
            "Toast.makeText(context, unsupportedMessage, Toast.LENGTH_SHORT).show()",
            "else if (value != mode)",
            "onValueChange(mode)",
        ).forEach { marker -> assertTrue("missing $marker", selector.contains(marker)) }

        val chinese = stringResources(
            sourceFile("src/main/res/values-zh-rCN/strings.xml")
        )
        assertEquals(
            "%1\$s翻译服务只能逐段翻译，因此只支持“快速”模式。",
            chinese["settings_translation_mode_unsupported"],
        )
        listOf("deepl", "youdao", "google", "mlkit", "volc", "baidu", "tencent")
            .forEach { service ->
                assertTrue(
                    service,
                    chinese["settings_translation_service_$service"].orEmpty().isNotBlank(),
                )
            }
    }

    private fun stringResources(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
                put(name, node.textContent)
            }
        }
    }

    private fun sourceFile(path: String): File = listOf(
        File(path),
        File("app/$path"),
    ).first(File::isFile)
}
