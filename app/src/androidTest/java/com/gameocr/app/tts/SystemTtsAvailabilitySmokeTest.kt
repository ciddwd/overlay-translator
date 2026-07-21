package com.gameocr.app.tts

import android.speech.tts.TextToSpeech
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemTtsAvailabilitySmokeTest {

    @Test
    fun defaultEngine_initializesAndExposesVoices() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val initialized = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        var engine: TextToSpeech? = null

        try {
            instrumentation.runOnMainSync {
                engine = TextToSpeech(context) { result ->
                    status = result
                    initialized.countDown()
                }
            }
            assertTrue("TTS initialization timed out", initialized.await(10, TimeUnit.SECONDS))
            val readyEngine = requireNotNull(engine)
            assertEquals("defaultEngine=${readyEngine.defaultEngine}", TextToSpeech.SUCCESS, status)

            var voiceNames = emptyList<String>()
            instrumentation.runOnMainSync {
                voiceNames = readyEngine.voices.orEmpty().map { it.name }.sorted()
            }
            assertTrue(
                "defaultEngine=${readyEngine.defaultEngine} returned no voices",
                voiceNames.isNotEmpty(),
            )
        } finally {
            engine?.let { created -> instrumentation.runOnMainSync { created.shutdown() } }
        }
    }
}
