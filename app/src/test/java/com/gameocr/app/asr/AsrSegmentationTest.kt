package com.gameocr.app.asr

import com.gameocr.app.data.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AsrSegmentationTest {
    @Test fun defaultsPreserveLocalAndRealtimeEndpointsAndUseShorterHttpFiles() {
        val values = AsrSegmentationSettings()
        listOf(
            Triple(AsrSegmentationProfile.ON_DEVICE, 350, 6000),
            Triple(AsrSegmentationProfile.ZIPFORMER, 600, 6000),
            Triple(AsrSegmentationProfile.HTTP, 350, 4000),
            Triple(AsrSegmentationProfile.REALTIME, 400, 6000),
        ).forEach { (profile, pause, max) ->
            assertEquals(profile.name, AsrSegmentDurations(pause, max), values.forProfile(profile))
        }
        assertEquals(values, Json.decodeFromString<AsrSegmentationSettings>("{}"))
    }

    @Test fun selectionIsExplicitAndServerManagedProvidersHaveNoClientControls() {
        listOf(
            AsrEngineKind.SENSE_VOICE to AsrSegmentationProfile.ON_DEVICE,
            AsrEngineKind.WHISPER to AsrSegmentationProfile.ON_DEVICE,
            AsrEngineKind.ZIPFORMER to AsrSegmentationProfile.ZIPFORMER,
        ).forEach { (engine, profile) ->
            assertEquals(profile, AsrSegmentationPolicy.profile(Settings(asrEngine = engine)))
        }
        for (provider in AsrCloudProvider.entries) for (transport in OpenAiAsrTransport.entries) {
            val settings = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD, asrCloudProvider = provider,
                asrCloudConnections = AsrCloudConnections(openAiTransport = transport))
            val expected = when {
                provider != AsrCloudProvider.OPENAI_COMPATIBLE -> null
                transport == OpenAiAsrTransport.HTTP_TRANSCRIPTION -> AsrSegmentationProfile.HTTP
                else -> AsrSegmentationProfile.REALTIME
            }
            assertEquals("$provider/$transport", expected, AsrSegmentationPolicy.profile(settings))
            assertEquals(expected == null, AsrSegmentationPolicy.durations(settings) == null)
        }
    }

    @Test fun everyProfileIsIndependentAndOutOfRangeValuesAreBounded() {
        val initial = AsrSegmentationSettings()
        val cases = listOf(
            AsrSegmentDurations(Int.MIN_VALUE, Int.MIN_VALUE) to AsrSegmentDurations(200, 2000),
            AsrSegmentDurations(Int.MAX_VALUE, Int.MAX_VALUE) to AsrSegmentDurations(2000, 15000),
            AsrSegmentDurations(750, 9000) to AsrSegmentDurations(750, 9000),
        )
        for (profile in AsrSegmentationProfile.entries) for ((input, expected) in cases) {
            val updated = initial.withProfile(profile, input)
            assertEquals(expected, updated.forProfile(profile))
            AsrSegmentationProfile.entries.filterNot { it == profile }.forEach {
                assertEquals(it.name, initial.forProfile(it), updated.forProfile(it))
            }
        }
    }

    @Test fun selectedValuesReachVadAndStreamingRulesWithoutChangingSpeechSensitivity() {
        for (durations in listOf(AsrSegmentDurations(200, 2000), AsrSegmentDurations(750, 9000),
            AsrSegmentDurations(2000, 15000), AsrSegmentDurations(Int.MAX_VALUE, Int.MIN_VALUE))) {
            val effective = durations.normalized()
            val vad = AsrVadPolicy.config(durations)
            assertEquals(16000, vad.sampleRate)
            assertEquals(1, vad.numThreads)
            assertEquals("cpu", vad.provider)
            with(vad.sileroVadModelConfig) {
                assertEquals(effective.pauseMs / 1000f, minSilenceDuration)
                assertEquals(effective.maxSpeechMs / 1000f, maxSpeechDuration)
                assertEquals(0.25f, minSpeechDuration)
                assertEquals(0.5f, threshold)
                assertEquals(512, windowSize)
                assertEquals(AsrVadModel.ASSET_PATH, model)
            }
            val streaming = AsrVadPolicy.streamingConfig(durations)
            assertEquals(2.4f, streaming.rule1.minTrailingSilence)
            assertFalse(streaming.rule1.mustContainNonSilence)
            assertEquals(effective.pauseMs / 1000f, streaming.rule2.minTrailingSilence)
            assertTrue(streaming.rule2.mustContainNonSilence)
            assertEquals(effective.maxSpeechMs / 1000f, streaming.rule3.minUtteranceLength)
            // Longer local turns retain the entire partial instead of truncating the start.
            val samples = AsrVadPolicy.openBufferSamples(durations)
            assertEquals((effective.maxSpeechMs + 1000) * 16, samples)
            val buffer = SampleBuffer(samples)
            buffer.append(FloatArray(effective.maxSpeechMs * 16) { 0.25f })
            buffer.append(FloatArray(16000) { 0.5f })
            assertEquals(samples, buffer.size)
            assertEquals(0.25f, buffer.toArray().first())
        }
    }

    @Test fun onlyEffectiveEndpointChangesRestartAnEngine() {
        for (engine in AsrEngineKind.entries) for (provider in AsrCloudProvider.entries) {
            val settings = Settings(asrEngine = engine, asrCloudProvider = provider)
            val profile = AsrSegmentationPolicy.profile(settings)
            val key = SpeechStartPolicy.engineKey(settings)
            for (changedProfile in AsrSegmentationProfile.entries) {
                val changed = settings.copy(asrSegmentation = settings.asrSegmentation.withProfile(
                    changedProfile, AsrSegmentDurations(900, 11000)))
                assertEquals("$engine/$provider/$changedProfile", changedProfile == profile,
                    key != SpeechStartPolicy.engineKey(changed))
            }
        }
        val realtime = Settings(asrEngine = AsrEngineKind.PARAFORMER_CLOUD,
            asrCloudProvider = AsrCloudProvider.OPENAI_COMPATIBLE,
            asrCloudConnections = AsrCloudConnections(openAiTransport = OpenAiAsrTransport.REALTIME_TRANSCRIPTION))
        assertNotEquals(SpeechStartPolicy.engineKey(realtime), SpeechStartPolicy.engineKey(realtime.copy(
            asrSegmentation = realtime.asrSegmentation.copy(realtime = AsrSegmentDurations(800, 8000)))))
    }

    @Test fun portableSettingsIncludeEndpointChoicesWithoutCredentialsAndIgnoreMalformedImports() {
        val values = AsrSegmentationSettings(http = AsrSegmentDurations(800, 8000),
            realtime = AsrSegmentDurations(500, 5000))
        val settings = Settings(asrSegmentation = values,
            asrCloudConnections = AsrCloudConnections(openAiApiKey = "private-asr-key"))
        val exported = SettingsFieldPolicy.encodePortable(settings)
        assertTrue(exported.containsKey("asrSegmentation"))
        assertFalse(exported.toString().contains("private-asr-key"))
        val imported = SettingsFieldPolicy.decodePortable(exported)
        assertTrue(imported.skippedFields.isEmpty())
        assertEquals(values, imported.settings.asrSegmentation)
        val merged = SettingsFieldPolicy.applyPortable(Settings(apiKey = "translation-key"), imported.settings)
        assertEquals(values, merged.asrSegmentation)
        assertEquals("translation-key", merged.apiKey)
        val malformed = SettingsFieldPolicy.decodePortable(JsonObject(mapOf("asrSegmentation" to JsonPrimitive("invalid"))))
        assertEquals(listOf("asrSegmentation"), malformed.skippedFields)
        assertEquals(AsrSegmentationSettings(), malformed.settings.asrSegmentation)
    }
}
