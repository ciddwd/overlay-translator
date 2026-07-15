package com.gameocr.app.translate

import android.util.LruCache

/**
 * 译文缓存：key = 原文 + 模型 + 目标语 + prompt 哈希。
 * 命中率高的话 token 消耗能压一大截，galgame 同句重复出现的场景特别明显。
 */
class TranslationCache(capacity: Int = 256) {
    private val cache = LruCache<String, String>(capacity)

    fun key(source: String, model: String, targetLang: String, prompt: String): String =
        "$model|$targetLang|${prompt.hashCode()}|${source.hashCode()}|${source.length}"

    fun get(key: String): String? = cache.get(key)

    fun put(key: String, value: String) {
        if (!TranslationCachePolicy.shouldCache(value)) return
        cache.put(key, value)
    }

    fun clear() {
        cache.evictAll()
    }
}

internal object TranslationCachePolicy {
    fun shouldCache(value: String): Boolean = value.isNotBlank()
}
