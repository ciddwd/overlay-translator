package com.gameocr.app.dictionary

import com.gameocr.app.translate.WordResult

interface OfflineWordDictionary {
    suspend fun lookup(word: String, languageCode: String): WordResult?
}

object EmptyOfflineWordDictionary : OfflineWordDictionary {
    override suspend fun lookup(word: String, languageCode: String): WordResult? = null
}
