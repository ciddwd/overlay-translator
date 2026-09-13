package com.gameocr.app.onboarding

/** Official documentation pages verified with a successful HTTPS response before inclusion. */
internal val CloudProvider.documentationUrl: String?
    get() = when (this) {
        CloudProvider.DEEPSEEK -> "https://api-docs.deepseek.com/"
        CloudProvider.KIMI -> "https://platform.kimi.com/docs/get-api-key"
        CloudProvider.MINIMAX -> "https://platform.minimax.cn/docs/api-reference/api-overview"
        CloudProvider.GLM -> "https://docs.bigmodel.cn/cn/api/introduction"
        CloudProvider.MIMO -> "https://mimo.mi.com/docs/en-US/quick-start/faq/api-integration"
        CloudProvider.OPENAI -> "https://developers.openai.com/api/docs"
        CloudProvider.CLAUDE -> "https://platform.claude.com/docs/en/intro"
        CloudProvider.GEMINI -> "https://ai.google.dev/gemini-api/docs"
        CloudProvider.MODELSCOPE -> "https://modelscope.cn/docs"
        CloudProvider.OPENROUTER -> "https://openrouter.ai/docs/quickstart"
        CloudProvider.AIHUBMIX -> "https://docs.aihubmix.com/cn"
        CloudProvider.AI_302 -> "https://doc.302.ai/"
        CloudProvider.SCNET -> "https://www.scnet.cn/ac/openapi/doc/2.0/moduleapi/overview.html"
        CloudProvider.CUSTOM -> null
    }
