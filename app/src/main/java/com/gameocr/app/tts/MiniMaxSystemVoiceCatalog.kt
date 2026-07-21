package com.gameocr.app.tts

import java.util.Locale

data class MiniMaxSystemVoice(
    val language: String,
    val voiceId: String,
    val name: String,
) {
    internal val searchableText: String =
        "$language\n$name\n$voiceId".lowercase(Locale.ROOT)
}

internal const val MINIMAX_SYSTEM_VOICE_SOURCE_URL =
    "https://platform.minimaxi.com/docs/faq/system-voice-id"

internal val MINIMAX_SYSTEM_VOICES: List<MiniMaxSystemVoice> by lazy {
    MINIMAX_SYSTEM_VOICE_ROWS
        .lineSequence()
        .filter(String::isNotBlank)
        .map { row ->
            val columns = row.split('\t', limit = 3)
            require(columns.size == 3) { "Invalid MiniMax system voice row: $row" }
            MiniMaxSystemVoice(
                language = columns[0],
                voiceId = columns[1],
                name = columns[2],
            )
        }
        .toList()
}

internal fun searchMiniMaxSystemVoices(
    query: String,
    voices: List<MiniMaxSystemVoice> = MINIMAX_SYSTEM_VOICES,
): List<MiniMaxSystemVoice> {
    val terms = query
        .trim()
        .lowercase(Locale.ROOT)
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
    if (terms.isEmpty()) return voices

    return voices.filter { voice ->
        terms.all(voice.searchableText::contains)
    }
}

