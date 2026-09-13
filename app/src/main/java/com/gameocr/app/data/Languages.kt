package com.gameocr.app.data

import android.content.Context
import androidx.annotation.StringRes
import com.gameocr.app.R

/**
 * 翻译/源语言条目。code 用 BCP-47（"ja"、"zh-CN"、"pt-BR" 等），name 通过 [nameRes] 资源 id 提供，
 * 跟随系统语言显示中文 / 英文（i18n）。
 *
 * 下游用法：
 * - 写入 [Settings.sourceLang] / [Settings.targetLang] 都是 String code
 * - OpenAiTranslator 把 code 转成当前 locale 下的显示名填进 prompt（{source}/{target} 占位符）
 * - DeepLTranslator 把 code 映射成 DeepL API 的大写代码（"JA"/"ZH" 等）
 */
data class Language(val code: String, @StringRes val nameRes: Int)

/**
 * 完整可选语言列表，按 Google 翻译 Web 端语言选择面板的顺序整理。
 *
 * 与 Google 列表的差异：
 * - 把单一的 "zh" 拆成 [Languages.ZH_CN] / [Languages.ZH_TW]，方便用户区分简繁
 * - 第一项是 [Languages.AUTO]（"auto"），仅源语言侧有意义；目标语言侧选 auto 会被
 *   DeepLTranslator/OpenAiTranslator 视作回退到默认 "zh-CN"。
 *
 * 添加新语言时直接 append；在 values/strings.xml + values-en/strings.xml 同步加 lang_xx 资源。
 */
object Languages {
    val AUTO = Language("auto", R.string.lang_auto)
    val ZH_CN = Language("zh-CN", R.string.lang_zh_cn)
    val ZH_TW = Language("zh-TW", R.string.lang_zh_tw)

    val ALL: List<Language> = listOf(
        AUTO,
        Language("sq", R.string.lang_sq),
        Language("af", R.string.lang_af),
        Language("ar", R.string.lang_ar),
        Language("an", R.string.lang_an),
        Language("as", R.string.lang_as),
        Language("az", R.string.lang_az),
        Language("ay", R.string.lang_ay),
        Language("ga", R.string.lang_ga),
        Language("et", R.string.lang_et),
        Language("oc", R.string.lang_oc),
        Language("om", R.string.lang_om),
        Language("ba", R.string.lang_ba),
        Language("eu", R.string.lang_eu),
        Language("be", R.string.lang_be),
        Language("pag", R.string.lang_pag),
        Language("pam", R.string.lang_pam),
        Language("bg", R.string.lang_bg),
        Language("is", R.string.lang_is),
        Language("pl", R.string.lang_pl),
        Language("bs", R.string.lang_bs),
        Language("fa", R.string.lang_fa),
        Language("bho", R.string.lang_bho),
        Language("br", R.string.lang_br),
        Language("tn", R.string.lang_tn),
        Language("ts", R.string.lang_ts),
        Language("prs", R.string.lang_prs),
        Language("da", R.string.lang_da),
        Language("de", R.string.lang_de),
        Language("ru", R.string.lang_ru),
        Language("fr", R.string.lang_fr),
        Language("sa", R.string.lang_sa),
        Language("fi", R.string.lang_fi),
        Language("qu", R.string.lang_qu),
        Language("ka", R.string.lang_ka),
        Language("gu", R.string.lang_gu),
        Language("gn", R.string.lang_gn),
        Language("kk", R.string.lang_kk),
        Language("ht", R.string.lang_ht),
        Language("ko", R.string.lang_ko),
        Language("ha", R.string.lang_ha),
        Language("nl", R.string.lang_nl),
        Language("ky", R.string.lang_ky),
        Language("gl", R.string.lang_gl),
        Language("ca", R.string.lang_ca),
        Language("cs", R.string.lang_cs),
        Language("xh", R.string.lang_xh),
        Language("hr", R.string.lang_hr),
        Language("gom", R.string.lang_gom),
        Language("kmr", R.string.lang_kmr),
        Language("ckb", R.string.lang_ckb),
        Language("la", R.string.lang_la),
        Language("lv", R.string.lang_lv),
        Language("lt", R.string.lang_lt),
        Language("ln", R.string.lang_ln),
        Language("lb", R.string.lang_lb),
        Language("lmo", R.string.lang_lmo),
        Language("ro", R.string.lang_ro),
        Language("mg", R.string.lang_mg),
        Language("mt", R.string.lang_mt),
        Language("mr", R.string.lang_mr),
        Language("ml", R.string.lang_ml),
        Language("ms", R.string.lang_ms),
        Language("mk", R.string.lang_mk),
        Language("mai", R.string.lang_mai),
        Language("mi", R.string.lang_mi),
        Language("mn", R.string.lang_mn),
        Language("bn", R.string.lang_bn),
        Language("my", R.string.lang_my),
        Language("ne", R.string.lang_ne),
        Language("pa", R.string.lang_pa),
        Language("pt", R.string.lang_pt),
        Language("ps", R.string.lang_ps),
        Language("ja", R.string.lang_ja),
        Language("sv", R.string.lang_sv),
        Language("sr", R.string.lang_sr),
        Language("st", R.string.lang_st),
        Language("eo", R.string.lang_eo),
        Language("nb", R.string.lang_nb),
        Language("sk", R.string.lang_sk),
        Language("sl", R.string.lang_sl),
        Language("sw", R.string.lang_sw),
        Language("ceb", R.string.lang_ceb),
        Language("tg", R.string.lang_tg),
        Language("tl", R.string.lang_tl),
        Language("tt", R.string.lang_tt),
        Language("te", R.string.lang_te),
        Language("ta", R.string.lang_ta),
        Language("tr", R.string.lang_tr),
        Language("tk", R.string.lang_tk),
        Language("cy", R.string.lang_cy),
        Language("wo", R.string.lang_wo),
        Language("ur", R.string.lang_ur),
        Language("uk", R.string.lang_uk),
        Language("uz", R.string.lang_uz),
        Language("es", R.string.lang_es),
        Language("scn", R.string.lang_scn),
        Language("he", R.string.lang_he),
        Language("el", R.string.lang_el),
        Language("hu", R.string.lang_hu),
        Language("su", R.string.lang_su),
        Language("hy", R.string.lang_hy),
        Language("ace", R.string.lang_ace),
        Language("ig", R.string.lang_ig),
        Language("it", R.string.lang_it),
        Language("yi", R.string.lang_yi),
        Language("hi", R.string.lang_hi),
        Language("id", R.string.lang_id),
        Language("en", R.string.lang_en),
        Language("yue", R.string.lang_yue),
        ZH_CN,
        ZH_TW,
        Language("zu", R.string.lang_zu)
    )

    /** 按 code 反查名称（跟随当前 context locale）；找不到时返回 code 本身。 */
    fun nameOf(context: Context, code: String): String {
        val lang = ALL.firstOrNull { it.code.equals(code, ignoreCase = true) }
            ?: return languageDisplayName(code, context.resources.configuration.locales[0])
        return context.getString(lang.nameRes)
    }

    /** 按 code 查 Language 对象；找不到返回 [AUTO]。 */
    fun byCode(code: String): Language =
        ALL.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO

    /** 按 code/已解析的 name 子串模糊匹配，大小写不敏感。name 用当前 locale 解析。 */
    fun search(context: Context, query: String): List<Language> {
        val q = query.trim()
        if (q.isEmpty()) return ALL
        val lower = q.lowercase()
        return ALL.filter {
            context.getString(it.nameRes).contains(q, ignoreCase = true) ||
                it.code.lowercase().contains(lower)
        }
    }
}
