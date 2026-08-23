package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WordResultPromptTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun groupedSenseParser_tableDriven_preservesMeaningRelationships() {
        data class Case(
            val name: String,
            val raw: String,
            val expectedLemma: String,
            val expectedSenses: List<WordSense>,
            val expectedDefinitions: List<String>,
        )

        listOf(
            Case(
                "displayed has adjective and verb senses",
                """{
                    "lemma":"display",
                    "senses":[
                      {"pos":"adj.","definitions":["显示的"],"form_note":""},
                      {"pos":"v.","definitions":["表现","展示","陈列"],"form_note":"display 的过去式和过去分词"}
                    ]
                }""".trimIndent(),
                "display",
                listOf(
                    WordSense("adj.", listOf("显示的")),
                    WordSense("v.", listOf("表现", "展示", "陈列"), "display 的过去式和过去分词"),
                ),
                listOf("显示的", "表现", "展示", "陈列"),
            ),
            Case(
                "blank and duplicate meanings are normalized",
                """{"lemma":"update","senses":[{"part_of_speech":"v.","meanings":["更新"," 更新 ",""]}]}""",
                "update",
                listOf(WordSense("v.", listOf("更新"))),
                listOf("更新"),
            ),
            Case(
                "malformed senses retain compatible legacy fields",
                """{"pos":["n.","v."],"definitions":["记录","记下"],"senses":[{"pos":"adj.","definitions":[]}]}""",
                "",
                emptyList(),
                listOf("记录", "记下"),
            ),
        ).forEach { case ->
            val result = parseWordResult(case.raw, json)
            assertEquals("${case.name} lemma", case.expectedLemma, result?.lemma)
            assertEquals("${case.name} senses", case.expectedSenses, result?.senses)
            assertEquals("${case.name} definitions", case.expectedDefinitions, result?.effectiveDefinitions())
        }
    }

    @Test
    fun groupedSenseParser_tableDriven_mergesRepeatedPartOfSpeechLocally() {
        data class Case(
            val name: String,
            val raw: String,
            val expected: List<WordSense>,
        )

        listOf(
            Case(
                "three noun objects become one noun group",
                """{
                    "senses":[
                      {"pos":"n.","definitions":["姐妹"]},
                      {"pos":"n.","definitions":["修女"]},
                      {"pos":"n.","definitions":["护士长"]}
                    ]
                }""".trimIndent(),
                listOf(WordSense("n.", listOf("姐妹", "修女", "护士长"))),
            ),
            Case(
                "case whitespace meanings and notes are normalized",
                """{
                    "senses":[
                      {"pos":" N. ","definitions":["姐妹","姐妹"],"form_note":"单数"},
                      {"pos":"n.","definitions":["姊妹舰"],"form_note":"集合用法"}
                    ]
                }""".trimIndent(),
                listOf(WordSense("N.", listOf("姐妹", "姊妹舰"), "单数；集合用法")),
            ),
            Case(
                "different parts of speech remain separate",
                """{
                    "senses":[
                      {"pos":"n.","definitions":["显示"]},
                      {"pos":"v.","definitions":["展示"]},
                      {"pos":"n.","definitions":["陈列品"]}
                    ]
                }""".trimIndent(),
                listOf(
                    WordSense("n.", listOf("显示", "陈列品")),
                    WordSense("v.", listOf("展示")),
                ),
            ),
            Case(
                "invalid empty sense is discarded",
                """{
                    "senses":[
                      {"pos":"n.","definitions":[]},
                      {"pos":"n.","definitions":["有效释义"]}
                    ]
                }""".trimIndent(),
                listOf(WordSense("n.", listOf("有效释义"))),
            ),
        ).forEach { case ->
            assertEquals(case.name, case.expected, parseWordResult(case.raw, json)?.senses)
        }
    }

    @Test
    fun groupedSenseContracts_tableDriven_requireCompactAndFullRelationships() {
        val full = "Return dictionary JSON."
            .withGroupedSensesContract("English", "Chinese")
        assertTrue(full.contains("\"senses\""))
        assertTrue(full.contains("\"lemma\""))
        assertSame(full, full.withGroupedSensesContract("English", "Chinese"))

        val compact = compactDictionaryPrompt("English", "Chinese")
        listOf("\"lemma\"", "\"senses\"", "\"pos\"", "\"definitions\"", "\"form_note\"")
            .forEach { field -> assertTrue(field, compact.contains(field)) }
        listOf("phonetic", "examples", "synonyms", "difficulty_notes")
            .forEach { omitted -> assertFalse(omitted, compact.contains("\"$omitted\"")) }
    }

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
    fun lexicalDetailsContract_tableDriven_addsOnlyMissingFields() {
        data class Case(
            val name: String,
            val prompt: String,
            val expectedInflectionCount: Int,
            val expectedSynonymCount: Int,
            val shouldReuseInstance: Boolean,
        )

        listOf(
            Case("legacy prompt", "Return dictionary JSON.", 1, 1, false),
            Case("inflections already present", """Return {"inflections":[]}""", 1, 1, false),
            Case("synonyms already present", """Return {"synonyms":[]}""", 1, 1, false),
            Case("current default prompt", Settings.DEFAULT_DICTIONARY_PROMPT, 1, 1, true),
        ).forEach { case ->
            val resolved = case.prompt.withLexicalDetailsContract("English")
            assertEquals(
                "${case.name} inflections",
                case.expectedInflectionCount,
                Regex("\"inflections\"").findAll(resolved).count(),
            )
            assertEquals(
                "${case.name} synonyms",
                case.expectedSynonymCount,
                Regex("\"synonyms\"").findAll(resolved).count(),
            )
            if (case.shouldReuseInstance) assertSame(case.name, case.prompt, resolved)
        }
    }

    @Test
    fun parsesInflectionsAndSynonyms_tableDriven_acceptsCompatibleKeys() {
        data class Case(
            val name: String,
            val raw: String,
            val expectedInflections: List<String>,
            val expectedSynonyms: List<String>,
        )

        listOf(
            Case(
                name = "canonical fields",
                raw = """{
                    "definitions":["去"],
                    "inflections":["past: went","past participle: gone"],
                    "synonyms":["move","travel"]
                }""".trimIndent(),
                expectedInflections = listOf("past: went", "past participle: gone"),
                expectedSynonyms = listOf("move", "travel"),
            ),
            Case(
                name = "camel case aliases",
                raw = """{
                    "meaning":"运行",
                    "wordForms":["past: ran"],
                    "similarWords":["operate","function"]
                }""".trimIndent(),
                expectedInflections = listOf("past: ran"),
                expectedSynonyms = listOf("operate", "function"),
            ),
            Case(
                name = "singular aliases",
                raw = """{
                    "definition":"快速的",
                    "inflection":"comparative: faster",
                    "synonym":"quick"
                }""".trimIndent(),
                expectedInflections = listOf("comparative: faster"),
                expectedSynonyms = listOf("quick"),
            ),
            Case(
                name = "legacy response remains compatible",
                raw = """{"definitions":["测试"],"examples":[]}""",
                expectedInflections = emptyList(),
                expectedSynonyms = emptyList(),
            ),
        ).forEach { case ->
            val result = parseWordResult(case.raw, json)
            assertEquals("${case.name} inflections", case.expectedInflections, result?.inflections)
            assertEquals("${case.name} synonyms", case.expectedSynonyms, result?.synonyms)
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
    fun dictionaryJsonOutput_tableDriven_isControlledByNegotiatedCapability() {
        data class Case(val name: String, val enabled: Boolean, val expected: Boolean)

        val cases = listOf(
            Case("unknown or supported capability sends field", true, true),
            Case("cached unsupported capability omits field", false, false),
        )

        cases.forEach { case ->
            val format = jsonObjectResponseFormatOrNull(case.enabled)
            assertEquals(case.name, case.expected, format?.type == "json_object")
        }

        val encoded = json.encodeToString(
            ChatRequest(
                model = "deepseek-chat",
                messages = listOf(
                    OpenAiRequestMessage(
                        "user",
                        kotlinx.serialization.json.JsonPrimitive("json"),
                    )
                ),
                responseFormat = jsonObjectResponseFormatOrNull(enabled = true),
            )
        )
        assertTrue(encoded.contains("\"response_format\":{\"type\":\"json_object\"}"))
    }
}
