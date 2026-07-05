package com.gameocr.app.translate

internal object ChineseScriptNormalizer {
    enum class TargetScript {
        SIMPLIFIED,
        TRADITIONAL
    }

    fun targetScriptFor(targetLang: String): TargetScript? {
        val lang = targetLang.trim().lowercase()
        if (lang.isEmpty()) return null
        return when {
            lang == "zh" ||
                lang == "zh-cn" ||
                lang == "zh-hans" ||
                lang == "zh-sg" ||
                lang == "zh-my" ||
                lang == "zh-chs" ||
                lang.startsWith("zh-hans-") -> TargetScript.SIMPLIFIED

            lang == "zh-tw" ||
                lang == "zh-hant" ||
                lang == "zh-hk" ||
                lang == "zh-mo" ||
                lang == "zh-cht" ||
                lang.startsWith("zh-hant-") -> TargetScript.TRADITIONAL

            else -> null
        }
    }

    fun normalizeForTarget(text: String, targetLang: String): String {
        if (text.isEmpty()) return text
        return when (targetScriptFor(targetLang)) {
            TargetScript.SIMPLIFIED -> toSimplified(text)
            TargetScript.TRADITIONAL -> toTraditional(text)
            null -> text
        }
    }

    private fun toSimplified(text: String): String =
        platformTraditionalToSimplified?.invoke(text) ?: fallbackTraditionalToSimplified(text)

    private fun toTraditional(text: String): String =
        platformSimplifiedToTraditional?.invoke(text) ?: fallbackSimplifiedToTraditional(text)

    private val platformTraditionalToSimplified: ((String) -> String)? =
        platformTransliterator("Traditional-Simplified")

    private val platformSimplifiedToTraditional: ((String) -> String)? =
        platformTransliterator("Simplified-Traditional")

    private fun platformTransliterator(id: String): ((String) -> String)? {
        return runCatching {
            val clazz = Class.forName("android.icu.text.Transliterator")
            val getInstance = clazz.getMethod("getInstance", String::class.java)
            val transliterate = clazz.getMethod("transliterate", String::class.java)
            val instance = getInstance.invoke(null, id)
            val lock = Any()
            val converter: (String) -> String = { input ->
                synchronized(lock) {
                    transliterate.invoke(instance, input) as? String ?: input
                }
            }
            converter
        }.getOrNull()
    }

    private fun fallbackTraditionalToSimplified(text: String): String =
        replacePhrases(text, traditionalToSimplifiedPhrases).mapChars(traditionalToSimplifiedChars)

    private fun fallbackSimplifiedToTraditional(text: String): String =
        replacePhrases(text, simplifiedToTraditionalPhrases).mapChars(simplifiedToTraditionalChars)

    private fun replacePhrases(text: String, phrases: List<Pair<String, String>>): String {
        var out = text
        for ((from, to) in phrases) {
            out = out.replace(from, to)
        }
        return out
    }

    private fun String.mapChars(mapping: Map<Char, Char>): String {
        var changed = false
        val out = StringBuilder(length)
        for (ch in this) {
            val mapped = mapping[ch]
            if (mapped != null) {
                out.append(mapped)
                changed = true
            } else {
                out.append(ch)
            }
        }
        return if (changed) out.toString() else this
    }

    private val traditionalToSimplifiedPhrases = listOf(
        "後臺" to "后台",
        "後台" to "后台",
        "臺灣" to "台湾",
        "台灣" to "台湾",
        "軟體" to "软件",
        "伺服器" to "服务器",
        "資料" to "数据",
        "資訊" to "资讯",
        "網路" to "网络",
        "網絡" to "网络",
        "螢幕" to "屏幕",
        "滑鼠" to "鼠标",
        "檔案" to "文件",
        "預設" to "默认",
        "儲存" to "保存",
        "設定" to "设置",
        "畫面" to "画面",
        "圖像" to "图像",
        "圖片" to "图片",
        "載入" to "加载",
        "傳送" to "发送",
        "執行" to "执行",
        "語言" to "语言",
        "翻譯" to "翻译",
        "簡體" to "简体",
        "繁體" to "繁体",
        "啟動" to "启动",
        "關閉" to "关闭",
        "開啟" to "开启",
        "應用程式" to "应用程序",
        "應用" to "应用",
        "視窗" to "窗口",
        "視訊" to "视频",
        "音訊" to "音频",
        "佇列" to "队列",
        "記憶體" to "内存",
        "內容" to "内容",
        "使用者" to "用户",
        "帳號" to "账号",
        "密碼" to "密码",
        "登入" to "登录",
        "登出" to "退出",
        "匯入" to "导入",
        "匯出" to "导出",
        "品質" to "质量",
        "訊息" to "消息",
        "壓縮" to "压缩",
        "瀏覽" to "浏览",
        "搜尋" to "搜索",
        "選擇" to "选择",
        "顯示" to "显示",
        "隱藏" to "隐藏",
        "複製" to "复制",
        "貼上" to "粘贴",
        "刪除" to "删除",
        "備份" to "备份",
        "還原" to "还原",
        "錯誤" to "错误",
        "偵測" to "检测",
        "識別" to "识别"
    )