private const val MINIMAX_SYSTEM_VOICE_ROWS = """
中文 (普通话)	male-qn-qingse	青涩青年音色
中文 (普通话)	male-qn-jingying	精英青年音色
中文 (普通话)	male-qn-badao	霸道青年音色
中文 (普通话)	male-qn-daxuesheng	青年大学生音色
中文 (普通话)	female-shaonv	少女音色
中文 (普通话)	female-yujie	御姐音色
中文 (普通话)	female-chengshu	成熟女性音色
中文 (普通话)	female-tianmei	甜美女性音色
中文 (普通话)	male-qn-qingse-jingpin	青涩青年音色-beta
中文 (普通话)	male-qn-jingying-jingpin	精英青年音色-beta
中文 (普通话)	male-qn-badao-jingpin	霸道青年音色-beta
中文 (普通话)	male-qn-daxuesheng-jingpin	青年大学生音色-beta
中文 (普通话)	female-shaonv-jingpin	少女音色-beta
中文 (普通话)	female-yujie-jingpin	御姐音色-beta
中文 (普通话)	female-chengshu-jingpin	成熟女性音色-beta
中文 (普通话)	female-tianmei-jingpin	甜美女性音色-beta
中文 (普通话)	clever_boy	聪明男童
中文 (普通话)	cute_boy	可爱男童
中文 (普通话)	lovely_girl	萌萌女童
中文 (普通话)	cartoon_pig	卡通猪小琪
中文 (普通话)	bingjiao_didi	病娇弟弟
中文 (普通话)	junlang_nanyou	俊朗男友
中文 (普通话)	chunzhen_xuedi	纯真学弟
中文 (普通话)	lengdan_xiongzhang	冷淡学长
中文 (普通话)	badao_shaoye	霸道少爷
中文 (普通话)	tianxin_xiaoling	甜心小玲
中文 (普通话)	qiaopi_mengmei	俏皮萌妹
中文 (普通话)	wumei_yujie	妩媚御姐
中文 (普通话)	diadia_xuemei	嗲嗲学妹
中文 (普通话)	danya_xuejie	淡雅学姐
中文 (普通话)	Chinese (Mandarin)_Reliable_Executive	沉稳高管
中文 (普通话)	Chinese (Mandarin)_News_Anchor	新闻女声
中文 (普通话)	Chinese (Mandarin)_Mature_Woman	傲娇御姐
中文 (普通话)	Chinese (Mandarin)_Unrestrained_Young_Man	不羁青年
中文 (普通话)	Arrogant_Miss	嚣张小姐
中文 (普通话)	Robot_Armor	机械战甲
中文 (普通话)	Chinese (Mandarin)_Kind-hearted_Antie	热心大婶
中文 (普通话)	Chinese (Mandarin)_HK_Flight_Attendant	港普空姐
中文 (普通话)	Chinese (Mandarin)_Humorous_Elder	搞笑大爷
中文 (普通话)	Chinese (Mandarin)_Gentleman	温润男声
中文 (普通话)	Chinese (Mandarin)_Warm_Bestie	温暖闺蜜
中文 (普通话)	Chinese (Mandarin)_Male_Announcer	播报男声
中文 (普通话)	Chinese (Mandarin)_Sweet_Lady	甜美女声
中文 (普通话)	Chinese (Mandarin)_Southern_Young_Man	南方小哥
中文 (普通话)	Chinese (Mandarin)_Wise_Women	阅历姐姐
中文 (普通话)	Chinese (Mandarin)_Gentle_Youth	温润青年
中文 (普通话)	Chinese (Mandarin)_Warm_Girl	温暖少女
中文 (普通话)	Chinese (Mandarin)_Kind-hearted_Elder	花甲奶奶
中文 (普通话)	Chinese (Mandarin)_Cute_Spirit	憨憨萌兽
中文 (普通话)	Chinese (Mandarin)_Radio_Host	电台男主播
中文 (普通话)	Chinese (Mandarin)_Lyrical_Voice	抒情男声
中文 (普通话)	Chinese (Mandarin)_Straightforward_Boy	率真弟弟
中文 (普通话)	Chinese (Mandarin)_Sincere_Adult	真诚青年
中文 (普通话)	Chinese (Mandarin)_Gentle_Senior	温柔学姐
中文 (普通话)	Chinese (Mandarin)_Stubborn_Friend	嘴硬竹马
中文 (普通话)	Chinese (Mandarin)_Crisp_Girl	清脆少女
中文 (普通话)	Chinese (Mandarin)_Pure-hearted_Boy	清澈邻家弟弟
中文 (普通话)	Chinese (Mandarin)_Soft_Girl	柔和少女
中文 (粤语)	Cantonese_ProfessionalHost（F)	专业女主持
中文 (粤语)	Cantonese_GentleLady	温柔女声
中文 (粤语)	Cantonese_ProfessionalHost（M)	专业男主持
中文 (粤语)	Cantonese_PlayfulMan	活泼男声
中文 (粤语)	Cantonese_CuteGirl	可爱女孩
中文 (粤语)	Cantonese_KindWoman	善良女声
英文	Santa_Claus	Santa Claus
英文	Grinch	Grinch
英文	Rudolph	Rudolph
英文	Arnold	Arnold
英文	Charming_Santa	Charming Santa
英文	Charming_Lady	Charming Lady
英文	Sweet_Girl	Sweet Girl
英文	Cute_Elf	Cute Elf
英文	Attractive_Girl	Attractive Girl
英文	Serene_Woman	Serene Woman
英文	English_Trustworthy_Man	Trustworthy Man
英文	English_Graceful_Lady	Graceful Lady
英文	English_Aussie_Bloke	Aussie Bloke
英文	English_Whispering_girl	Whispering girl
英文	English_Diligent_Man	Diligent Man
英文	English_Gentle-voiced_man	Gentle-voiced man
日文	Japanese_IntellectualSenior	Intellectual Senior
日文	Japanese_DecisivePrincess	Decisive Princess
日文	Japanese_LoyalKnight	Loyal Knight
日文	Japanese_DominantMan	Dominant Man
日文	Japanese_SeriousCommander	Serious Commander
日文	Japanese_ColdQueen	Cold Queen
日文	Japanese_DependableWoman	Dependable Woman
日文	Japanese_GentleButler	Gentle Butler
日文	Japanese_KindLady	Kind Lady
日文	Japanese_CalmLady	Calm Lady
日文	Japanese_OptimisticYouth	Optimistic Youth
日文	Japanese_GenerousIzakayaOwner	Generous Izakaya Owner
日文	Japanese_SportyStudent	Sporty Student
日文	Japanese_InnocentBoy	Innocent Boy
日文	Japanese_GracefulMaiden	Graceful Maiden
韩文	Korean_SweetGirl	Sweet Girl
韩文	Korean_CheerfulBoyfriend	Cheerful Boyfriend
韩文	Korean_EnchantingSister	Enchanting Sister
韩文	Korean_ShyGirl	Shy Girl
韩文	Korean_ReliableSister	Reliable Sister
韩文	Korean_StrictBoss	Strict Boss
韩文	Korean_SassyGirl	Sassy Girl
韩文	Korean_ChildhoodFriendGirl	Childhood Friend Girl
韩文	Korean_PlayboyCharmer	Playboy Charmer
韩文	Korean_ElegantPrincess	Elegant Princess
韩文	Korean_BraveFemaleWarrior	Brave Female Warrior
韩文	Korean_BraveYouth	Brave Youth
韩文	Korean_CalmLady	Calm Lady
韩文	Korean_EnthusiasticTeen	Enthusiastic Teen
韩文	Korean_SoothingLady	Soothing Lady
韩文	Korean_IntellectualSenior	Intellectual Senior
韩文	Korean_LonelyWarrior	Lonely Warrior
韩文	Korean_MatureLady	Mature Lady
韩文	Korean_InnocentBoy	Innocent Boy
韩文	Korean_CharmingSister	Charming Sister
韩文	Korean_AthleticStudent	Athletic Student
韩文	Korean_BraveAdventurer	Brave Adventurer
韩文	Korean_CalmGentleman	Calm Gentleman
韩文	Korean_WiseElf	Wise Elf
韩文	Korean_CheerfulCoolJunior	Cheerful Cool Junior
韩文	Korean_DecisiveQueen	Decisive Queen
韩文	Korean_ColdYoungMan	Cold Young Man
韩文	Korean_MysteriousGirl	Mysterious Girl
韩文	Korean_QuirkyGirl	Quirky Girl
韩文	Korean_ConsiderateSenior	Considerate Senior
韩文	Korean_CheerfulLittleSister	Cheerful Little Sister
韩文	Korean_DominantMan	Dominant Man
韩文	Korean_AirheadedGirl	Airheaded Girl
韩文	Korean_ReliableYouth	Reliable Youth
韩文	Korean_FriendlyBigSister	Friendly Big Sister
韩文	Korean_GentleBoss	Gentle Boss
韩文	Korean_ColdGirl	Cold Girl
韩文	Korean_HaughtyLady	Haughty Lady
韩文	Korean_CharmingElderSister	Charming Elder Sister
韩文	Korean_IntellectualMan	Intellectual Man
韩文	Korean_CaringWoman	Caring Woman
韩文	Korean_WiseTeacher	Wise Teacher
韩文	Korean_ConfidentBoss	Confident Boss
韩文	Korean_AthleticGirl	Athletic Girl
韩文	Korean_PossessiveMan	Possessive Man
韩文	Korean_GentleWoman	Gentle Woman
韩文	Korean_CockyGuy	Cocky Guy
韩文	Korean_ThoughtfulWoman	Thoughtful Woman
韩文	Korean_OptimisticYouth	Optimistic Youth
西班牙文	Spanish_SereneWoman	Serene Woman
西班牙文	Spanish_MaturePartner	Mature Partner
西班牙文	Spanish_CaptivatingStoryteller	Captivating Storyteller
西班牙文	Spanish_Narrator	Narrator
西班牙文	Spanish_WiseScholar	Wise Scholar
西班牙文	Spanish_Kind-heartedGirl	Kind-hearted Girl
西班牙文	Spanish_DeterminedManager	Determined Manager
西班牙文	Spanish_BossyLeader	Bossy Leader
西班牙文	Spanish_ReservedYoungMan	Reserved Young Man
西班牙文	Spanish_ConfidentWoman	Confident Woman
西班牙文	Spanish_ThoughtfulMan	Thoughtful Man
西班牙文	Spanish_Strong-WilledBoy	Strong-willed Boy
西班牙文	Spanish_SophisticatedLady	Sophisticated Lady
西班牙文	Spanish_RationalMan	Rational Man
西班牙文	Spanish_AnimeCharacter	Anime Character
西班牙文	Spanish_Deep-tonedMan	Deep-toned Man
西班牙文	Spanish_Fussyhostess	Fussy hostess
西班牙文	Spanish_SincereTeen	Sincere Teen
西班牙文	Spanish_FrankLady	Frank Lady
西班牙文	Spanish_Comedian	Comedian
西班牙文	Spanish_Debator	Debator
西班牙文	Spanish_ToughBoss	Tough Boss
西班牙文	Spanish_Wiselady	Wise Lady
西班牙文	Spanish_Steadymentor	Steady Mentor
西班牙文	Spanish_Jovialman	Jovial Man
西班牙文	Spanish_SantaClaus	Santa Claus
西班牙文	Spanish_Rudolph	Rudolph
西班牙文	Spanish_Intonategirl	Intonate Girl
西班牙文	Spanish_Arnold	Arnold
西班牙文	Spanish_Ghost	Ghost
西班牙文	Spanish_HumorousElder	Humorous Elder
西班牙文	Spanish_EnergeticBoy	Energetic Boy
西班牙文	Spanish_WhimsicalGirl	Whimsical Girl
西班牙文	Spanish_StrictBoss	Strict Boss
西班牙文	Spanish_ReliableMan	Reliable Man
西班牙文	Spanish_SereneElder	Serene Elder
西班牙文	Spanish_AngryMan	Angry Man
西班牙文	Spanish_AssertiveQueen	Assertive Queen
西班牙文	Spanish_CaringGirlfriend	Caring Girlfriend
西班牙文	Spanish_PowerfulSoldier	Powerful Soldier
西班牙文	Spanish_PassionateWarrior	Passionate Warrior
西班牙文	Spanish_ChattyGirl	Chatty Girl
西班牙文	Spanish_RomanticHusband	Romantic Husband
西班牙文	Spanish_CompellingGirl	Compelling Girl
西班牙文	Spanish_PowerfulVeteran	Powerful Veteran
西班牙文	Spanish_SensibleManager	Sensible Manager
西班牙文	Spanish_ThoughtfulLady	Thoughtful Lady
葡萄牙文	Portuguese_SentimentalLady	Sentimental Lady
葡萄牙文	Portuguese_BossyLeader	Bossy Leader
葡萄牙文	Portuguese_Wiselady	Wise lady
葡萄牙文	Portuguese_Strong-WilledBoy	Strong-willed Boy
葡萄牙文	Portuguese_Deep-VoicedGentleman	Deep-voiced Gentleman
葡萄牙文	Portuguese_UpsetGirl	Upset Girl
葡萄牙文	Portuguese_PassionateWarrior	Passionate Warrior
葡萄牙文	Portuguese_AnimeCharacter	Anime Character
葡萄牙文	Portuguese_ConfidentWoman	Confident Woman
葡萄牙文	Portuguese_AngryMan	Angry Man
葡萄牙文	Portuguese_CaptivatingStoryteller	Captivating Storyteller
葡萄牙文	Portuguese_Godfather	Godfather
葡萄牙文	Portuguese_ReservedYoungMan	Reserved Young Man
葡萄牙文	Portuguese_SmartYoungGirl	Smart Young Girl
葡萄牙文	Portuguese_Kind-heartedGirl	Kind-hearted Girl
葡萄牙文	Portuguese_Pompouslady	Pompous lady
葡萄牙文	Portuguese_Grinch	Grinch
葡萄牙文	Portuguese_Debator	Debator
葡萄牙文	Portuguese_SweetGirl	Sweet Girl
葡萄牙文	Portuguese_AttractiveGirl	Attractive Girl
葡萄牙文	Portuguese_ThoughtfulMan	Thoughtful Man
葡萄牙文	Portuguese_PlayfulGirl	Playful Girl
葡萄牙文	Portuguese_GorgeousLady	Gorgeous Lady
葡萄牙文	Portuguese_LovelyLady	Lovely Lady
葡萄牙文	Portuguese_SereneWoman	Serene Woman
葡萄牙文	Portuguese_SadTeen	Sad Teen
葡萄牙文	Portuguese_MaturePartner	Mature Partner
葡萄牙文	Portuguese_Comedian	Comedian
葡萄牙文	Portuguese_NaughtySchoolgirl	Naughty Schoolgirl
葡萄牙文	Portuguese_Narrator	Narrator
葡萄牙文	Portuguese_ToughBoss	Tough Boss
葡萄牙文	Portuguese_Fussyhostess	Fussy hostess
葡萄牙文	Portuguese_Dramatist	Dramatist
葡萄牙文	Portuguese_Steadymentor	Steady Mentor
葡萄牙文	Portuguese_Jovialman	Jovial Man
葡萄牙文	Portuguese_CharmingQueen	Charming Queen
葡萄牙文	Portuguese_SantaClaus	Santa Claus
葡萄牙文	Portuguese_Rudolph	Rudolph
葡萄牙文	Portuguese_Arnold	Arnold
葡萄牙文	Portuguese_CharmingSanta	Charming Santa
葡萄牙文	Portuguese_CharmingLady	Charming Lady
葡萄牙文	Portuguese_Ghost	Ghost
葡萄牙文	Portuguese_HumorousElder	Humorous Elder
葡萄牙文	Portuguese_CalmLeader	Calm Leader
葡萄牙文	Portuguese_GentleTeacher	Gentle Teacher
葡萄牙文	Portuguese_EnergeticBoy	Energetic Boy
葡萄牙文	Portuguese_ReliableMan	Reliable Man
葡萄牙文	Portuguese_SereneElder	Serene Elder
葡萄牙文	Portuguese_GrimReaper	Grim Reaper
葡萄牙文	Portuguese_AssertiveQueen	Assertive Queen
葡萄牙文	Portuguese_WhimsicalGirl	Whimsical Girl
葡萄牙文	Portuguese_StressedLady	Stressed Lady
葡萄牙文	Portuguese_FriendlyNeighbor	Friendly Neighbor
葡萄牙文	Portuguese_CaringGirlfriend	Caring Girlfriend
葡萄牙文	Portuguese_PowerfulSoldier	Powerful Soldier
葡萄牙文	Portuguese_FascinatingBoy	Fascinating Boy
葡萄牙文	Portuguese_RomanticHusband	Romantic Husband
葡萄牙文	Portuguese_StrictBoss	Strict Boss
葡萄牙文	Portuguese_InspiringLady	Inspiring Lady
葡萄牙文	Portuguese_PlayfulSpirit	Playful Spirit
葡萄牙文	Portuguese_ElegantGirl	Elegant Girl
葡萄牙文	Portuguese_CompellingGirl	Compelling Girl
葡萄牙文	Portuguese_PowerfulVeteran	Powerful Veteran
葡萄牙文	Portuguese_SensibleManager	Sensible Manager
葡萄牙文	Portuguese_ThoughtfulLady	Thoughtful Lady
葡萄牙文	Portuguese_TheatricalActor	Theatrical Actor
葡萄牙文	Portuguese_FragileBoy	Fragile Boy
葡萄牙文	Portuguese_ChattyGirl	Chatty Girl
葡萄牙文	Portuguese_Conscientiousinstructor	Conscientious Instructor
葡萄牙文	Portuguese_RationalMan	Rational Man
葡萄牙文	Portuguese_WiseScholar	Wise Scholar
葡萄牙文	Portuguese_FrankLady	Frank Lady
葡萄牙文	Portuguese_DeterminedManager	Determined Manager
法文	French_Male_Speech_New	Level-Headed Man
法文	French_Female_News Anchor	Patient Female Presenter
法文	French_CasualMan	Casual Man
法文	French_MovieLeadFemale	Movie Lead Female
法文	French_FemaleAnchor	Female Anchor
法文	French_MaleNarrator	Male Narrator
印尼文	Indonesian_SweetGirl	Sweet Girl
印尼文	Indonesian_ReservedYoungMan	Reserved Young Man
印尼文	Indonesian_CharmingGirl	Charming Girl
印尼文	Indonesian_CalmWoman	Calm Woman
印尼文	Indonesian_ConfidentWoman	Confident Woman
印尼文	Indonesian_CaringMan	Caring Man
印尼文	Indonesian_BossyLeader	Bossy Leader
印尼文	Indonesian_DeterminedBoy	Determined Boy
印尼文	Indonesian_GentleGirl	Gentle Girl
德文	German_FriendlyMan	Friendly Man
德文	German_SweetLady	Sweet Lady
德文	German_PlayfulMan	Playful Man
俄文	Russian_HandsomeChildhoodFriend	Handsome Childhood Friend
俄文	Russian_BrightHeroine	Bright Queen
俄文	Russian_AmbitiousWoman	Ambitious Woman
俄文	Russian_ReliableMan	Reliable Man
俄文	Russian_CrazyQueen	Crazy Girl
俄文	Russian_PessimisticGirl	Pessimistic Girl
俄文	Russian_AttractiveGuy	Attractive Guy
俄文	Russian_Bad-temperedBoy	Bad-tempered Boy
意大利文	Italian_BraveHeroine	Brave Heroine
意大利文	Italian_Narrator	Narrator
意大利文	Italian_WanderingSorcerer	Wandering Sorcerer
意大利文	Italian_DiligentLeader	Diligent Leader
阿拉伯文	Arabic_CalmWoman	Calm Woman
阿拉伯文	Arabic_FriendlyGuy	Friendly Guy
土耳其文	Turkish_CalmWoman	Calm Woman
土耳其文	Turkish_Trustworthyman	Trustworthy man
乌克兰文	Ukrainian_CalmWoman	Calm Woman
乌克兰文	Ukrainian_WiseScholar	Wise Scholar
荷兰文	Dutch_kindhearted_girl	Kind-hearted girl
荷兰文	Dutch_bossy_leader	Bossy leader
越南文	Vietnamese_kindhearted_girl	Kind-hearted girl
泰文	Thai_male_1_sample8	Serene Man
泰文	Thai_male_2_sample2	Friendly Man
泰文	Thai_female_1_sample1	Confident Woman
泰文	Thai_female_2_sample2	Energetic Woman
波兰文	Polish_male_1_sample4	Male Narrator
波兰文	Polish_male_2_sample3	Male Anchor
波兰文	Polish_female_1_sample1	Calm Woman
波兰文	Polish_female_2_sample3	Casual Woman
罗马尼亚文	Romanian_male_1_sample2	Reliable Man
罗马尼亚文	Romanian_male_2_sample1	Energetic Youth
罗马尼亚文	Romanian_female_1_sample4	Optimistic Youth
罗马尼亚文	Romanian_female_2_sample1	Gentle Woman
希腊文	greek_male_1a_v1	Thoughtful Mentor
希腊文	Greek_female_1_sample1	Gentle Lady
希腊文	Greek_female_2_sample3	Girl Next Door
捷克文	czech_male_1_v1	Assured Presenter
捷克文	czech_female_5_v7	Steadfast Narrator
捷克文	czech_female_2_v2	Elegant Lady
芬兰文	finnish_male_3_v1	Upbeat Man
芬兰文	finnish_male_1_v2	Friendly Boy
芬兰文	finnish_female_4_v1	Assetive Woman
印地文	hindi_male_1_v2	Trustworthy Advisor
印地文	hindi_female_2_v1	Tranquil Woman
印地文	hindi_female_1_v2	News Anchor
"""
