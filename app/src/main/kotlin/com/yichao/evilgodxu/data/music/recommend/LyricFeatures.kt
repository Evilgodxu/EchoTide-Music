package com.yichao.evilgodxu.data.music.recommend

import kotlin.math.sqrt

/**
 * 歌词文本特征提取：词面分词、跨语概念映射与结构代理特征。
 *
 * 推荐算法的三个通道在此取原始特征，打分与排序在 [MusicRecommender] 中完成。
 *
 * 覆盖语言：中（简/繁）、英、日、韩、德、俄。各语言的书写系统决定了分词方式 ——
 * 有空格分词的（英/德/俄）整词成词元，中日按 2-gram 切分，谚文整段保留。
 */
internal object LyricFeatures {

    private val STOP_LATIN = setOf(
        "a", "an", "the", "i", "me", "my", "you", "your", "we", "us", "our", "he", "she", "it",
        "they", "them", "this", "that", "these", "those", "is", "am", "are", "was", "were", "be",
        "been", "being", "do", "does", "did", "done", "have", "has", "had", "having", "will",
        "would", "can", "could", "should", "may", "might", "must", "not", "no", "nor", "but",
        "and", "or", "so", "if", "then", "than", "too", "very", "just", "now", "up", "down",
        "out", "off", "over", "under", "again", "more", "most", "some", "any", "all", "both",
        "each", "few", "other", "own", "same", "s", "t", "don", "ll", "re", "ve", "d", "m",
        "ooh", "oh", "ah", "yeah", "yea", "la", "da", "di", "du", "na", "noh", "hmm", "mhm",
        "uh", "em", "hey", "okay", "ok", "gonna", "wanna", "ain", "im", "ive", "dont", "cant",
        "wont",
    )

    private val STOP_CN = setOf(
        '的', '了', '是', '在', '我', '你', '他', '她', '它', '们', '这', '那', '有', '就', '都',
        '也', '还', '又', '很', '太', '再', '要', '会', '能', '说', '着', '过', '把', '被', '让',
        '给', '对', '从', '到', '和', '与', '或', '而', '但', '因', '所', '吗', '呢', '吧', '啊',
        '呀', '哦', '嗯',
    )

    /**
     * 日语助词与助动词专用假名。
     *
     * 中文按「词元含任一停用字即丢弃」处理，日语不能照搬 —— 假名字符表极小，
     * 逐字过滤会把「なみだ」「こと」这类实词一并杀掉。故仅在整段二元组全部由这些字符
     * 组成时才丢弃（如「のっ」「って」），含实词字符的组合一律保留。
     */
    private val STOP_KANA = setOf(
        'の', 'は', 'が', 'を', 'に', 'へ', 'と', 'で', 'て', 'た', 'だ', 'な', 'し', 'れ', 'ら',
        'り', 'る', 'も', 'か', 'よ', 'ね', 'さ', 'ん', 'っ', 'ー', 'や', 'ぞ', 'ぜ', 'ず', 'わ',
    )

    // 德语冠词、连词、代词：德语有空格分词，按词表过滤即可，无需形态处理
    private val STOP_DE = setOf(
        "der", "die", "das", "den", "dem", "des", "und", "ist", "ich", "du", "nicht", "es", "sie",
        "er", "wir", "ihr", "mit", "für", "auf", "von", "zu", "im", "in", "ein", "eine", "einen",
        "mir", "sich", "uns", "euch", "als", "auch", "aber", "wenn", "wie", "was", "wer", "wo",
        "so", "noch", "nur", "schon", "sehr", "kann", "will", "muss", "soll", "war", "bin", "bist",
        "sind", "seid", "haben", "hat", "hatte", "werde", "wird", "wurde", "über", "unter", "vor",
        "nach", "bei", "aus", "durch", "gegen", "ohne", "um", "ja", "nein", "mein", "dein",
    )

