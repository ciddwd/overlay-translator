package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WordResultPromptTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun difficultyContractSupportsLegacyAndCurrentPrompts() {
        data class Case(
            val name: String,
            val prompt: String,
            val shouldReuseInstance: Boolean
        )

        val cases = listOf(
            Case("legacy custom prompt", "Return dictionary JSON.", false),
            Case("current default prompt", Settings.DEFAULT_DICTIONARY_PROMPT, true)
        )

        cases.forEach { case ->
            val resolved = case.prompt.withDifficultyNotesContract("Simplified Chinese")
            assertTrue(case.name, resolved.contains("\"difficulty_notes\""))
            assertEquals(case.name, 1, Regex("\"difficulty_notes\"").findAll(resolved).count())
            if (case.shouldReuseInstance) assertSame(case.name, case.prompt, resolved)
        }
    }

    @Test
    fun parsesDifficultyNotesAndKeepsLegacyJsonCompatible() {
        data class Case(
            val name: String,
            val raw: String,
            val expectedNotes: List<String>
        )

        val cases = listOf(
            Case(
                "new schema",
                """```json
                    {"phonetic":"/kjuː/","pos":["n."],"definitions":["队列"],"difficulty_notes":["计算机领域中指先进先出的数据结构"],"examples":[]}
                    ```""".trimIndent(),
                listOf("计算机领域中指先进先出的数据结构")
            ),
            Case(
                "legacy schema",
                """{"phonetic":"","pos":[],"definitions":["队列"],"examples":[]}""",
                emptyList()
            )
        )

        cases.forEach { case ->
            val result = parseWordResult(case.raw, json)
            assertEquals(case.name, case.expectedNotes, result?.difficultyNotes)
            assertEquals(case.name, listOf("队列"), result?.definitions)
        }
    }

    @Test
    fun rejectsBlankOrStructurallyEmptyResults() {
        listOf(
            "",
            "not json",
            """{"phonetic":"","pos":[],"definitions":[],"difficulty_notes":[],"examples":[]}"""
        ).forEach { raw ->
            assertNull(raw, parseWordResult(raw, json))
        }
    }

    @Test
    fun parserVariants_tableDriven_acceptsCommonCompatibleShapes() {
        data class Case(
            val name: String,
            val raw: String,
            val phonetic: String,
            val pos: List<String>,
            val definitions: List<String>,
            val notes: List<String>,
            val examples: List<ExamplePair>,
        )

        val cases = listOf(
            Case(
                name = "camel case and scalar values",
                raw = """{
                    "pronunciation":"/test/",
                    "partOfSpeech":"noun",
                    "meaning":"测试",
                    "usageNotes":"也可用作动词",
                    "example":{"source":"This is a test.","target":"这是一个测试。"}
                }""".trimIndent(),
                phonetic = "/test/",
                pos = listOf("noun"),
                definitions = listOf("测试"),
                notes = listOf("也可用作动词"),
                examples = listOf(ExamplePair("This is a test.", "这是一个测试。")),
            ),
            Case(
                name = "nested data and object arrays",
                raw = """{
                    "data": {
                        "ipa":"/kjuː/",
                        "part_of_speech":[{"name":"noun"}],
                        "definitions":[{"meaning":"队列"}],
                        "difficulty_notes":[{"note":"计算机术语"}],
                        "examples":["Messages wait in a queue."]
                    }
                }""".trimIndent(),
                phonetic = "/kjuː/",
                pos = listOf("noun"),
                definitions = listOf("队列"),
                notes = listOf("计算机术语"),
                examples = listOf(ExamplePair("Messages wait in a queue.", "")),
            ),
            Case(
                name = "standard snake case arrays",
                raw = """{
                    "phonetic":"かな",
                    "pos":["动词"],
                    "definitions":["承担"],
                    "difficulty_notes":[],
                    "examples":[{"src":"仕事を引き受ける。","dst":"承担工作。"}]
                }""".trimIndent(),
                phonetic = "かな",
                pos = listOf("动词"),
                definitions = listOf("承担"),
                notes = emptyList(),
                examples = listOf(ExamplePair("仕事を引き受ける。", "承担工作。")),
            ),
        )

        cases.forEach { case ->
            val result = parseWordResult(case.raw, json)
            assertEquals(case.name, case.phonetic, result?.phonetic)
            assertEquals(case.name, case.pos, result?.pos)
            assertEquals(case.name, case.definitions, result?.definitions)
            assertEquals(case.name, case.notes, result?.difficultyNotes)
            assertEquals(case.name, case.examples, result?.examples)
        }
    }

    @Test
    fun dictionaryJsonOutput_tableDriven_onlyTargetsVerifiedDeepSeekHost() {
        data class Case(val name: String, val baseUrl: String, val expected: Boolean)

        val cases = listOf(
            Case("DeepSeek v1", "https://api.deepseek.com/v1/", true),
            Case("DeepSeek root", "https://api.deepseek.com", true),
            Case("case insensitive host", "https://API.DEEPSEEK.COM/v1", true),
            Case("spoofed suffix", "https://api.deepseek.com.example.org/v1", false),
            Case("SiliconFlow compatible", "https://api.siliconflow.cn/v1", false),
            Case("local compatible server", "http://192.168.0.10:8000/v1", false),
            Case("invalid URL", "not a URL", false),
        )

        cases.forEach { case ->
            val format = dictionaryJsonResponseFormatOrNull(case.baseUrl)
            assertEquals(case.name, case.expected, format?.type == "json_object")
        }

        val encoded = json.encodeToString(
            ChatRequest(
                model = "deepseek-chat",
                messages = listOf(ChatMessage("user", "json")),
                responseFormat = dictionaryJsonResponseFormatOrNull("https://api.deepseek.com/v1/"),
            )
        )
        assertTrue(encoded.contains("\"response_format\":{\"type\":\"json_object\"}"))
    }
}
