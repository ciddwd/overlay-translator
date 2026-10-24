package com.gameocr.app.translate

import com.gameocr.app.asr.OpenAiAsrProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class OpenAiModelListPolicyTest {
    @Test fun officialSites_onlyRequestChatModels_withoutChangingTheHostOrCredential() = runBlocking {
        listOf("api.siliconflow.cn", "api.siliconflow.com").forEach { host ->
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val request = chain.request()
                assertEquals("GET", request.method)
                assertEquals(host, request.url.host)
                assertEquals("/v1/models", request.url.encodedPath)
                assertEquals("sub_type=chat", request.url.encodedQuery)
                assertEquals("Bearer $host-key", request.header("Authorization"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200)
                    .message("test").body("""{"data":[{"id":"org/chat-model"},{"id":"org/vision-model"}]}""".toResponseBody()).build()
            }.build()
            try {
                val models = OpenAiModelListClient(client, Json).fetch(
                    chatModelListUrl("https://$host/v1/models".toHttpUrl()), "$host-key", 30,
                )
                assertEquals(listOf("org/chat-model", "org/vision-model"), models)
                assertEquals(1, calls)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun customEndpointsAndExplicitQueries_arePreservedExactly() {
        listOf(
            "https://api.openai.com/v1/models",
            "https://gateway.example/v1/models",
            "https://api.siliconflow.cn.evil.example/v1/models",
            "https://api.siliconflow.com/v2/models",
            "https://api.siliconflow.cn/proxy/v1/models",
            "http://api.siliconflow.cn/v1/models",
            "https://api.siliconflow.cn:8443/v1/models",
            "https://user@api.siliconflow.cn/v1/models",
            "https://api.siliconflow.cn/v1/models?sub_type=speech-to-text",
            "https://api.siliconflow.cn/v1/models#custom",
        ).forEach { raw ->
            val url = raw.toHttpUrl()
            assertSame(raw, url, chatModelListUrl(url))
        }
        assertEquals("chat", chatModelListUrl("https://API.SILICONFLOW.COM:443/v1/models".toHttpUrl())
            .queryParameter("sub_type"))
    }

    @Test fun speechModelLists_neverUseTheChatFilter() {
        listOf("cn", "com").forEach { site ->
            val url = OpenAiAsrProtocol.modelsEndpoint("https://api.siliconflow.$site/v1")!!
            assertEquals("https://api.siliconflow.$site/v1/models", url.toString())
            assertNull(url.query)
        }
    }
}