    // 俄语虚词与代词：常用词形逐格列入 —— 俄语每个词都可能带格后缀，漏形即漏过滤
    private val STOP_RU = setOf(
        "и", "в", "во", "не", "на", "я", "что", "он", "с", "со", "как", "а", "то", "все", "она",
        "так", "его", "но", "да", "ты", "к", "у", "же", "вы", "за", "бы", "по", "только", "ее",
        "мне", "было", "вот", "от", "меня", "еще", "нет", "о", "из", "ему", "теперь", "когда",
        "даже", "ну", "вдруг", "ли", "если", "уже", "или", "ни", "быть", "был", "была", "были",
        "него", "до", "вас", "вам", "ведь", "там", "потом", "себя", "ничего", "ей", "может",
        "они", "тут", "где", "есть", "надо", "ней", "для", "мы", "тебя", "их", "чем", "сам",
        "чтоб", "без", "будто", "чего", "раз", "тоже", "себе", "под", "будет", "тогда", "кто",
        "этот", "того", "потому", "этого", "какой", "совсем", "ним", "здесь", "этом", "один",
        "почти", "мой", "тем", "чтобы", "нее", "куда", "зачем", "всех", "никогда", "можно",
        "при", "об", "другой", "хоть", "после", "над", "больше", "тот", "через", "эти", "нас",
        "про", "какая", "много", "разве", "эту", "моя", "хорошо", "свою", "этой", "перед",
    )

    // 拉丁词形扩展到拉丁补充区与扩展区，覆盖德语 ä ö ü ß 及其它欧洲变音字母；
    // 撇号同时收 ASCII 与排版撇号，否则「don’t」这类会被切成两段
    private val LATIN_RE = Regex("[A-Za-z\\u00C0-\\u024F][A-Za-z\\u00C0-\\u024F'’]*")
    // 西里尔字母（俄语）：有空格分词，整词即一个词元
    private val CYRILLIC_RE = Regex("[\\u0400-\\u04FF]+")
    private val HAN_RE = Regex("[\\u4e00-\\u9fff]+")
    private val HANGUL_RE = Regex("[\\uac00-\\ud7a3]+")

    // 平假名与片假名（含长音符）的连续段
    private val KANA_RE = Regex("[\\u3040-\\u30ff]+")

    // 概念词林中可作为独立词条匹配的字形：汉字（含繁体与日文汉字）与假名
    private val LEXICON_WORD_RE = Regex("[\\u4e00-\\u9fff\\u3040-\\u30ff]+")

