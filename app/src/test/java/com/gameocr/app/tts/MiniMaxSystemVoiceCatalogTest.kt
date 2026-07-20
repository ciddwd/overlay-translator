package com.gameocr.app.tts

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMaxSystemVoiceCatalogTest {

    @Test
    fun catalog_matchesOfficialMiniMaxVoiceTable() {
        assertEquals(327, MINIMAX_SYSTEM_VOICES.size)
        assertEquals(327, MINIMAX_SYSTEM_VOICES.map { it.voiceId }.toSet().size)
        assertEquals("male-qn-qingse", MINIMAX_SYSTEM_VOICES.first().voiceId)
        assertEquals("hindi_female_1_v2", MINIMAX_SYSTEM_VOICES.last().voiceId)

        val counts = MINIMAX_SYSTEM_VOICES.groupingBy { it.language }.eachCount()
        data class Case(val language: String, val expected: Int)

        listOf(
            Case("中文 (普通话)", 58),
            Case("中文 (粤语)", 6),
            Case("英文", 16),
            Case("日文", 15),
            Case("韩文", 49),
            Case("西班牙文", 47),
            Case("葡萄牙文", 73),
            Case("印地文", 3),
        ).forEach { case ->
            assertEquals(case.language, case.expected, counts[case.language])
        }
    }

    @Test
    fun search_matchesLanguageNameAndVoiceIdWithAndSemantics() {
        data class Case(
            val name: String,
            val query: String,
            val expectedCount: Int,
            val expectedVoiceId: String? = null,
        )

        listOf(
            Case("blank returns full catalog", "  ", 327, "male-qn-qingse"),
            Case("Mandarin language tokens", "中文 普通话", 58),
            Case("Cantonese language", "粤语", 6),
            Case("language and English voice name", "英文 sweet girl", 1, "Sweet_Girl"),
            Case("Japanese language and voice name", "日文 loyal knight", 1, "Japanese_LoyalKnight"),
            Case("case-insensitive Voice ID", "KOREAN_DOMINANTMAN", 1, "Korean_DominantMan"),
            Case("Chinese voice name", "温柔女声", 1, "Cantonese_GentleLady"),
            Case(
                "full-width punctuation in official Voice ID",
                "Cantonese_ProfessionalHost（F)",
                1,
                "Cantonese_ProfessionalHost（F)",
            ),
            Case("unknown query", "no-such-minimax-voice", 0),
        ).forEach { case ->
            val result = searchMiniMaxSystemVoices(case.query)
            assertEquals(case.name, case.expectedCount, result.size)
            case.expectedVoiceId?.let { expected ->
                assertEquals(case.name, expected, result.firstOrNull()?.voiceId)
            }
        }
    }

    @Test
    fun sourceUrl_pointsToOfficialHttpsDocumentation() {
        val uri = URI(MINIMAX_SYSTEM_VOICE_SOURCE_URL)
        assertEquals("https", uri.scheme)
        assertEquals("platform.minimaxi.com", uri.host)
        assertEquals("/docs/faq/system-voice-id", uri.path)
        assertTrue(uri.query.isNullOrEmpty())
    }
}
