package com.gameocr.app.translate

import com.gameocr.app.llm.LlamaMultiSequence
import org.junit.Assert.assertEquals
import org.junit.Test

class HyMt2OutputPolicyTest {

    @Test
    fun normalizeHarmlessSingleItemPrefix_tableDriven_isStrictAndContentAgnostic() {
        data class Case(
            val name: String,
            val source: String = "あっ!?",
            val output: String,
            val requestHadBackground: Boolean = true,
            val expected: String,
        )

        listOf(
            Case("numbered single line", output = "1. 啊！？", expected = "啊！？"),
            Case("alternative number delimiter", output = "23） 啊！？", expected = "啊！？"),
            Case("full width number marker", output = "１． 啊！？", expected = "啊！？"),
            Case("bullet single line", output = "- 啊！？", expected = "啊！？"),
            Case("ordinary output", output = "啊！？", expected = "啊！？"),
            Case(
                name = "quick request is unchanged",
                output = "1. 啊！？",
                requestHadBackground = false,
                expected = "1. 啊！？",
            ),
            Case(
                name = "list source keeps list output",
                source = "1. 最初の項目",
                output = "1. 第一项",
                expected = "1. 第一项",
            ),
            Case(
                name = "multiple output lines remain rejectable",
                output = "1. 第一条\n2. 第二条",
                expected = "1. 第一条\n2. 第二条",
            ),
            Case(
                name = "header leakage remains rejectable after prefix",
                output = "1. [Source Text] 梓酱，你是谁？",
                expected = "1. [Source Text] 梓酱，你是谁？",
            ),
            Case(
                name = "punctuation expansion remains rejectable after prefix",
                source = "?",
                output = "1. 如果听到那样的声音肯定很可怕",
                expected = "1. 如果听到那样的声音肯定很可怕",
            ),
            Case(
                name = "short source expansion remains rejectable after prefix",
                source = "え",
                output = "1. 哎呀，阿比仓同学愿意帮忙的话，也许就能成功呢！",
                expected = "1. 哎呀，阿比仓同学愿意帮忙的话，也许就能成功呢！",
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                HyMt2OutputPolicy.normalizeHarmlessSingleItemPrefix(
                    source = case.source,
                    output = case.output,
                    requestHadBackground = case.requestHadBackground,
                ),
            )
        }
    }