    /**
     * 多语概念词林：把「伤心 / 难过 / 悲しい / itami / pain / Schmerz / боль」这类跨语言近义表达映射到同一语义槽。
     *
     * 词面相似度在跨语言时交集恒为空（日文歌词与中文歌词毫无共同词元），
     * 概念槽是这类组合唯一可用的召回通道，故词条覆盖尽量多的字形：
     * 简体、繁体、日文汉字与假名、英文与罗马音 —— 平台歌词接口给的是各语言原文，
     * 只收简体与罗马音会让日文、繁体、德语、俄语歌在概念通道上同样落空。
     *
     * 无法用整词精确匹配的语言（谚文、德语、俄语）另见 [SUBSTRING_CONCEPTS]。
     */
    private val CONCEPT_LEXICON: Map<String, List<String>> = mapOf(
        "love" to listOf("爱", "愛", "相爱", "相愛", "爱过", "愛過", "爱情", "愛情", "爱着", "愛著", "爱上", "愛上", "恋", "戀", "心动", "心動", "喜欢", "喜歡", "love", "loved", "loving", "ai", "koi", "sarang", "あい", "こい", "すき"),
        "heart" to listOf("心", "心里", "心裡", "心中", "心跳", "心疼", "真心", "心事", "胸", "heart", "mune", "kokoro", "こころ", "むね"),
        "you" to listOf("你", "君", "kimi", "anata", "boku", "no", "きみ", "あなた", "ぼく", "おまえ"),
        "night" to listOf("夜", "夜晚", "深夜", "夜色", "夜里", "夜裡", "黑夜", "黒夜", "黑暗", "晚", "night", "yoru", "yami", "dark", "よる", "やみ", "まよなか"),
        "light" to listOf("光", "光芒", "光晕", "光暈", "光辉", "光輝", "星光", "亮", "光照", "耀眼", "light", "hikari", "hikaru", "shine", "glow", "ひかり", "ひかる", "かがやき"),
        "dream" to listOf("梦", "夢", "梦想", "夢想", "梦境", "夢境", "梦到", "梦里", "夢裡", "dream", "yume", "kumu", "ゆめ"),
        "wind" to listOf("风", "風", "晚风", "晚風", "微风", "微風", "吹风", "吹風", "风儿", "風兒", "wind", "kaze", "breeze", "かぜ"),
        "sky" to listOf("天空", "天上", "天", "天空下", "sky", "sora", "そら", "青空", "夜空"),
        "star" to listOf("星", "星星", "繁星", "星光", "流星", "star", "hoshi", "byul", "ほし", "ながれぼし"),
        "tears" to listOf("泪", "淚", "眼泪", "眼淚", "泪水", "淚水", "哭", "哭泣", "流泪", "流淚", "哭过", "哭過", "涙", "tears", "cry", "namida", "なみだ", "なく", "ないて"),
        "pain" to listOf("痛", "疼痛", "痛苦", "伤人", "傷人", "伤", "傷", "疼", "心痛", "pain", "hurts", "itami", "いたい", "いたみ", "きず"),
        "memory" to listOf("记忆", "記憶", "回忆", "回憶", "记得", "記得", "纪念", "紀念", "想起", "过往", "過往", "memory", "kioku", "omoide", "きおく", "おもいで"),
        "time" to listOf("时间", "時間", "时光", "時光", "岁月", "歲月", "时候", "時候", "年", "日", "time", "toki", "jikan", "とき", "じかん"),
        "lonely" to listOf("孤独", "孤獨", "寂寞", "一个人", "一個人", "孤单", "孤單", "冷落", "无人", "無人", "lonely", "alone", "sabishii", "kodoku", "ひとり", "さみしい", "こどく"),
        "warmth" to listOf("温柔", "溫柔", "温暖", "溫暖", "暖", "温热", "溫熱", "暖阳", "暖陽", "warm", "tenderness", "atatakai", "nukumori", "ongi", "やさしさ", "ぬくもり", "あたたかい"),
        "lie" to listOf("谎言", "謊言", "骗", "騙", "假装", "假裝", "虚伪", "虛偽", "谎", "謊", "lie", "uso", "itsuwari", "pretend", "fake", "うそ"),
        "goodbye" to listOf("告别", "告別", "再见", "再見", "分手", "离开", "離開", "走吧", "散", "goodbye", "sayonara", "wakare", "さよなら", "わかれ", "ばいばい"),
        "wait" to listOf("等待", "等候", "等", "守望", "等着", "等著", "wait", "matsu", "まつ", "まって"),
        "summer" to listOf("夏天", "夏", "夏日", "炎热", "炎熱", "初夏", "summer", "natsu", "なつ"),
        "heat" to listOf("热", "熱", "热浪", "熱浪", "温度", "溫度", "heat", "neppa", "atsui", "あつい"),
        "rain" to listOf("雨", "下雨", "雨天", "烟雨", "煙雨", "雨滴", "rain", "ame", "あめ"),
        "sea" to listOf("海", "海洋", "沙滩", "沙灘", "海面", "sea", "umi", "うみ"),
        "cloud" to listOf("云", "雲", "云层", "雲層", "乌云", "烏雲", "cloud", "kumo", "くも"),
        "road" to listOf("路", "道路", "路上", "路途", "方向", "road", "michi", "みち"),
        "distance" to listOf("遥远", "遙遠", "距离", "距離", "远", "遠", "分隔", "distance", "tooi", "kyori", "とおい", "きょり"),
        "breath" to listOf("呼吸", "气息", "氣息", "窒息", "breath", "iki", "kokyu", "いき", "こきゅう"),
        "fall" to listOf("坠落", "墜落", "下坠", "下墜", "掉落", "跌", "fall", "falling", "ochiru", "おちる"),
        "hope" to listOf("希望", "期待", "盼望", "hope", "kibou", "nozomi", "きぼう", "のぞみ"),
        "happiness" to listOf("幸福", "快乐", "快樂", "开心", "開心", "愉快", "甜", "happiness", "happy", "shiawase", "kofuku", "しあわせ", "こうふく"),
        "future" to listOf("未来", "未來", "以后", "以後", "明天", "将来", "將來", "前方", "future", "mirai", "ashita", "みらい", "あした"),
        "salvation" to listOf("救", "救赎", "救贖", "解脱", "解脫", "拯救", "安慰", "salvation", "sukui", "sukuu", "すくい", "すくう"),
        "fragile" to listOf("脆弱", "易碎", "碎", "破碎", "fragile", "moroi", "koware", "もろい", "こわれ"),
        "eternity" to listOf("永远", "永遠", "一辈子", "一輩子", "永恒", "永恆", "一直", "forever", "eternity", "eien", "itsumade", "ずっと", "えいえん"),
        "world" to listOf("世界", "人间", "人間", "世间", "世間", "人世", "world", "sekai", "せかい"),
        "silence" to listOf("沉默", "安静", "安靜", "静", "靜", "无声", "無聲", "一言不发", "一言不發", "silence", "chinmoku", "shizuka", "しずか", "ちんもく"),
        "voice" to listOf("声音", "聲音", "声", "聲", "歌", "歌唱", "旋律", "歌单", "歌單", "唱", "voice", "koe", "uta", "こえ", "うた"),
        "fire" to listOf("火", "烟火", "煙火", "火焰", "燃烧", "燃燒", "轰烈", "轟烈", "fire", "honoo", "ほのお"),
        "flower" to listOf("花", "花开", "花開", "落花", "花香", "花儿", "花兒", "flower", "hana", "はな"),
        "shadow" to listOf("影", "影子", "阴影", "陰影", "倒影", "shadow", "kage", "かげ"),
        "numb" to listOf("麻木", "麻痹", "麻痺", "麻醉", "无感", "無感", "numb", "shibireteru", "しびれ"),
        "sand" to listOf("砂", "沙", "沙滩", "沙灘", "黄沙", "黃沙", "sand", "suna", "すな"),
        "wave" to listOf("波", "浪潮", "波浪", "滚动", "滾動", "wave", "nami", "なみ"),
        "eyes" to listOf("眼", "眼睛", "目光", "眉眼", "眼底", "瞳", "eyes", "ひとみ", "まなざし"),
        "hands" to listOf("手", "握住", "牵手", "牽手", "手掌", "抱紧", "抱緊", "hands", "だきしめ", "にぎる"),
        "universe" to listOf("宇宙", "银河", "銀河", "星空", "星球", "黑洞", "universe", "galaxy", "uchu", "ginga", "うちゅう", "ぎんが"),
        "morning" to listOf("晨曦", "早晨", "清晨", "黎明", "morning", "asa", "あさ", "よあけ"),
        "mistake" to listOf("错", "錯", "过错", "過錯", "错误", "錯誤", "罪过", "罪過", "mistake", "machigai", "まちがい"),
        "regret" to listOf("后悔", "後悔", "遗憾", "遺憾", "懊悔", "忏悔", "懺悔", "可惜", "regret", "koukai", "こうかい"),
        "mess" to listOf("混乱", "混亂", "一团乱", "一團亂", "混帐", "混帳", "复杂", "複雜", "mess", "konran"),
        "friend" to listOf("朋友", "陪伴", "陪", "陪着", "陪著", "伙伴", "friend", "tomo", "とも", "なかま"),
        "freedom" to listOf("自由", "解放", "无拘", "無拘", "freedom", "jiyuu", "じゆう"),
        "promise" to listOf("约定", "約定", "承诺", "承諾", "许诺", "許諾", "promise", "yakusoku", "やくそく"),
        "believe" to listOf("相信", "信任", "信仰", "信", "believe", "shinjiru", "しんじる"),
        "lose" to listOf("失去", "丢", "丟", "丢失", "丟失", "遗失", "遺失", "错过", "錯過", "lose", "ushinau", "うしなう"),
        "continue" to listOf("继续", "繼續", "不停", "延续", "延續", "continue", "tsuzuiteku", "tsuzuku", "つづく"),
        "new" to listOf("新", "新的", "重新", "全新", "new", "atarashii", "あたらしい"),
        "end" to listOf("结束", "結束", "完结", "完結", "终点", "終點", "终", "終", "end", "owara", "owaru", "おわり", "おわる"),
        "soul" to listOf("灵魂", "靈魂", "命运", "命運", "宿命", "意识", "意識", "心意", "soul", "tamashii", "ishiki", "omoi", "unmei", "たましい", "いしき", "うんめい"),
        "cold" to listOf("冷", "冰冷", "冷漠", "寒", "冷却", "冷卻", "cold", "samui", "tsumetai", "さむい", "つめたい"),
        "city" to listOf("城市", "街巷", "霓虹", "街", "city", "machi", "まち"),
        "sleep" to listOf("睡", "睡着", "睡著", "失眠", "梦醒", "夢醒", "醒", "睡不着", "睡不著", "sleep", "nemuru", "samete", "ねむる", "さめて"),
        "glass" to listOf("琉璃", "玻璃", "镜子", "鏡子", "透明", "晶莹", "晶瑩", "glass", "mirror", "kagami", "かがみ", "ガラス"),
    )

