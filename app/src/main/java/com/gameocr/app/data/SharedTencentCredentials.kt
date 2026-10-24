package com.gameocr.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Read-only compatibility for old ASR-only credentials. New saves contain only shared keys. */
internal object SharedTencentCredentials {
    fun resolve(id: String, key: String, legacyConnections: String): Pair<String, String> {
        if (id.isNotBlank() || key.isNotBlank()) return id to key
        val legacy = runCatching { Json.parseToJsonElement(legacyConnections) as? JsonObject }.getOrNull()
            ?: return id to key
        fun string(name: String) = (legacy[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val oldId = string("tencentSecretId")
        val oldKey = string("tencentSecretKey")
        return if (oldId.isNotBlank() && oldKey.isNotBlank()) oldId to oldKey else id to key
    }
}
