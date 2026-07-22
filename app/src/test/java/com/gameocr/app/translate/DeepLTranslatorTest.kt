package com.gameocr.app.translate

import android.content.Context
import android.content.ContextWrapper
import com.gameocr.app.data.DeeplProtocol
import com.gameocr.app.data.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLTranslatorTest {
    private val translator = DeepLTranslator(
        appContext = TestContext(),
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        cache = TranslationCache(),
    )

    @Test
    fun testConnection_invalidInputReturnsFailureInsteadOfThrowing() = runBlocking {
        val cases = listOf(
            Settings(
                deeplProtocol = DeeplProtocol.OFFICIAL,
                deeplApiKey = "key\u3000with-full-width-space",
            ),
            Settings(
                deeplProtocol = DeeplProtocol.DEEPLX,
                deeplBaseUrl = "https://invalid host.example",
            ),
        )

        cases.forEach { settings ->
            val result = translator.testConnection(settings)

            assertFalse(settings.toString(), result.success)
            assertTrue(settings.toString(), result.message.isNotBlank())
        }
    }

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
