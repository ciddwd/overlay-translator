package com.gameocr.app.translate

import com.gameocr.app.data.NiuTransMode

/**
 * Versioned copy of the language-code tables published in the NiuTrans v2 documentation.
 *
 * The service uses mostly ISO language codes, but also exposes a few provider-specific values
 * such as `cht`, `pt-BR`, and Pro's `mo`. Keep the complete server catalog here rather than
 * scattering a small, lossy language `when` across request builders.
 */
internal object NiuTransLanguageCatalog {
    private val flashCanonicalCodes: Map<String, String> =
        """sq ar am acu agr ake amu az ga et ee ojb om or os ifb aym knj ify acr amk bdu adh any cpb efi ach ish bin alz quy oc ast an aa arq ab tpi bsn ba eu be mww ber bg is bi bem pl bs fa pot br poh bam map bba bus bqp bnp bch bno bqj bdh ptu bfa cbl gbo bas bum pag bci bhw btx pon bzj gug ncj pt-BR pam nso se cha cv tn ts che ccp cdf tsc chw tt da de tet dv dik dyu tbz mps tih duo ada dua tdt dhv tiv bbc zai nds toki ru djk enx nzi nij nyn ndc ndo fr fo fil fj fi cfm gur kea fon fur frp sa km quw kg fy jy gu gub gof xsm krs guw swc gym gn kl plt ang ka kk ht ko ha nl me cnh hui hlb her hup ky quc gbi gl ca cs gil kac gaa kik kmb cab fr-CA kab cjp cak kn kek cni cop kbh co otq hr ku ckb ksd quz kpg crh xal kbo keo cki pss kle qxr rar kbp kam kqn wes kua tlh kr kw csb rw la lv lo rn lt ln lg dop lb ro rmn ngl rug lsi ond loz lua lub lun rnd lue li jbo mg mt gv mr ml ms mhr mam mk mi mo mn my bn mni meu mah mrw mdy mad mos muv lus mfe umb arn mxv vmw bts mgr pdt mwl mai crp nhg af xh zu ne no azb quh lnd fuv nop ntm nyy niu nia nba nyu nav nyk pcm nr pap pck pa pt ps ata pis top ny tw chr chq cas cjk cce chk qug hne ja sv sm sr crs st sg si mrj eo jiv sk sl sw gd so swp ssx spy huv jmc srm sxn seh kwy sop tzo ksw sco nb sc shn sh ss hsb tg ty te ta th to tig tmh tr tk tpm ctd tyv iou tex lcm teo tvl tll tgl tum toj ttj wal war ve wol udm ur uk uz ppk usp wlx prk wsk wrs vun cy wls urh mau guc wa es he shi el haw sd hu sn ceb syc hwc hmo lcp sid mbb shp ssd gnw kyu hil nn dsb lfn ie hy jac ace ig it yi hi su id jv en yua yo vi yue ikk izz pil jae yon zyb byr iso iba ilo ibg yap qvi io ia dje zh cht dz ifa czt dtp bcl tzh zne ncx nch frm"""
            .splitToCanonicalMap()

    private val proCanonicalCodes: Map<String, String> =
        """zh cht en fr es pt ja tr ru ar ko th it de vi ms id fil hi pl cs nl km my fa gu ur te mr he bn ta uk ti kk mo yue"""
            .splitToCanonicalMap()

    val flashCodes: Set<String> get() = flashCanonicalCodes.values.toSet()
    val proCodes: Set<String> get() = proCanonicalCodes.values.toSet()

    fun mapSource(code: String, mode: NiuTransMode): String? =
        map(code = code, mode = mode, allowAuto = true)

    fun mapTarget(code: String, mode: NiuTransMode): String? =
        map(code = code, mode = mode, allowAuto = false)

    private fun map(code: String, mode: NiuTransMode, allowAuto: Boolean): String? {
        val raw = code.trim()
        if (raw.isEmpty()) return null
        if (raw.equals("auto", ignoreCase = true)) return "auto".takeIf { allowAuto }

        val normalized = raw.lowercase()
        val providerCode = when (normalized) {
            "zh", "zh-cn", "zh-hans", "zh-sg" -> "zh"
            "zh-tw", "zh-hant", "zh-hk", "zh-mo" -> "cht"
            "jp" -> "ja"
            "kor" -> "ko"
            "tl" -> "fil"
            "bo" -> "ti"
            "mn" -> if (mode == NiuTransMode.PRO) "mo" else "mn"
            else -> normalized
        }
        val catalog = when (mode) {
            NiuTransMode.FLASH -> flashCanonicalCodes
            NiuTransMode.PRO -> proCanonicalCodes
        }
        catalog[providerCode]?.let { return it }
        val primary = providerCode.substringBefore('-')
        return catalog[primary]
    }

    private fun String.splitToCanonicalMap(): Map<String, String> =
        trim().split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .associateBy(String::lowercase)
}
