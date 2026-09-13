package com.gameocr.app.dictionary

import com.gameocr.app.data.TranslatorEngine
import java.text.Normalizer
import java.util.Locale

enum class DictionaryPackId(val wireId: String, val fileName: String) {
    ECDICT("ecdict", "ecdict.db"),
    JMDICT("jmdict", "jmdict.db"),
    KOREAN_BASIC("korean-basic", "korean-basic.db"),
}

data class DictionaryPackSpec(
    val id: DictionaryPackId,
    val languageCode: String,
    val displayName: String,
    val sourceName: String,
    val licenseName: String,
)

object DictionaryPackCatalog {
    val all: List<DictionaryPackSpec> = listOf(
        DictionaryPackSpec(
            id = DictionaryPackId.ECDICT,
            languageCode = "en",
            displayName = "English (ECDICT)",
            sourceName = "ECDICT",
            licenseName = "MIT",
        ),
        DictionaryPackSpec(
            id = DictionaryPackId.JMDICT,
            languageCode = "ja",
            displayName = "日本語 JMdict",
            sourceName = "Electronic Dictionary Research and Development Group",
            licenseName = "CC BY-SA 4.0",
        ),
        DictionaryPackSpec(
            id = DictionaryPackId.KOREAN_BASIC,
            languageCode = "ko",
            displayName = "한국어 한국어기초사전",
            sourceName = "National Institute of Korean Language",
            licenseName = "CC BY-SA 2.0 KR（文字数据）",
        ),
    )

    fun forLanguage(languageCode: String): DictionaryPackSpec? {
        val normalized = languageCode.trim().lowercase(Locale.ROOT)
        return all.firstOrNull { spec ->
            normalized == spec.languageCode || normalized.startsWith("${spec.languageCode}-")
        }
    }

    fun byId(id: DictionaryPackId): DictionaryPackSpec = all.first { it.id == id }
}

fun supportsOnlineDictionaryLookup(engine: TranslatorEngine): Boolean =
    engine == TranslatorEngine.OPENAI || engine == TranslatorEngine.ANTHROPIC

internal fun normalizeDictionaryHeadword(value: String): String = Normalizer
    .normalize(value.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
