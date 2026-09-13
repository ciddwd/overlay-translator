package com.gameocr.app.ocr

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AutoOcrLanguageIntegrationTest {
    @Test fun realLanguageModelAndBoundedRoutingAreWired_tableDriven() {
        val identifier = File("src/main/java/com/gameocr/app/ocr/OcrTextLanguageIdentifier.kt").readText()
        val auto = File("src/main/java/com/gameocr/app/ocr/AutomaticOcrRecognizer.kt").readText()
        listOf(
            "bundled real language model" to File("build.gradle.kts").readText().contains("com.google.mlkit:language-id:17.0.6"),
            "real client" to identifier.contains("LanguageIdentification.getClient()"),
            "confidence scores considered" to identifier.contains("identifyPossibleLanguages(sample).await()"),
            "bounded samples" to identifier.contains("text.take(1000)"),
            "runtime uses identifier" to auto.contains("identifier.identify(sample)"),
            "per-frame cache bounded" to auto.contains("languageCache.size >= 128"),
            "manual language bypasses identification" to (auto.indexOf("source != \"auto\"") < auto.indexOf("val languageCache")),
            "refinement uses effective settings" to auto.contains("recognize(bitmap, selected.engine, effective)"),
        ).forEach { (name, passed) -> assertTrue(name, passed) }
    }

}
