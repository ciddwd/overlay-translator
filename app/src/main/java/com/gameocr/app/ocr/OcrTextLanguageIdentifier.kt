package com.gameocr.app.ocr

import com.google.mlkit.nl.languageid.LanguageIdentification
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import timber.log.Timber

@Singleton
class OcrTextLanguageIdentifier @Inject constructor() {
    // Bundled model: no network or model-download task is needed to identify text.
    private val client by lazy { LanguageIdentification.getClient() }

    suspend fun identify(text: String): String? = try {
        val sample = text.take(1000)
        AutoOcrLanguagePolicy.choose(sample, client.identifyPossibleLanguages(sample).await().map {
            LanguageScore(it.languageTag, it.confidence)
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.tag("AutoOCR").w(error, "Language identification unavailable")
        null
    }
}
