package com.gameocr.app.translate

import com.gameocr.app.data.withApiTimeout
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class OpenAiModelListHttpException(val status: Int) : IOException("HTTP $status")

/** Shared list-only request; never falls back to a chat or audio generation request.
 * https://developers.openai.com/api/reference/resources/models/methods/list
 */
@Singleton
class OpenAiModelListClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetch(url: HttpUrl, apiKey: String, timeoutSeconds: Int): List<String> {
        require(apiKey.isNotBlank())
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json").get().build()
        val call = client.withApiTimeout(timeoutSeconds).newBuilder()
            .followRedirects(false).followSslRedirects(false).build().newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { resp ->
                            if (!resp.isSuccessful) throw OpenAiModelListHttpException(resp.code)
                            val source = checkNotNull(resp.body?.source())
                            check(!source.request(MAX_BODY_BYTES + 1))
                            val root = json.parseToJsonElement(source.readUtf8())
                            check(root is JsonObject && root["data"] is JsonArray)
                            json.decodeFromJsonElement<ModelsResponse>(root).data
                                .mapNotNull { it.id?.trim()?.takeIf(String::isNotEmpty) }
                                .distinct().sorted()
                        }
                    }
                    if (continuation.isActive) continuation.resumeWith(result)
                }
            })
        }
    }

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024L
    }
}
