package com.gameocr.app.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class MiniMaxVoicePreviewPolicyTest {

    @Test
    fun previewText_tableDriven_usesEverySystemVoiceLanguage() {
        data class Case(val language: String, val expected: String)

        val cases = listOf(
            Case("中文 (普通话)", "你好，这是当前音色的试听效果。"),
            Case("中文 (粤语)", "你好，呢段係而家呢把声嘅试听效果。"),
            Case("英文", "Hello, this is a preview of the selected voice."),
            Case("日文", "こんにちは、これは選択した音声のプレビューです。"),
            Case("韩文", "안녕하세요, 선택한 음성의 미리 듣기입니다."),
            Case("西班牙文", "Hola, esta es una muestra de la voz seleccionada."),
            Case("法文", "Bonjour, voici un aperçu de la voix sélectionnée."),
            Case("德文", "Hallo, dies ist eine Vorschau der ausgewählten Stimme."),
            Case("葡萄牙文", "Olá, esta é uma prévia da voz selecionada."),
            Case("俄文", "Здравствуйте, это пример выбранного голоса."),
            Case("意大利文", "Ciao, questa è un'anteprima della voce selezionata."),
            Case("土耳其文", "Merhaba, bu seçilen sesin bir ön izlemesidir."),
            Case("乌克兰文", "Вітаю, це приклад вибраного голосу."),
            Case("越南文", "Xin chào, đây là bản nghe thử của giọng nói đã chọn."),
            Case("印尼文", "Halo, ini adalah pratinjau suara yang dipilih."),
            Case("泰文", "สวัสดี นี่คือตัวอย่างเสียงที่เลือก"),
            Case("阿拉伯文", "مرحبًا، هذه معاينة للصوت المحدد."),
            Case("荷兰文", "Hallo, dit is een voorbeeld van de geselecteerde stem."),
            Case("波兰文", "Dzień dobry, to jest próbka wybranego głosu."),
            Case("罗马尼亚文", "Bună, aceasta este o previzualizare a vocii selectate."),
            Case("希腊文", "Γεια σας, αυτή είναι μια προεπισκόπηση της επιλεγμένης φωνής."),
            Case("捷克文", "Dobrý den, toto je ukázka vybraného hlasu."),
            Case("芬兰文", "Hei, tämä on valitun äänen esikatselu."),
            Case("印地文", "नमस्ते, यह चुनी गई आवाज़ का पूर्वावलोकन है।"),
        )

        cases.forEach { case ->
            assertEquals(case.language, case.expected, miniMaxVoicePreviewText(case.language, "en"))
        }
        assertEquals("every language must have its own phrase", cases.size, cases.map { it.expected }.toSet().size)
    }

    @Test
    fun previewText_tableDriven_fallsBackToConfiguredLanguageForAccountVoices() {
        data class Case(val language: String, val fallback: String, val expected: String)

        listOf(
            Case("", "ja-JP", "こんにちは、これは選択した音声のプレビューです。"),
            Case("unknown", "zh-HK", "你好，呢段係而家呢把声嘅试听效果。"),
            Case("  ", "pt_BR", "Olá, esta é uma prévia da voz selecionada."),
            Case("unknown", "auto", "Hello, this is a preview of the selected voice."),
        ).forEach { case ->
            assertEquals(
                "${case.language}/${case.fallback}",
                case.expected,
                miniMaxVoicePreviewText(case.language, case.fallback),
            )
        }
    }
}
