package com.gameocr.app.tts

private val MINI_MAX_VOICE_PREVIEW_TEXT_BY_LANGUAGE = mapOf(
    "中文 (普通话)" to "你好，这是当前音色的试听效果。",
    "中文 (粤语)" to "你好，呢段係而家呢把声嘅试听效果。",
    "英文" to "Hello, this is a preview of the selected voice.",
    "日文" to "こんにちは、これは選択した音声のプレビューです。",
    "韩文" to "안녕하세요, 선택한 음성의 미리 듣기입니다.",
    "西班牙文" to "Hola, esta es una muestra de la voz seleccionada.",
    "法文" to "Bonjour, voici un aperçu de la voix sélectionnée.",
    "德文" to "Hallo, dies ist eine Vorschau der ausgewählten Stimme.",
    "葡萄牙文" to "Olá, esta é uma prévia da voz selecionada.",
    "俄文" to "Здравствуйте, это пример выбранного голоса.",
    "意大利文" to "Ciao, questa è un'anteprima della voce selezionata.",
    "土耳其文" to "Merhaba, bu seçilen sesin bir ön izlemesidir.",
    "乌克兰文" to "Вітаю, це приклад вибраного голосу.",
    "越南文" to "Xin chào, đây là bản nghe thử của giọng nói đã chọn.",
    "印尼文" to "Halo, ini adalah pratinjau suara yang dipilih.",
    "泰文" to "สวัสดี นี่คือตัวอย่างเสียงที่เลือก",
    "阿拉伯文" to "مرحبًا، هذه معاينة للصوت المحدد.",
    "荷兰文" to "Hallo, dit is een voorbeeld van de geselecteerde stem.",
    "波兰文" to "Dzień dobry, to jest próbka wybranego głosu.",
    "罗马尼亚文" to "Bună, aceasta este o previzualizare a vocii selectate.",
    "希腊文" to "Γεια σας, αυτή είναι μια προεπισκόπηση της επιλεγμένης φωνής.",
    "捷克文" to "Dobrý den, toto je ukázka vybraného hlasu.",
    "芬兰文" to "Hei, tämä on valitun äänen esikatselu.",
    "印地文" to "नमस्ते, यह चुनी गई आवाज़ का पूर्वावलोकन है।",
)

internal fun miniMaxVoicePreviewText(
    language: String,
    fallbackLanguageTag: String,
): String {
    val languageKey = language.trim().takeIf(MINI_MAX_VOICE_PREVIEW_TEXT_BY_LANGUAGE::containsKey)
        ?: miniMaxPreviewLanguageForTag(fallbackLanguageTag)
    return MINI_MAX_VOICE_PREVIEW_TEXT_BY_LANGUAGE.getValue(languageKey)
}

private fun miniMaxPreviewLanguageForTag(rawTag: String): String {
    val normalized = rawTag.trim().lowercase().replace('_', '-')
    if (normalized == "yue" || normalized.startsWith("yue-") ||
        normalized.startsWith("zh-hk") || normalized.startsWith("zh-mo")
    ) {
        return "中文 (粤语)"
    }
    return when (normalized.substringBefore('-')) {
        "zh" -> "中文 (普通话)"
        "ja" -> "日文"
        "ko" -> "韩文"
        "es" -> "西班牙文"
        "fr" -> "法文"
        "de" -> "德文"
        "pt" -> "葡萄牙文"
        "ru" -> "俄文"
        "it" -> "意大利文"
        "tr" -> "土耳其文"
        "uk" -> "乌克兰文"
        "vi" -> "越南文"
        "id" -> "印尼文"
        "th" -> "泰文"
        "ar" -> "阿拉伯文"
        "nl" -> "荷兰文"
        "pl" -> "波兰文"
        "ro" -> "罗马尼亚文"
        "el" -> "希腊文"
        "cs" -> "捷克文"
        "fi" -> "芬兰文"
        "hi" -> "印地文"
        else -> "英文"
    }
}
