package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleYamlDictionaryTest {

    @Test
    fun parsePaddleYamlCharacterDict_handlesOfficialAndBoundaryCases() {
        data class Case(
            val name: String,
            val yaml: String,
            val expected: List<String>,
        )

        val cases = listOf(
            Case(
                name = "official indented dictionary",
                yaml = """
                    Global:
                      model_name: PP-OCRv5_mobile_rec
                      character_dict:
                      - 　
                      - 一
                      - 乙
                      use_space_char: true
                """.trimIndent(),
                expected = listOf("　", "一", "乙"),
            ),
            Case(
                name = "quoted and escaped entries",
                yaml = """
                    character_dict:
                    - 'A'
                    - "B"
                    - '\n'
                    next_field: value
                """.trimIndent(),
                expected = listOf("A", "B", "\n"),
            ),
            Case(
                name = "missing dictionary",
                yaml = "Global:\n  model_name: PP-OCRv5_mobile_rec",
                expected = emptyList(),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                parsePaddleYamlCharacterDict(case.yaml.lines()),
            )
        }
    }
}