    /**
     * 按子串命中的概念词条：谚文、德语、俄语都存不住「整词」这个前提。
     *
     * - 谚文无空格分词，且词形随助词变化，整词匹配覆盖不到；
     * - 德语靠复合构词（Herzschlag 含 Herz、Sonnenuntergang 含 Sonne），整词匹配基本落空；
     * - 俄语每个名词、形容词、动词都按格与时态变形（любовь / любви / любить），
     *   所以存的是词干（любов、любл、люби）而不是词。
     *
     * 代价是词干短时有误命中（「нов」也会命中 «снова»），但这是该类语言唯一可用的召回通道，
     * 噪声由 IDF 与三通道权重稀释。
     *
     * 取词干时须避开跨语言碰撞：子串匹配不分语种，德语词干若与英文高频词形重叠会大面积误命中 ——
     * `end` 会被 friend / send / spend / weekend 命中，`still` 会被英文副词命中，
     * 故德语取 `ende` / `endet` / `stille` 这类不会跨语碰撞的形式。
     */
    private val SUBSTRING_CONCEPTS: Map<String, String> = mapOf(
        // ---- 谚文 ----
        "사랑" to "love", "별" to "star", "빛" to "light", "꿈" to "dream", "밤" to "night",
        "시간" to "time", "기억" to "memory", "운명" to "promise", "영원" to "eternity",
        "눈물" to "tears", "아픔" to "pain", "마음" to "heart", "세상" to "world",
        "그대" to "you", "너" to "you", "우리" to "friend", "추억" to "memory",
        "처음" to "new", "다시" to "continue", "무서워" to "lonely", "따뜻" to "warmth",
        "안아" to "hands", "숨" to "breath", "기다" to "wait",
        // ---- 德语词干 ----
        "lieb" to "love", "herz" to "heart", "brust" to "heart",
        "dich" to "you", "dir" to "you", "dein" to "you",
        "nacht" to "night", "nächt" to "night",
        "licht" to "light", "leucht" to "light", "glanz" to "light",
        "traum" to "dream", "träum" to "dream",
        "wind" to "wind", "sturm" to "wind", "himmel" to "sky", "stern" to "star",
        "trän" to "tears", "schmerz" to "pain", "weh" to "pain",
        "erinner" to "memory", "gedenk" to "memory", "zeit" to "time", "stund" to "time",
        "einsam" to "lonely", "allein" to "lonely", "verlassen" to "lonely",
        "warm" to "warmth", "wärm" to "warmth", "zärtlich" to "warmth", "sanft" to "warmth",
        "lüg" to "lie", "betrüg" to "lie",
        "abschied" to "goodbye", "tschüs" to "goodbye", "lebwohl" to "goodbye",
        "wart" to "wait", "sommer" to "summer", "hitz" to "heat", "heiß" to "heat",
        "regn" to "rain", "meer" to "sea", "wolk" to "cloud",
        "straße" to "road", "strasse" to "road", "pfad" to "road",
        "entfern" to "distance", "fern" to "distance", "weit" to "distance",
        "atem" to "breath", "fall" to "fall", "stürz" to "fall", "hoff" to "hope",
        "glück" to "happiness", "freud" to "happiness", "froh" to "happiness",
        "zukunft" to "future", "künftig" to "future",
        "rett" to "salvation", "erlös" to "salvation", "trost" to "salvation",
        "zerbrech" to "fragile", "brüchig" to "fragile", "verletz" to "fragile",
        "ewig" to "eternity", "unendlich" to "eternity", "welt" to "world",
        "schweig" to "silence", "stille" to "silence",
        "stimm" to "voice", "sing" to "voice", "lied" to "voice",
        "feuer" to "fire", "brenn" to "fire", "flamm" to "fire",
        "blum" to "flower", "blüt" to "flower", "schatt" to "shadow",
        "taub" to "numb", "betäub" to "numb", "sand" to "sand",
        "welle" to "wave", "wog" to "wave", "aug" to "eyes",
        "hand" to "hands", "händ" to "hands",
        "univer" to "universe", "kosmos" to "universe", "galax" to "universe",
        "morgen" to "morning", "früh" to "morning",
        "fehler" to "mistake", "schuld" to "mistake", "irrtum" to "mistake",
        "bereu" to "regret", "reue" to "regret", "vermiss" to "regret",
        "chaos" to "mess", "wirr" to "mess", "durcheinander" to "mess",
        "freund" to "friend", "frei" to "freedom", "versprech" to "promise",
        "glaub" to "believe", "vertrau" to "believe",
        "verlier" to "lose", "verlor" to "lose", "weiter" to "continue", "neu" to "new",
        "ende" to "end", "endet" to "end", "seel" to "soul", "geist" to "soul",
        "kalt" to "cold", "kält" to "cold", "kühl" to "cold",
        "stadt" to "city", "schlaf" to "sleep", "schläf" to "sleep",
        "glas" to "glass", "spiegel" to "glass",
        // ---- 俄语词干 ----
        "любов" to "love", "любл" to "love", "люби" to "love",
        "сердц" to "heart", "теб" to "you", "тво" to "you",
        "ноч" to "night", "свет" to "light", "сия" to "light",
        "мечт" to "dream", "снит" to "dream", "снил" to "dream",
        "вете" to "wind", "ветр" to "wind", "небе" to "sky", "небос" to "sky",
        "звезд" to "star", "звёзд" to "star",
        "слез" to "tears", "слёз" to "tears", "плач" to "tears", "плак" to "tears",
        "боль" to "pain", "страда" to "pain", "рани" to "pain",
        "памят" to "memory", "вспомин" to "memory", "помн" to "memory",
        "врем" to "time", "час" to "time",
        "одинок" to "lonely", "тоск" to "lonely",
        "тепл" to "warmth", "нежн" to "warmth", "ласк" to "warmth",
        "лож" to "lie", "обман" to "lie", "врут" to "lie", "врал" to "lie",
        "проща" to "goodbye", "жда" to "wait", "жду" to "wait",
        "летн" to "summer", "лето" to "summer", "жар" to "heat", "зной" to "heat",
        "дожд" to "rain", "морск" to "sea", "море" to "sea",
        "облак" to "cloud", "туч" to "cloud",
        "дорог" to "road", "пут" to "road",
        "далек" to "distance", "далёк" to "distance", "даль" to "distance",
        "дыхан" to "breath", "дыш" to "breath", "вздох" to "breath",
        "пада" to "fall", "упал" to "fall", "паден" to "fall",
        "надежд" to "hope", "надеж" to "hope", "надею" to "hope",
        "счаст" to "happiness", "радост" to "happiness",
        "будущ" to "future", "завтра" to "future",
        "спас" to "salvation", "утеш" to "salvation",
        "хрупк" to "fragile", "разбит" to "fragile", "ломк" to "fragile",
        "вечн" to "eternity", "навсегд" to "eternity", "мир" to "world",
        "тишин" to "silence", "тих" to "silence", "молч" to "silence",
        "голос" to "voice", "песн" to "voice", "поё" to "voice",
        "огн" to "fire", "пожар" to "fire",
        "цвет" to "flower", "тень" to "shadow", "тени" to "shadow",
        "оцепен" to "numb", "онем" to "numb", "безразлич" to "numb",
        "песок" to "sand", "песк" to "sand", "волн" to "wave",
        "глаз" to "eyes", "взгляд" to "eyes",
        "рук" to "hands", "ладон" to "hands", "обним" to "hands",
        "вселен" to "universe", "космос" to "universe", "галакт" to "universe",
        "утро" to "morning", "утрен" to "morning", "рассвет" to "morning",
        "ошиб" to "mistake", "винов" to "mistake", "грех" to "mistake",
        "сожал" to "regret", "жале" to "regret", "раска" to "regret",
        "хаос" to "mess", "путан" to "mess", "беспоряд" to "mess",
        "друг" to "friend", "друз" to "friend",
        "свобод" to "freedom", "воля" to "freedom",
        "обеща" to "promise", "клятв" to "promise",
        "верю" to "believe", "вериш" to "believe", "довер" to "believe",
        "потеря" to "lose", "теря" to "lose", "утрат" to "lose",
        "продолж" to "continue", "дальш" to "continue", "нов" to "new",
        "конч" to "end", "конец" to "end", "финал" to "end",
        "душ" to "soul", "холод" to "cold", "мерз" to "cold",
        "город" to "city", "спат" to "sleep", "засып" to "sleep", "проснус" to "sleep",
        "сон" to "sleep", "стекл" to "glass", "зеркал" to "glass",
    )