    private val simplifiedToTraditionalPhrases =
        traditionalToSimplifiedPhrases.map { (traditional, simplified) -> simplified to traditional }

    private val traditionalToSimplifiedChars: Map<Char, Char> = mapOf(
        '這' to '这', '個' to '个', '還' to '还', '沒' to '没', '與' to '与', '為' to '为',
        '請' to '请', '結' to '结', '轉' to '转',
        '後' to '后', '裏' to '里', '裡' to '里', '臺' to '台', '灣' to '湾', '體' to '体',
        '簡' to '简', '繁' to '繁', '軟' to '软', '體' to '体', '資' to '资', '訊' to '讯',
        '網' to '网', '絡' to '络', '語' to '语', '譯' to '译', '翻' to '翻', '畫' to '画',
        '圖' to '图', '啟' to '启', '動' to '动', '關' to '关', '開' to '开', '應' to '应',
        '視' to '视', '頻' to '频', '音' to '音', '儲' to '储', '檔' to '档', '預' to '预',
        '設' to '设', '執' to '执', '載' to '载', '傳' to '传', '發' to '发', '選' to '选',
        '擇' to '择', '顯' to '显', '隱' to '隐', '複' to '复', '製' to '制', '貼' to '贴',
        '刪' to '删', '錯' to '错', '誤' to '误', '偵' to '侦', '測' to '测', '識' to '识',
        '別' to '别', '內' to '内', '容' to '容', '帳' to '帐', '號' to '号', '碼' to '码',
        '錄' to '录', '匯' to '汇', '導' to '导', '壓' to '压', '縮' to '缩', '瀏' to '浏',
        '覽' to '览', '搜' to '搜', '尋' to '寻', '質' to '质', '準' to '准', '確' to '确',
        '電' to '电', '機' to '机', '器' to '器', '服' to '服', '務' to '务', '線' to '线',
        '連' to '连', '離' to '离', '獲' to '获', '取' to '取', '異' to '异', '常' to '常',
        '狀' to '状', '態' to '态', '當' to '当', '前' to '前', '長' to '长', '時' to '时',
        '間' to '间', '標' to '标', '題' to '题', '頁' to '页', '項' to '项', '類' to '类',
        '節' to '节', '點' to '点', '數' to '数', '據' to '据', '萬' to '万', '億' to '亿'
    )

    private val simplifiedToTraditionalChars: Map<Char, Char> = mapOf(
        '这' to '這', '个' to '個', '还' to '還', '没' to '沒', '与' to '與', '为' to '為',
        '请' to '請', '结' to '結', '转' to '轉',
        '后' to '後', '里' to '裏', '台' to '臺', '湾' to '灣', '体' to '體', '简' to '簡',
        '软' to '軟', '资' to '資', '讯' to '訊', '网' to '網', '络' to '絡', '语' to '語',
        '译' to '譯', '画' to '畫', '图' to '圖', '启' to '啟', '动' to '動', '关' to '關',
        '开' to '開', '应' to '應', '视' to '視', '频' to '頻', '储' to '儲', '档' to '檔',
        '预' to '預', '设' to '設', '执' to '執', '载' to '載', '传' to '傳', '发' to '發',
        '选' to '選', '择' to '擇', '显' to '顯', '隐' to '隱', '复' to '複', '贴' to '貼',
        '删' to '刪', '错' to '錯', '误' to '誤', '侦' to '偵', '测' to '測', '识' to '識',
        '别' to '別', '内' to '內', '帐' to '帳', '号' to '號', '码' to '碼', '录' to '錄',
        '汇' to '匯', '导' to '導', '压' to '壓', '缩' to '縮', '浏' to '瀏', '览' to '覽',
        '寻' to '尋', '质' to '質', '准' to '準', '确' to '確', '电' to '電', '机' to '機',
        '务' to '務', '线' to '線', '连' to '連', '离' to '離', '获' to '獲', '异' to '異',
        '状' to '狀', '态' to '態', '当' to '當', '长' to '長', '时' to '時', '间' to '間',
        '标' to '標', '题' to '題', '页' to '頁', '项' to '項', '类' to '類', '节' to '節',
        '点' to '點', '数' to '數', '据' to '據', '万' to '萬', '亿' to '億'
    )
}
