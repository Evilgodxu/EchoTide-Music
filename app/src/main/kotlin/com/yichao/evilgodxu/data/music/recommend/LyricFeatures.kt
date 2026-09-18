package com.yichao.evilgodxu.data.music.recommend

import kotlin.math.sqrt

/**
 * 歌词文本特征提取：词面分词、跨语概念映射与结构代理特征。
 *
 * 推荐算法的三个通道在此取原始特征，打分与排序在 [MusicRecommender] 中完成。
 *
 * 覆盖语言：中（简/繁）、英、日、韩、德、俄。各语言的书写系统决定了分词方式 ——
 * 有空格分词的英/德/俄整词成词元，韩语还要剥掉助词与语尾取词干，中日按 2-gram 切分。
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

    // 韩语功能词：助词与语尾剥不掉的常用虚词、代词、副词，以及「剥完只剩形素」的功能性词干
    private val STOP_KO = setOf(
        "나는", "내가", "나를", "나의", "너는", "너를", "네가", "우리", "저는", "제가",
        "그는", "그녀", "그것", "이것", "저것", "여기", "거기", "저기",
        "그리고", "그러나", "하지만", "그래서", "그러면", "그런데", "그럼", "그냥",
        "정말", "진짜", "너무", "아주", "많이", "조금", "다시", "이제", "아직", "이미",
        "때문", "때문에", "위해", "통해", "함께", "같이", "모두", "다들", "하나", "둘",
        "이런", "그런", "저런", "어떤", "무슨", "어디", "언제", "누가", "무엇", "왜", "어떻게",
        "그대", "당신",
        // 剥尾后剩下的功能性词干：实词剥尾不会落到这些形态上。
        // 只收系词、补助动词、存在动词与单位名词，形容词的冠形形（큰/좋은）带情感，保留
        "있어", "없어", "있는", "없는", "있게", "없게", "같아", "같은", "하는", "하지",
        "해서", "해도", "한다", "했다", "되고", "되어", "되는", "아무래",
        "할", "하", "되", "돼", "있", "없", "같", "준",
        "수", "거", "것", "네", "내", "제", "저", "좀", "잘", "더", "안", "못", "등", "및", "위", "중",
    )

    /**
     * 韩语助词与语尾。
     *
     * 韩语以「어절」（空格单位）成词，一个 어절 = 词干 + 助词/语尾：사랑 / 사랑을 / 사랑해
     * 在词面上是三个不同的词元。不剥尾，词面通道在韩语内部就对不上，遑论跨语言。
     *
     * 长尾优先匹配：사랑해요 应剥成 사랑（命中「해요」），而不是剥成 사랑해（命中「요」）。
     */
    private val KO_SUFFIXES: List<String> = listOf(
        "에서는", "으로는", "에게서", "이라고", "에서도", "으로도", "에게는", "이라고는",
        "이라도", "이라서", "이라는", "더라도", "뿐만",
        "습니다", "세요", "어요", "아요", "해요", "네요", "군요", "나요", "는데", "지만",
        "면서", "니까", "려고", "러고", "라고", "다고", "에서", "에게", "으로", "이나",
        "까지", "부터", "처럼", "보다", "마다", "한테", "께서", "밖에", "조차", "마저",
        "커녕", "이랑", "하고", "이란", "라도", "라는", "한다", "했다", "하는", "하지",
        "해서", "해도", "되고", "되어", "되는", "든지", "만큼", "대로",
        "았", "었", "겠", "해", "워", "와", "요", "다", "고", "서",
        "며", "면", "지", "게", "음", "함", "할", "된", "봐", "줘", "걸", "죠", "데",
        "은", "는", "이", "가", "을", "를", "에", "의",
        "도", "만", "로", "과", "랑", "야", "아",
    ).sortedByDescending { it.length }

    /**
     * 韩语词干：剥掉一个最长的助词/语尾。
     *
     * 剩余长度的下限取决于尾本身：多字尾（이라도/해요）是屈折的确证，剥到单字也认；
     * 单字尾（랑/가/도）与词根形素同形（사랑 的「랑」），剥到单字就把实词毁了，故要求剩两字。
     */
    private fun koreanStem(eojeol: String): String {
        if (eojeol.length < 3) return eojeol
        val suffix = KO_SUFFIXES.firstOrNull { eojeol.endsWith(it) } ?: return eojeol
        val stem = eojeol.dropLast(suffix.length)
        val minStem = if (suffix.length >= 2) 1 else 2
        return if (stem.length >= minStem) stem else eojeol
    }

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
     * 无法用整词精确匹配的语言另走两条通道：韩语黏着语按词元前缀（[KOREAN_CONCEPTS]），
     * 德语复合构词与俄语格变化按子串（[SUBSTRING_CONCEPTS]）。
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
     * 韩语概念词条：按词元前缀匹配，不并入 [SUBSTRING_CONCEPTS]。
     *
     * 韩语是黏着语，어절 = 词干 + 助词/语尾，词干必在词元开头，故判据是「词元以词条开头」。
     * 整行包含匹配对韩语不成立：「이별」（离别）会命中「별」（星），「기대어」（倚靠）会命中「기대」（期待）。
     *
     * 复合词的词干在词元中段（밤하늘 的 하늘），前缀判据够不到，故把常见复合词单独收录。
     * 单音节词干与其它词共形者不单收：「비」会被 비밀 命中，「길」会被 길다 命中 —— 改写带尾形态。
     */
    private val KOREAN_CONCEPTS: Map<String, String> = mapOf(
        "사랑" to "love", "연인" to "love",
        "마음" to "heart", "심장" to "heart", "가슴" to "heart",
        "그대" to "you", "당신" to "you", "너의" to "you", "네가" to "you", "너를" to "you",
        "밤" to "night", "어둠" to "night", "한밤" to "night", "새벽" to "night",
        "빛" to "light", "빛나" to "light", "반짝" to "light", "햇살" to "light",
        "꿈" to "dream", "꿈결" to "dream",
        "바람" to "wind", "하늘" to "sky", "밤하늘" to "sky", "하늘빛" to "sky",
        "별" to "star", "별빛" to "star", "별자리" to "universe",
        "눈물" to "tears", "울어" to "tears", "울음" to "tears",
        "아픔" to "pain", "아파" to "pain", "아프" to "pain", "상처" to "pain", "통증" to "pain",
        "기억" to "memory", "추억" to "memory", "생각" to "memory",
        "시간" to "time", "순간" to "time", "세월" to "time",
        "외로" to "lonely", "혼자" to "lonely", "고독" to "lonely",
        "따뜻" to "warmth", "온기" to "warmth", "다정" to "warmth", "포근" to "warmth",
        "거짓" to "lie", "속임" to "lie",
        "안녕" to "goodbye", "이별" to "goodbye", "떠나" to "goodbye", "작별" to "goodbye",
        "기다" to "wait", "기다림" to "wait",
        "여름" to "summer", "더위" to "heat", "뜨거" to "heat", "열기" to "heat",
        "비가" to "rain", "빗물" to "rain", "빗소리" to "rain", "소나기" to "rain",
        "바다" to "sea", "구름" to "cloud",
        "길을" to "road", "골목" to "road", "도로" to "road",
        "멀리" to "distance", "멀어" to "distance",
        "숨결" to "breath", "호흡" to "breath",
        "떨어" to "fall", "추락" to "fall",
        "희망" to "hope", "소망" to "hope", "기대" to "hope",
        "행복" to "happiness", "기쁨" to "happiness", "기뻐" to "happiness", "즐거" to "happiness",
        "미래" to "future", "내일" to "future", "앞으로" to "future",
        "구원" to "salvation", "구해" to "salvation", "살려" to "salvation", "위로" to "salvation",
        "약하" to "fragile", "부서" to "fragile", "여려" to "fragile",
        "영원" to "eternity", "언제까지" to "eternity", "영영" to "eternity",
        "세상" to "world", "세계" to "world", "인간" to "world",
        "침묵" to "silence", "조용" to "silence", "고요" to "silence",
        "목소리" to "voice", "노래" to "voice", "소리" to "voice",
        "불꽃" to "fire", "불빛" to "fire", "화염" to "fire", "타오" to "fire",
        "꽃" to "flower", "꽃잎" to "flower",
        "그림자" to "shadow", "그늘" to "shadow",
        "마비" to "numb", "무감" to "numb", "멍하" to "numb",
        "모래" to "sand", "파도" to "wave", "물결" to "wave",
        "눈동자" to "eyes", "눈빛" to "eyes", "시선" to "eyes",
        "손을" to "hands", "손길" to "hands", "잡아" to "hands",
        "우주" to "universe", "은하" to "universe",
        "아침" to "morning", "해돋" to "morning",
        "실수" to "mistake", "잘못" to "mistake",
        "후회" to "regret", "아쉬" to "regret", "미련" to "regret",
        "혼란" to "mess", "엉망" to "mess",
        "친구" to "friend", "우정" to "friend",
        "자유" to "freedom", "해방" to "freedom",
        "약속" to "promise", "맹세" to "promise",
        // 믿/잃/끝 只有单一词族，无共形问题
        "믿" to "believe", "신뢰" to "believe",
        "잃" to "lose", "놓쳐" to "lose",
        "계속" to "continue", "이어" to "continue",
        "새로운" to "new", "처음" to "new", "새로" to "new",
        "끝" to "end", "끝나" to "end", "마지막" to "end",
        "영혼" to "soul", "운명" to "soul", "정신" to "soul",
        "차가" to "cold", "추워" to "cold", "시려" to "cold", "얼음" to "cold",
        "도시" to "city", "거리" to "city", "네온" to "city",
        "잠들" to "sleep", "잠이" to "sleep", "졸려" to "sleep",
        "유리" to "glass", "거울" to "glass", "투명" to "glass",
    )

    /**
     * 按子串命中的概念词条：德语与俄语存不住「整词」这个前提。
     *
     * - 德语靠复合构词（Herzschlag 含 Herz、Sonnenuntergang 含 Sonne），整词匹配基本落空；
     * - 俄语每个名词、形容词、动词都按格与时态变形（любовь / любви / любить），
     *   所以存的是词干（любов、любл、люби）而不是词。
     *
     * 这两种语言都以空格分隔词、以词形变化承载语法，词干在词内出现，故用包含判据。
     * 韩语不同（黏着语，词干固定在词元开头），另有 [KOREAN_CONCEPTS] 走前缀判据。
     *
     * 代价是词干短时有误命中（「нов」也会命中 «снова»），但这是该类语言唯一可用的召回通道，
     * 噪声由 IDF 与三通道权重稀释。
     *
     * 取词干时须避开跨语言碰撞：子串匹配不分语种，德语词干若与英文高频词形重叠会大面积误命中 ——
     * `end` 会被 friend / send / spend / weekend 命中，`still` 会被英文副词命中，
     * 故德语取 `ende` / `endet` / `stille` 这类不会跨语碰撞的形式。
     */
    private val SUBSTRING_CONCEPTS: Map<String, String> = mapOf(
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
     * 中文切 2-gram，假名切 2-gram，谚文剥掉助词/语尾后取词干。
     *
     * 中文与日文都用 2-gram 而非词典分词：歌词中新词与专有名词多，词表分词会把它们整段丢弃。
     * 假名必须参与分词 —— 日文实词大量以假名书写（「ゆめ」「なみだ」「きぼう」），
     * 只取汉字段会让日文歌在词面通道上近乎空白。
     * 韩语不能按整段取词元 —— 一个 어절 含词干与助词，不剥尾则同一词的不同格位互不相识。
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
        HANGUL_RE.findAll(line).forEach { match ->
            // 韩语剥掉助词/语尾后再成词元：사랑 / 사랑을 / 사랑해 都归到「사랑」
            val stem = koreanStem(match.value)
            if (stem.isNotEmpty() && stem !in STOP_KO) tokens += stem
        }
        return tokens
    }

    /** 词频统计 */
    fun termCounts(lines: Collection<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        lines.forEach { line -> tokenize(line).forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        return counts
    }

    /** 概念槽计数：拉丁按词匹配，汉字/假名按包含匹配，韩语按词元前缀，德语/俄语按词干包含 */
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
            val koreanTokens = HANGUL_RE.findAll(line).map { koreanStem(it.value) }.toList()
            if (koreanTokens.isNotEmpty()) {
                KOREAN_CONCEPTS.forEach { (stem, concept) ->
                    if (koreanTokens.any { it.startsWith(stem) }) counts[concept] = (counts[concept] ?: 0) + 1
                }
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