    private val CONCEPT_TOKENS: Map<String, String> =
        CONCEPT_LEXICON.flatMap { (concept, words) -> words.map { it.lowercase() to concept } }.toMap()

    // 汉字 / 假名概念词按长度倒序匹配，避免「心」先于「心疼」命中而丢失更具体的语义
    private val SCRIPT_CONCEPT_ITEMS: List<Pair<String, String>> =
        CONCEPT_LEXICON.flatMap { (concept, words) ->
            words.filter { LEXICON_WORD_RE.matchEntire(it) != null }.map { it to concept }
        }.sortedByDescending { it.first.length }

    // 歌词中的制作信息行不含歌词语义，会稀释偏好词袋
    private val METADATA_PREFIX = Regex(
        "^(作词|作詞|作曲|编曲|編曲|制作人|製作人|词|詞|曲|歌手|专辑|專輯|由|出品|和声|和聲|混音|母带|母帶|录音|錄音|OP|SP|监制|監製|策划|企劃)"
    )

    /** 数据清洗：去制作信息行与空行，返回有效歌词行 */
    fun cleanLyrics(rawLines: Collection<String>): List<String> = rawLines
        .map { it.trim() }
        .filter { it.isNotEmpty() && METADATA_PREFIX.find(it) == null }

    fun cleanLyrics(text: String): List<String> = cleanLyrics(text.lines())