    @Test
    fun inspect_tableDriven_rejectsPromptAndWholePageBleeding() {
        data class Case(
            val name: String,
            val source: String = "梓ちゃん誰?",
            val output: String,
            val requestHadBackground: Boolean = true,
            val expected: HyMt2OutputPolicy.Decision,
        )

        listOf(
            Case(
                name = "normal translation",
                output = "梓酱，你是谁？",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "valid long translation is not rejected by length",
                source = "そうなの?",
                output = "真的是这样吗？我此前完全不知道这件事情，也没有任何人向我解释过。",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "raw Japanese onomatopoeia can be intentionally retained",
                source = "ドキドキ",
                output = "ドキドキ",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "multiple source lines can keep multiple output lines",
                source = "一行目\n二行目\n三行目",
                output = "第一行\n第二行\n第三行",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "source list item can keep its item prefix",
                source = "1. 最初の項目",
                output = "1. 第一项",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "punctuation remains punctuation",
                source = "?",
                output = "？",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "short interjection remains short",
                source = "え",
                output = "诶？",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "ordinary word background is valid",
                source = "背景はきれいだね",
                output = "背景真漂亮啊。",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "English prompt sections",
                output = "[Background Information]\nTranslated context\n[Source Text]\n梓酱，你是谁？",
                expected = reject(HyMt2OutputPolicy.Reason.MULTI_SECTION_ECHO),
            ),
            Case(
                name = "translated Chinese prompt sections from device log",
                output = "【背景信息】\n同页的其他对话……\n[原文文本]\n梓酱，你是谁？",
                expected = reject(HyMt2OutputPolicy.Reason.MULTI_SECTION_ECHO),
            ),
            Case(
                name = "official Chinese prompt sections",
                output = "〖背景信息〗\n其他台词\n〖待翻译文本〗\n梓酱，你是谁？",
                expected = reject(HyMt2OutputPolicy.Reason.MULTI_SECTION_ECHO),
            ),
            Case(
                name = "background header only",
                output = "[Background Information]\n其他台词",
                expected = reject(HyMt2OutputPolicy.Reason.BACKGROUND_HEADER_ECHO),
            ),
            Case(
                name = "source header only",
                output = "【原文文本】\n梓酱，你是谁？",
                expected = reject(HyMt2OutputPolicy.Reason.SOURCE_HEADER_ECHO),
            ),
            Case(
                name = "English instruction echo",
                output = "Please translate the following text into Chinese, taking the provided " +
                    "background information into consideration.",
                expected = reject(HyMt2OutputPolicy.Reason.TRANSLATION_INSTRUCTION_ECHO),
            ),
            Case(
                name = "Chinese instruction echo",
                output = "请结合背景信息将以下文本翻译为中文。",
                expected = reject(HyMt2OutputPolicy.Reason.TRANSLATION_INSTRUCTION_ECHO),
            ),
            Case(
                name = "native line cap marker is never displayable",
                output = "1. 第一条\n2. 第二条\n${LlamaMultiSequence.LINE_LIMIT_SENTINEL}",
                expected = reject(HyMt2OutputPolicy.Reason.NATIVE_LINE_LIMIT),
            ),
            Case(
                name = "numbered whole page from device log",
                source = "?",
                output = "1. 我们学校会举办体育节\n2. 哇\n3. 从现在开始练习吧",
                expected = reject(HyMt2OutputPolicy.Reason.MULTI_ITEM_OUTPUT),
            ),
            Case(
                name = "numbered Japanese background from device log",
                source = "ゼェ...ゼェ...",
                output = "14. はあ\n15. 色んな意味で!!\n16. びくんっ",
                expected = reject(HyMt2OutputPolicy.Reason.MULTI_ITEM_OUTPUT),
            ),
            Case(
                name = "unnumbered multi paragraph response for one source",
                source = "ひっ...",
                output = "第一段\n第二段\n第三段",
                expected = reject(HyMt2OutputPolicy.Reason.MULTI_ITEM_OUTPUT),
            ),
            Case(
                name = "single unexpected numbering is structural leakage",
                source = "あっ!?",
                output = "1. 啊！？",
                expected = reject(HyMt2OutputPolicy.Reason.UNEXPECTED_ITEM_PREFIX),
            ),
            Case(
                name = "punctuation source cannot expand into translated page",
                source = "?",
                output = "如果听到那样的声音肯定很可怕",
                expected = reject(HyMt2OutputPolicy.Reason.PUNCTUATION_SOURCE_EXPANSION),
            ),
            Case(
                name = "one character source cannot absorb neighboring dialogue",
                source = "え",
                output = "哎呀，阿比仓同学愿意帮忙的话，也许就能成功呢！",
                expected = reject(HyMt2OutputPolicy.Reason.SHORT_SOURCE_CONTEXT_EXPANSION),
            ),
            Case(
                name = "structural expansion checks only apply to background requests",
                source = "え",
                output = "哎呀，阿比仓同学愿意帮忙的话，也许就能成功呢！",
                requestHadBackground = false,
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "literal source header requested by source is retained",
                source = "[Source Text]",
                output = "[Source Text]",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "literal background header requested by source is retained",
                source = "[Background Information]",
                output = "[Background Information]",
                expected = HyMt2OutputPolicy.Decision.Accept,
            ),
            Case(
                name = "unexpected header is rejected without background request too",
                output = "[Source Text]\n梓酱，你是谁？",
                requestHadBackground = false,
                expected = reject(HyMt2OutputPolicy.Reason.SOURCE_HEADER_ECHO),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                HyMt2OutputPolicy.inspect(
                    source = case.source,
                    output = case.output,
                    requestHadBackground = case.requestHadBackground,
                ),
            )
        }
    }

    private fun reject(reason: HyMt2OutputPolicy.Reason): HyMt2OutputPolicy.Decision =
        HyMt2OutputPolicy.Decision.Reject(reason)
}
