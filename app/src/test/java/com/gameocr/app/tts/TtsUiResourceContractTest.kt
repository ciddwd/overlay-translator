package com.gameocr.app.tts

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsUiResourceContractTest {

    @Test
    fun defaultTestText_followsLocalizedStringResources() {
        data class Case(
            val name: String,
            val resourcePath: String,
            val expected: String,
        )

        val cases = listOf(
            Case(
                name = "default English resources",
                resourcePath = "src/main/res/values/strings.xml",
                expected = "Hello, this is a text-to-speech test.",
            ),
            Case(
                name = "Simplified Chinese resources",
                resourcePath = "src/main/res/values-zh-rCN/strings.xml",
                expected = "你好，这是一段文本转语音测试。",
            ),
        )

        cases.forEach { case ->
            val strings = stringResources(sourceFile(case.resourcePath))
            assertEquals(case.name, case.expected, strings["settings_tts_test_text_default"])
            val summary = strings.getValue("preset_quick_summary_format")
            assertTrue("${case.name} preset summary shows TTS", summary.contains("TTS"))
            assertTrue("${case.name} preset summary accepts a TTS label", summary.contains("%5\$s"))
        }
    }

    @Test
    fun settingsScreen_usesLocalizedDefaultWithoutOverwritingEditsOnRecomposition() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val expectedFragments = listOf(
            "stringResource(R.string.settings_tts_test_text_default)",
            "remember(defaultTtsTestText) { mutableStateOf(defaultTtsTestText) }",
        )

        expectedFragments.forEach { fragment ->
            assertTrue("SettingsScreen missing localized TTS default: $fragment", source.contains(fragment))
        }
    }

    @Test
    fun mimoTokenPlanSelector_listsAllClustersAndShowsUsageRestriction() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        data class Case(val name: String, val marker: String)

        listOf(
            Case("China cluster", "MIMO_TOKEN_PLAN_CN_BASE_URL"),
            Case("Singapore cluster", "MIMO_TOKEN_PLAN_SGP_BASE_URL"),
            Case("Europe cluster", "MIMO_TOKEN_PLAN_EU_BASE_URL"),
            Case("restriction condition", "isMimoTokenPlanBaseUrl(mimoBaseUrl)"),
            Case("localized restriction", "R.string.settings_tts_mimo_token_plan_restriction"),
        ).forEach { case ->
            assertTrue(case.name, source.contains(case.marker))
        }

        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
        ).forEach { path ->
            val strings = stringResources(sourceFile(path))
            listOf(
                "settings_tts_endpoint_mimo_token_plan_cn",
                "settings_tts_endpoint_mimo_token_plan_sgp",
                "settings_tts_endpoint_mimo_token_plan_eu",
                "settings_tts_mimo_token_plan_restriction",
            ).forEach { key ->
                assertTrue("$path missing $key", strings[key].orEmpty().isNotBlank())
            }
        }
    }

    @Test
    fun cloudProviderPlanSelectors_hidePresetAndResolvedUrlsButKeepCustomEditing() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        data class Case(
            val name: String,
            val valueMarker: String,
            val resolverMarker: String,
        )

        listOf(
            Case("Volcengine", "value = volcengineBaseUrl", "::volcengineTtsEndpointUrlOrNull"),
            Case("MiniMax", "value = miniMaxBaseUrl", "::miniMaxTtsEndpointUrlOrNull"),
            Case("MiMo", "value = mimoBaseUrl", "::mimoTtsEndpointUrlOrNull"),
        ).forEach { case ->
            val start = source.indexOf(case.valueMarker)
            val resolver = source.indexOf(case.resolverMarker, startIndex = start)
            assertTrue("${case.name} selector is missing", start >= 0 && resolver > start)
            val blockEnd = (resolver + case.resolverMarker.length + 180).coerceAtMost(source.length)
            val selectorBlock = source.substring(start, blockEnd)
            assertTrue(
                "${case.name} should hide the selected preset URL",
                selectorBlock.contains("showSelectedUrl = false"),
            )
            assertTrue(
                "${case.name} should hide the resolved request URL",
                selectorBlock.contains("showResolvedEndpoint = false"),
            )
        }

        val selectorImplementation = source.substringAfter("private fun TtsApiBaseUrlSelector(")
        assertTrue(
            "custom URL input must remain editable",
            selectorImplementation.contains("if (customSelected)") &&
                selectorImplementation.contains("onValueChange = onValueChange"),
        )
    }

    @Test
    fun mimoVoiceDesignAndBundledReferences_areWiredAndLocalized() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        data class MarkerCase(val name: String, val marker: String)

        listOf(
            MarkerCase("AI action uses voice design prompt", "onGenerateMimoVoiceDesign(mimoVoiceDesignPrompt)"),
            MarkerCase("preset style has independent value", "value = mimoInstruction"),
            MarkerCase("voice design has independent value", "value = mimoVoiceDesignPrompt"),
            MarkerCase("voice clone style has independent value", "value = mimoVoiceCloneInstruction"),
            MarkerCase("AI action requires a non-blank prompt", "canGenerateMimoVoiceDesignPrompt("),
            MarkerCase("style AI action requires a non-blank intent", "canPolishMimoStyleInstruction("),
            MarkerCase("style fields share an editor but not state", "MimoStyleInstructionEditor("),
            MarkerCase("style help uses a dialog", "SettingHelpDialogButton("),
            MarkerCase("style help scrolls", ".verticalScroll(rememberScrollState())"),
            MarkerCase("preset AI result stays in preset field", "MimoTtsModel.PRESET -> ttsMimoInstruction = polished"),
            MarkerCase("clone AI result stays in clone field", "ttsMimoVoiceCloneInstruction = polished"),
            MarkerCase("AI action and help use opposite sides", "horizontalArrangement = Arrangement.SpaceBetween"),
            MarkerCase("AI help uses an icon tooltip", "SettingHelpTooltip("),
            MarkerCase("MiMo plan hides its selected URL", "showSelectedUrl = false"),
            MarkerCase("MiMo plan hides its resolved endpoint", "showResolvedEndpoint = false"),
            MarkerCase("AI progress", "mimoVoiceDesignGenerating"),
            MarkerCase("first reference", "MIMO_BUILTIN_VOICE_REFERENCE_1"),
            MarkerCase("second reference", "MIMO_BUILTIN_VOICE_REFERENCE_2"),
            MarkerCase("custom reference", "MIMO_CUSTOM_VOICE_REFERENCE"),
        ).forEach { case -> assertTrue(case.name, source.contains(case.marker)) }
        assertEquals(
            "preset and clone should each use the style editor",
            3,
            Regex("MimoStyleInstructionEditor\\(").findAll(source).count(),
        )

        val voiceDesignBlock = source
            .substringAfter("MimoTtsModel.VOICE_DESIGN -> {")
            .substringBefore("MimoTtsModel.VOICE_CLONE -> {")
        assertTrue(
            "AI action must remain left of the help icon",
            voiceDesignBlock.indexOf("OutlinedButton(") < voiceDesignBlock.indexOf("SettingHelpTooltip("),
        )
        assertTrue(
            "AI explanation must not remain as bottom text",
            !voiceDesignBlock.contains("Text(stringResource(R.string.settings_tts_mimo_ai_generate_hint)"),
        )

        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
        ).forEach { path ->
            val strings = stringResources(sourceFile(path))
            listOf(
                "settings_tts_mimo_ai_generate",
                "settings_tts_mimo_ai_generate_hint",
                "settings_tts_mimo_ai_polish",
                "settings_tts_mimo_ai_polishing",
                "settings_tts_mimo_style_instruction_required",
                "settings_tts_mimo_style_polish_error",
                "settings_tts_mimo_style_help_title",
                "settings_tts_mimo_style_help",
                "settings_tts_mimo_sample_source",
                "settings_tts_mimo_builtin_sample_1",
                "settings_tts_mimo_builtin_sample_2",
                "settings_tts_mimo_custom_sample",
            ).forEach { key -> assertTrue("$path missing $key", strings[key].orEmpty().isNotBlank()) }
            val styleHelp = strings.getValue("settings_tts_mimo_style_help")
            assertTrue("$path help must explain natural-language placement", styleHelp.contains("role=user"))
            assertTrue("$path help must explain audio-label placement", styleHelp.contains("role=assistant"))
        }

        data class ReferenceLabelCase(
            val path: String,
            val expectedFirst: String,
            val expectedSecond: String,
        )
        listOf(
            ReferenceLabelCase(
                path = "src/main/res/values/strings.xml",
                expectedFirst = "Built-in male voice",
                expectedSecond = "Built-in female voice",
            ),
            ReferenceLabelCase(
                path = "src/main/res/values-zh-rCN/strings.xml",
                expectedFirst = "内置男声",
                expectedSecond = "内置女声",
            ),
        ).forEach { case ->
            val strings = stringResources(sourceFile(case.path))
            assertEquals(
                "${case.path} first bundled reference",
                case.expectedFirst,
                strings["settings_tts_mimo_builtin_sample_1"],
            )
            assertEquals(
                "${case.path} second bundled reference",
                case.expectedSecond,
                strings["settings_tts_mimo_builtin_sample_2"],
            )
        }

        data class AudioCase(
            val path: String,
            val expectedLength: Long,
            val minimumRmsDbfs: Double,
            val maximumRmsDbfs: Double,
            val minimumPeakDbfs: Double,
            val maximumPeakDbfs: Double,
        )
        val audioCases = listOf(
            AudioCase(
                "src/main/res/raw/mimo_voice_reference_1.wav",
                161_870L,
                minimumRmsDbfs = -16.0,
                maximumRmsDbfs = -15.0,
                minimumPeakDbfs = -2.0,
                maximumPeakDbfs = -1.4,
            ),
            AudioCase(
                "src/main/res/raw/mimo_voice_reference_2.wav",
                152_108L,
                minimumRmsDbfs = -16.0,
                maximumRmsDbfs = -15.0,
                minimumPeakDbfs = -2.0,
                maximumPeakDbfs = -1.4,
            ),
        )
        val audioStats = audioCases.associate { case ->
            val bytes = sourceFile(case.path).readBytes()
            assertEquals(case.path, case.expectedLength, bytes.size.toLong())
            assertEquals(case.path, "RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(case.path, "WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            val stats = pcm16MonoWavStats(bytes)
            assertEquals("${case.path} PCM format", 1, stats.audioFormat)
            assertEquals("${case.path} sample rate", 22_050, stats.sampleRate)
            assertEquals("${case.path} channels", 1, stats.channels)
            assertEquals("${case.path} bit depth", 16, stats.bitsPerSample)
            assertTrue(
                "${case.path} RMS ${stats.rmsDbfs}",
                stats.rmsDbfs in case.minimumRmsDbfs..case.maximumRmsDbfs,
            )
            assertTrue(
                "${case.path} peak ${stats.peakDbfs}",
                stats.peakDbfs in case.minimumPeakDbfs..case.maximumPeakDbfs,
            )
            case.path to stats
        }
        val male = audioStats.getValue("src/main/res/raw/mimo_voice_reference_1.wav")
        val female = audioStats.getValue("src/main/res/raw/mimo_voice_reference_2.wav")
        assertTrue("bundled voice RMS mismatch", abs(male.rmsDbfs - female.rmsDbfs) <= 0.75)
        assertTrue("bundled voice peak mismatch", abs(male.peakDbfs - female.peakDbfs) <= 0.25)
    }

    @Test
    fun volcengineOfficialHttpUi_isWiredAndAliyunGatewayIsRemoved() {
        val source = sourceFile("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        data class MarkerCase(val name: String, val marker: String)

        listOf(
            MarkerCase("official provider", "TtsProvider.VOLCENGINE"),
            MarkerCase("resource selector", "VolcengineTtsResource.entries"),
            MarkerCase("official endpoint", "DEFAULT_VOLCENGINE_TTS_BASE_URL"),
            MarkerCase("custom endpoint resolver", "::volcengineTtsEndpointUrlOrNull"),
            MarkerCase("clone fidelity", "volcengineToneFidelity"),
        ).forEach { case -> assertTrue(case.name, source.contains(case.marker)) }

        listOf("ALIYUN_GATEWAY", "settings_tts_provider_aliyun").forEach { forbidden ->
            assertTrue("removed provider leaked into UI: $forbidden", !source.contains(forbidden))
        }
        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-zh-rCN/strings.xml",
        ).forEach { path ->
            val strings = stringResources(sourceFile(path))
            listOf(
                "settings_tts_volcengine_hint",
                "settings_tts_volcengine_resource",
                "settings_tts_volcengine_preset_resource",
                "settings_tts_volcengine_clone_resource",
                "settings_tts_volcengine_tone_fidelity_hint",
            ).forEach { key -> assertTrue("$path missing $key", strings[key].orEmpty().isNotBlank()) }
            assertTrue("$path still exposes Aliyun gateway", !strings.containsKey("settings_tts_provider_aliyun"))
        }
    }

    @Test
    fun manualSpeechRouting_doesNotSuppressRepeatedButtonClicks() {
        val source = sourceFile("src/main/java/com/gameocr/app/tts/RoutingTtsEngine.kt").readText()
        data class Case(val name: String, val forbidden: String)

        listOf(
            Case("no duplicate time window", "DUPLICATE_SUPPRESS_WINDOW_MS"),
            Case("no text duplicate gate", "TtsSpeechRequestGate"),
            Case("no successful-text cache", "lastText"),
        ).forEach { case ->
            assertTrue(case.name, !source.contains(case.forbidden))
        }
        assertTrue("system click reaches speak", source.contains("systemTtsEngine.speak(normalized"))
        assertTrue("HTTP click reaches speak", source.contains("httpTtsEngine.speak(normalized"))
    }

    private fun stringResources(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return document.getElementsByTagName("string").let { nodes ->
            buildMap {
                repeat(nodes.length) { index ->
                    val node = nodes.item(index)
                    put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
                }
            }
        }
    }

    private data class WavStats(
        val audioFormat: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val rmsDbfs: Double,
        val peakDbfs: Double,
    )

    private fun pcm16MonoWavStats(bytes: ByteArray): WavStats {
        var offset = 12
        var audioFormat = -1
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataSize = -1
        while (offset + 8 <= bytes.size) {
            val chunkId = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val chunkSize = littleEndianInt(bytes, offset + 4)
            val payloadOffset = offset + 8
            require(chunkSize >= 0 && payloadOffset + chunkSize <= bytes.size) {
                "Invalid WAV chunk $chunkId"
            }
            when (chunkId) {
                "fmt " -> {
                    audioFormat = littleEndianShort(bytes, payloadOffset)
                    channels = littleEndianShort(bytes, payloadOffset + 2)
                    sampleRate = littleEndianInt(bytes, payloadOffset + 4)
                    bitsPerSample = littleEndianShort(bytes, payloadOffset + 14)
                }
                "data" -> {
                    dataOffset = payloadOffset
                    dataSize = chunkSize
                }
            }
            offset = payloadOffset + chunkSize + (chunkSize and 1)
        }
        require(dataOffset >= 0 && dataSize >= 2) { "WAV data chunk is missing" }
        require(bitsPerSample == 16) { "Expected 16-bit PCM WAV" }

        var sumSquares = 0.0
        var peak = 0.0
        var sampleCount = 0
        var sampleOffset = dataOffset
        val dataEnd = dataOffset + dataSize
        while (sampleOffset + 1 < dataEnd) {
            val sample = littleEndianShort(bytes, sampleOffset).toShort().toInt().toDouble()
            val magnitude = abs(sample)
            sumSquares += sample * sample
            peak = maxOf(peak, magnitude)
            sampleCount += 1
            sampleOffset += 2
        }
        val rms = sqrt(sumSquares / sampleCount) / 32_768.0
        val normalizedPeak = peak / 32_768.0
        return WavStats(
            audioFormat = audioFormat,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            rmsDbfs = 20.0 * log10(rms),
            peakDbfs = 20.0 * log10(normalizedPeak),
        )
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun sourceFile(path: String): File =
        listOf(File(path), File("app", path)).firstOrNull { it.isFile }
            ?: error("Source file not found: $path")
}