    /**
     * 分词：拉丁词（含德语变音）保留完整词形，西里尔词整词保留，
     * 中文切 2-gram，假名切 2-gram，谚文整段保留。
     *
     * 中文与日文都用 2-gram 而非词典分词：歌词中新词与专有名词多，词表分词会把它们整段丢弃。
     * 假名必须参与分词 —— 日文实词大量以假名书写（「ゆめ」「なみだ」「きぼう」），
     * 只取汉字段会让日文歌在词面通道上近乎空白。
     */
    fun tokenize(line: String): List<String> {
        val tokens = mutableListOf<String>()
        LATIN_RE.findAll(line).forEach { match ->
            val word = match.value.lowercase()
            if (word.length > 1 && word !in STOP_LATIN && word !in STOP_DE) tokens += word
        }
        CYRILLIC_RE.findAll(line).forEach { match ->
            val word = match.value.lowercase()
            if (word.length > 1 && word !in STOP_RU) tokens += word
        }
        HAN_RE.findAll(line).forEach { match ->
            val segment = match.value
            for (i in 0 until segment.length - 1) {
                val gram = segment.substring(i, i + 2)
                if (gram.none { it in STOP_CN }) tokens += gram
            }
        }
        KANA_RE.findAll(line).forEach { match ->
            val segment = match.value
            for (i in 0 until segment.length - 1) {
                val gram = segment.substring(i, i + 2)
                // 整段皆由助词类假名组成时丢弃，含实词字符的组合一律保留
                if (gram.any { it !in STOP_KANA }) tokens += gram
            }
        }
        HANGUL_RE.findAll(line).forEach { tokens += it.value }
        return tokens
    }

