package com.gameocr.app.translate

import okhttp3.HttpUrl

/** SiliconFlow documents sub_type=chat for chat-capable models, including vision models.
 * https://docs.siliconflow.cn/docs/api/models-get
 * https://docs.siliconflow.com/en/api-reference/models/get-model-list
 * Only translation calls this policy; the shared ASR model-list client stays unfiltered.
 */
internal fun chatModelListUrl(url: HttpUrl): HttpUrl = if (
    url.scheme == "https" && url.port == 443 &&
    url.host in setOf("api.siliconflow.cn", "api.siliconflow.com") &&
    url.encodedPath == "/v1/models" && url.username.isEmpty() && url.password.isEmpty() &&
    url.query == null && url.fragment == null
) {
    url.newBuilder().addQueryParameter("sub_type", "chat").build()
} else {
    url
}