    /** 词频统计 */
    fun termCounts(lines: Collection<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        lines.forEach { line -> tokenize(line).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts
    }

    /** 概念槽计数：拉丁按词匹配，汉字/假名/谚文/德语构词/俄语词干按包含匹配 */
    fun conceptCounts(lines: Collection<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        lines.forEach { line ->
            val lower = line.lowercase()
            LATIN_RE.findAll(lower).forEach { match ->
                CONCEPT_TOKENS[match.value]?.let { counts[it] = (counts[it] ?: 0) + 1 }
            }
            SCRIPT_CONCEPT_ITEMS.forEach { (word, concept) ->
                if (line.contains(word)) counts[concept] = (counts[concept] ?: 0) + 1
            }
            SUBSTRING_CONCEPTS.forEach { (word, concept) ->
                if (lower.contains(word)) counts[concept] = (counts[concept] ?: 0) + 1
            }
        }
        return counts
    }

    /**
     * 结构特征：无时间戳歌词时作为节奏通道的代理量。
     *
     * 副歌重复度高说明是循环副歌的流行结构，独特行比高说明是叙事型，
     * 二者与行数、行长共同构成可比较的结构向量。
     */
    fun structure(lines: List<String>): StructureFeatures {
        val total = lines.size
        if (total == 0) return StructureFeatures(0, 0.0, 0.0, 0.0)
        val unique = lines.distinct().size
        val averageLength = lines.sumOf { it.length }.toDouble() / total
        return StructureFeatures(
            lines = total,
            avgLineLength = averageLength,
            chorusRepeat = 1.0 - unique.toDouble() / total,
            uniqueRatio = unique.toDouble() / total,
        )
    }

    /** 标准化欧氏距离：各维按候选池标准差缩放，避免行数这类大量纲维度压倒其它维度 */
    fun normalizedDistance(a: StructureFeatures, b: StructureFeatures, sigma: StructureSigma): Double {
        val squared = listOf(
            (a.lines - b.lines).toDouble() / sigma.lines,
            (a.avgLineLength - b.avgLineLength) / sigma.avgLineLength,
            (a.chorusRepeat - b.chorusRepeat) / sigma.chorusRepeat,
            (a.uniqueRatio - b.uniqueRatio) / sigma.uniqueRatio,
        ).map { it * it }
        return sqrt(squared.sum()) / sqrt(4.0)
    }
}

/** 歌词结构代理特征 */
internal data class StructureFeatures(
    val lines: Int,
    val avgLineLength: Double,
    val chorusRepeat: Double,
    val uniqueRatio: Double,
)

/** 结构向量各维的尺度（候选池标准差），用于跨曲目距离的标准化 */
internal data class StructureSigma(
    val lines: Double,
    val avgLineLength: Double,
    val chorusRepeat: Double,
    val uniqueRatio: Double,
)
