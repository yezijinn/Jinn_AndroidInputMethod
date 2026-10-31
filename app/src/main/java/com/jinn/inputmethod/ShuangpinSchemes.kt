package com.jinn.inputmethod

/**
 * 双拼键位数据（由脚本生成，勿手改）。
 *
 * 生成器：`tools/dict_builder/gen_shuangpin_tables.py`
 * 权威来源：`docs/rime-ice/double_pinyin*.schema.yaml` 的 `speller/algebra`，
 * 脚本按 librime 的代数语义（xform 替换 / derive 追加 / erase / xlit）逐条施加到
 * `assets/pinyin_syllables.txt` 的 421 个合法音节上，算出每套方案的码表。
 *
 * 因此本文件的键位与 rime-ice 完全一致（含 rime 的 derive 容错码）；唯一的人工裁决是
 * 「一码多音节」的取舍（如 `lo` = lo/luo），规则见生成器里的 COLLISION_PREFER 注释。
 * 首道双拼没有 rime 官方方案，按公开键位规格直表编码（见生成器的 TABLE_SCHEMES）。
 *
 * 键面提示（字母键下方的韵母、zh/ch/sh 红字）由本表在运行期反推，不另存一份，
 * 以保证键面与引擎永不漂移。
 *
 * 每套方案惰性构建（`Lazy`）：8 套表一次全建约 3,400 条目，实测首访耗时可观，
 * 而键盘视图是在 `onCreateInputView`（键盘首次弹出）里创建的，若在该路径同步建表会拖慢首次弹出。
 * 因此改为「用到哪套建哪套」，并在 IME 服务创建时由后台线程预热（见 `Shuangpin.warmUpAll`）。
 */

internal class ShuangpinTable(
    /** 声母键 → 声母（zh/ch/sh 各方案不同，例如 ABC 是 a/e/v） */
    val initials: Map<Char, String>,
    /** 双拼码（两字符）→ 全拼音节；含 rime 的 derive 容错码与零声母形式 */
    val codes: Map<String, String>,
) {
    /** 是否有码用到分号键（搜狗/微软/紫光的 ing 在分号键上） */
    val needsSemicolon: Boolean = codes.keys.any { it.contains(';') }

    /** 叹词音节（呣 / 嗯 / 哼），不作为韵母提示展示 */
    private val nasalExtras = setOf("m", "n", "ng", "hm", "hng")

    /**
     * 韵母表的规范顺序：只用于同一行内给韵母排序（长者在前），
     * 让同屏的两个韵母次序固定且符合直觉，例如微软 v 键显示 `ui ue` 而非 `ue ui`。
     *
     * **必须覆盖码表会产出的全部韵母**（含 üe 的 `ue` 写法）：漏项一律落到兜底值、
     * 只剩字符序兜底，而 `ui` / `ue` 这类同长项就再也拿不到「符合直觉」的次序。
     * 覆盖度由 `HintRuleTest` 逐个韵母核对（故不加 `private`）。
     */
    val finalOrder = listOf(
        "a", "o", "e", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "ong",
        "i", "ia", "ie", "iao", "iu", "ian", "in", "iang", "ing", "iong",
        "u", "ua", "uo", "uai", "ui", "ue", "uan", "un", "uang", "ueng",
        "v", "ve", "van", "vn",
    )

    /** 该声母键对应的声母；非声母键返回 null */
    fun initialOf(key: Char): String? = initials[key]

    /**
     * [codes] 的**打包索引**（MEM-20，惰性建一次，1024 项 ≈ 4KB/方案）：
     * `toQuanpin` 与 `displayQuanpin` 每两键各查一次码表，原先每次 `raw.substring(i, i + 2)`
     * 只为当 Map 键 —— 一次查询下来是十几个短命 String。
     *
     * 键位数据本身与生成端都不动：这是同一张 [codes] 的派生视图（值仍是同一个 String 引用），
     * 因此 `ShuangpinTest` 的键位期望与 `SHUANGPIN_TABLES` 生成物一模一样。
     */
    private val packedCodes: Array<String?> by lazy {
        val t = arrayOfNulls<String>(PACKED_SIZE)
        for ((code, syllable) in codes) {
            if (code.length == 2) {
                val p = packedIndexOf(code[0], code[1])
                if (p >= 0) t[p] = syllable
            }
        }
        t
    }

    /**
     * 两键整查（免建 String）。非字母对（如搜狗/微软的分号 `ing`）回落字符串查表 ——
     * 那条路径只在真按到分号键时才走。
     */
    fun codeOf(a: Char, b: Char): String? {
        val p = packedIndexOf(a, b)
        return if (p >= 0) packedCodes[p] else codes["" + a + b]
    }

    /** 韵母键 → 该键承载的韵母（由码表反推，因此永远与引擎一致） */
    private val finalsByKey: Map<Char, List<String>> = LinkedHashMap<Char, MutableList<String>>().apply {
        for ((code, syllable) in codes) {
            // 只认两字符码：非两字符码（abbrev 一类）没有「第二键」可言，
            // 与 packedCodes 同一口径，同时保证下面的 code[1] 不会越界。
            if (code.length != 2) continue
            val ini = initials[code[0]] ?: continue
            val fin = syllable.removePrefix(ini)
            if (fin.isEmpty() || fin == syllable || fin in nasalExtras) continue
            val list = getOrPut(code[1]) { mutableListOf() }
            if (fin !in list) list.add(fin)
        }
    }

    /**
     * 该键的韵母提示（字母键下方的小字）。
     *
     * 规则（不得违反）：
     *  1. 绝不显示与该键字母相同的韵母，e 键不显示 e、v 键不显示 v、a/i/u 同理，
     *     属纯冗余；
     *  2. ü 的两种写法同时出现时（u 对应 jqxy 后、v 对应 l/n 后）只留 v，
     *     否则同一键上会出现两个表示同一读音的项；
     *  3. 总显示行数强制 ≤ 2（含红色 zh/ch/sh 那一行）：
     *     · 该键承担 zh/ch/sh 时韵母挤成一行（红色占第 2 行）；
     *     · 否则每个韵母各占一行（最多 2 个）。
     *     每键过滤后本就不超过 2 项（见 [finalsOf]），此处 `take(2)` 只是兜底、从无截断。
     *
     * 例：自然码/小鹤/加加 v → `ui` + 红 `zh`；微软 v → 一行 `ui ue` + 红 `zh`；
     * 搜狗/微软 y → `uai` / `v` 两行。
     */
    fun finalHint(key: Char): String {
        val list = finalsOf(key).take(2)
        if (list.isEmpty()) return ""
        if (list.size == 1) return list[0]
        val hasRedInitial = (initials[key]?.length ?: 0) > 1
        return if (hasRedInitial) list.joinToString(" ") else list.joinToString("\n")
    }

    /**
     * 该键在规则 1 / 2 之后剩下的韵母，已按规范顺序排好（同一行内长者在前）。
     *
     * 与 [finalHint] 分开是为了让守卫能看见「被规则 3 截掉的部分」：`finalHint` 只取前 2 项，
     * 而只要这里超过 2 项就意味着有韵母**永远显示不出来**（静默丢信息）。
     * 用例 `任何方案任何键_过滤后不得静默截断` 钉着这条；改动规则时先看它。
     */
    internal fun finalsOf(key: Char): List<String> {
        val raw = finalsByKey[key] ?: return emptyList()
        // 注意顺序：先去重 ü 的两种写法（此时 v 还在，才能判断），再剔除与键位相同的韵母。
        // 反过来做会漏掉「v 键的 u」（自然码/小鹤/加加的 v 键原始集合是 {ui, u, v}：
        // 先去自身会得到 {ui, u}，u 便再无参照可去）。
        val list = if ("v" in raw && "u" in raw) raw.filter { it != "u" } else raw
        return list
            .filter { it != key.toString() }
            .distinct()
            .sortedWith(
                compareByDescending<String> { it.length }
                    .thenBy { finalOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    // 表外韵母（finalOrder 漏项）之间的次序：按字符序兜底，
                    // 不依赖码表的遍历序 —— 否则同一键上两个表外韵母的先后随表内容漂移。
                    .thenBy { it },
            )
    }

    /**
     * 打包算式与容量：字母对压成 5+5 bit（`a`..`z` → 0..25），非字母对返回 -1。
     *
     * 表长取 32×32 而不是 26×26：`(x shl 5) or y` 直接就是下标，不必再做一次除法/取模。
     */
    private companion object {
        const val PACKED_SIZE = 32 * 32

        fun packedIndexOf(a: Char, b: Char): Int {
            val x = a - 'a'
            val y = b - 'a'
            return if (x in 0..25 && y in 0..25) (x shl 5) or y else -1
        }
    }
}

/** 内置双拼方案表（生成数据见文件末尾） */
internal val SHUANGPIN_TABLES: Map<String, Lazy<ShuangpinTable>> = mapOf(

    /** 自然码双拼（rime-ice `double_pinyin.schema.yaml`）：418 个音节 / 434 个码 */
    "ZIRANMA" to lazy { ShuangpinTable(
        initials = mapOf(
            'b' to "b", 'c' to "c", 'd' to "d", 'f' to "f", 'g' to "g", 'h' to "h",
            'i' to "ch", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n",
            'p' to "p", 'q' to "q", 'r' to "r", 's' to "s", 't' to "t", 'u' to "sh",
            'v' to "zh", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        ),
        codes = mapOf(
            "aa" to "a", "ah" to "ang", "ai" to "ai", "aj" to "an", "ak" to "ao",
            "al" to "ai", "an" to "an", "ao" to "ao", "ba" to "ba", "bc" to "biao",
            "bd" to "biang", "bf" to "ben", "bg" to "beng", "bh" to "bang", "bi" to "bi",
            "bj" to "ban", "bk" to "bao", "bl" to "bai", "bm" to "bian", "bn" to "bin",
            "bo" to "bo", "bu" to "bu", "bx" to "bie", "by" to "bing", "bz" to "bei",
            "ca" to "ca", "cb" to "cou", "ce" to "ce", "cf" to "cen", "cg" to "ceng",
            "ch" to "cang", "ci" to "ci", "cj" to "can", "ck" to "cao", "cl" to "cai",
            "co" to "cuo", "cp" to "cun", "cr" to "cuan", "cs" to "cong", "cu" to "cu",
            "cv" to "cui", "cz" to "cei", "da" to "da", "db" to "dou", "dc" to "diao",
            "de" to "de", "df" to "den", "dg" to "deng", "dh" to "dang", "di" to "di",
            "dj" to "dan", "dk" to "dao", "dl" to "dai", "dm" to "dian", "do" to "duo",
            "dp" to "dun", "dq" to "diu", "dr" to "duan", "ds" to "dong", "du" to "du",
            "dv" to "dui", "dw" to "dia", "dx" to "die", "dy" to "ding", "dz" to "dei",
            "ee" to "e", "ef" to "en", "eg" to "eng", "ei" to "ei", "en" to "en",
            "er" to "er", "ez" to "ei", "fa" to "fa", "fb" to "fou", "fc" to "fiao",
            "ff" to "fen", "fg" to "feng", "fh" to "fang", "fj" to "fan", "fo" to "fo",
            "fu" to "fu", "fz" to "fei", "ga" to "ga", "gb" to "gou", "gd" to "guang",
            "ge" to "ge", "gf" to "gen", "gg" to "geng", "gh" to "gang", "gj" to "gan",
            "gk" to "gao", "gl" to "gai", "go" to "guo", "gp" to "gun", "gr" to "guan",
            "gs" to "gong", "gu" to "gu", "gv" to "gui", "gw" to "gua", "gy" to "guai",
            "gz" to "gei", "ha" to "ha", "hb" to "hou", "hd" to "huang", "he" to "he",
            "hf" to "hen", "hg" to "heng", "hh" to "hang", "hj" to "han", "hk" to "hao",
            "hl" to "hai", "hm" to "hm", "ho" to "huo", "hp" to "hun", "hr" to "huan",
            "hs" to "hong", "hu" to "hu", "hv" to "hui", "hw" to "hua", "hy" to "huai",
            "hz" to "hei", "ia" to "cha", "ib" to "chou", "id" to "chuang", "ie" to "che",
            "if" to "chen", "ig" to "cheng", "ih" to "chang", "ii" to "chi", "ij" to "chan",
            "ik" to "chao", "il" to "chai", "io" to "chuo", "ip" to "chun", "ir" to "chuan",
            "is" to "chong", "iu" to "chu", "iv" to "chui", "iw" to "chua", "iy" to "chuai",
            "jc" to "jiao", "jd" to "jiang", "ji" to "ji", "jm" to "jian", "jn" to "jin",
            "jp" to "jun", "jq" to "jiu", "jr" to "juan", "js" to "jiong", "jt" to "jue",
            "ju" to "ju", "jv" to "ju", "jw" to "jia", "jx" to "jie", "jy" to "jing",
            "ka" to "ka", "kb" to "kou", "kd" to "kuang", "ke" to "ke", "kf" to "ken",
            "kg" to "keng", "kh" to "kang", "kj" to "kan", "kk" to "kao", "kl" to "kai",
            "ko" to "kuo", "kp" to "kun", "kr" to "kuan", "ks" to "kong", "ku" to "ku",
            "kv" to "kui", "kw" to "kua", "ky" to "kuai", "kz" to "kei", "la" to "la",
            "lb" to "lou", "lc" to "liao", "ld" to "liang", "le" to "le", "lg" to "leng",
            "lh" to "lang", "li" to "li", "lj" to "lan", "lk" to "lao", "ll" to "lai",
            "lm" to "lian", "ln" to "lin", "lo" to "luo", "lp" to "lun", "lq" to "liu",
            "lr" to "luan", "ls" to "long", "lt" to "lue", "lu" to "lu", "lv" to "lv",
            "lw" to "lia", "lx" to "lie", "ly" to "ling", "lz" to "lei", "ma" to "ma",
            "mb" to "mou", "mc" to "miao", "me" to "me", "mf" to "men", "mg" to "meng",
            "mh" to "mang", "mi" to "mi", "mj" to "man", "mk" to "mao", "ml" to "mai",
            "mm" to "mian", "mn" to "min", "mo" to "mo", "mq" to "miu", "mu" to "mu",
            "mx" to "mie", "my" to "ming", "mz" to "mei", "na" to "na", "nb" to "nou",
            "nc" to "niao", "nd" to "niang", "ne" to "ne", "nf" to "nen", "ng" to "neng",
            "nh" to "nang", "ni" to "ni", "nj" to "nan", "nk" to "nao", "nl" to "nai",
            "nm" to "nian", "nn" to "nin", "no" to "nuo", "nq" to "niu", "nr" to "nuan",
            "ns" to "nong", "nt" to "nue", "nu" to "nu", "nv" to "nv", "nx" to "nie",
            "ny" to "ning", "nz" to "nei", "oa" to "a", "ob" to "ou", "oe" to "e",
            "of" to "en", "og" to "eng", "oh" to "ang", "oj" to "an", "ok" to "ao",
            "ol" to "ai", "oo" to "o", "or" to "er", "ou" to "ou", "oz" to "ei",
            "pa" to "pa", "pb" to "pou", "pc" to "piao", "pf" to "pen", "pg" to "peng",
            "ph" to "pang", "pi" to "pi", "pj" to "pan", "pk" to "pao", "pl" to "pai",
            "pm" to "pian", "pn" to "pin", "po" to "po", "pu" to "pu", "px" to "pie",
            "py" to "ping", "pz" to "pei", "qc" to "qiao", "qd" to "qiang", "qi" to "qi",
            "qm" to "qian", "qn" to "qin", "qp" to "qun", "qq" to "qiu", "qr" to "quan",
            "qs" to "qiong", "qt" to "que", "qu" to "qu", "qv" to "qu", "qw" to "qia",
            "qx" to "qie", "qy" to "qing", "rb" to "rou", "re" to "re", "rf" to "ren",
            "rg" to "reng", "rh" to "rang", "ri" to "ri", "rj" to "ran", "rk" to "rao",
            "ro" to "ruo", "rp" to "run", "rr" to "ruan", "rs" to "rong", "ru" to "ru",
            "rv" to "rui", "rw" to "rua", "sa" to "sa", "sb" to "sou", "se" to "se",
            "sf" to "sen", "sg" to "seng", "sh" to "sang", "si" to "si", "sj" to "san",
            "sk" to "sao", "sl" to "sai", "so" to "suo", "sp" to "sun", "sr" to "suan",
            "ss" to "song", "su" to "su", "sv" to "sui", "ta" to "ta", "tb" to "tou",
            "tc" to "tiao", "te" to "te", "tg" to "teng", "th" to "tang", "ti" to "ti",
            "tj" to "tan", "tk" to "tao", "tl" to "tai", "tm" to "tian", "to" to "tuo",
            "tp" to "tun", "tr" to "tuan", "ts" to "tong", "tu" to "tu", "tv" to "tui",
            "tx" to "tie", "ty" to "ting", "tz" to "tei", "ua" to "sha", "ub" to "shou",
            "ud" to "shuang", "ue" to "she", "uf" to "shen", "ug" to "sheng", "uh" to "shang",
            "ui" to "shi", "uj" to "shan", "uk" to "shao", "ul" to "shai", "uo" to "shuo",
            "up" to "shun", "ur" to "shuan", "uu" to "shu", "uv" to "shui", "uw" to "shua",
            "uy" to "shuai", "uz" to "shei", "va" to "zha", "vb" to "zhou", "vd" to "zhuang",
            "ve" to "zhe", "vf" to "zhen", "vg" to "zheng", "vh" to "zhang", "vi" to "zhi",
            "vj" to "zhan", "vk" to "zhao", "vl" to "zhai", "vo" to "zhuo", "vp" to "zhun",
            "vr" to "zhuan", "vs" to "zhong", "vu" to "zhu", "vv" to "zhui", "vw" to "zhua",
            "vy" to "zhuai", "vz" to "zhei", "wa" to "wa", "wf" to "wen", "wg" to "weng",
            "wh" to "wang", "wj" to "wan", "wl" to "wai", "wo" to "wo", "wu" to "wu",
            "wz" to "wei", "xc" to "xiao", "xd" to "xiang", "xi" to "xi", "xm" to "xian",
            "xn" to "xin", "xp" to "xun", "xq" to "xiu", "xr" to "xuan", "xs" to "xiong",
            "xt" to "xue", "xu" to "xu", "xv" to "xu", "xw" to "xia", "xx" to "xie",
            "xy" to "xing", "ya" to "ya", "yb" to "you", "ye" to "ye", "yh" to "yang",
            "yi" to "yi", "yj" to "yan", "yk" to "yao", "yn" to "yin", "yo" to "yo",
            "yp" to "yun", "yr" to "yuan", "ys" to "yong", "yt" to "yue", "yu" to "yu",
            "yv" to "yu", "yy" to "ying", "za" to "za", "zb" to "zou", "ze" to "ze",
            "zf" to "zen", "zg" to "zeng", "zh" to "zang", "zi" to "zi", "zj" to "zan",
            "zk" to "zao", "zl" to "zai", "zo" to "zuo", "zp" to "zun", "zr" to "zuan",
            "zs" to "zong", "zu" to "zu", "zv" to "zui", "zz" to "zei",
        ),
    ) },
    /** 小鹤双拼（rime-ice `double_pinyin_flypy.schema.yaml`）：418 个音节 / 424 个码 */
    "FLYPY" to lazy { ShuangpinTable(
        initials = mapOf(
            'b' to "b", 'c' to "c", 'd' to "d", 'f' to "f", 'g' to "g", 'h' to "h",
            'i' to "ch", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n",
            'p' to "p", 'q' to "q", 'r' to "r", 's' to "s", 't' to "t", 'u' to "sh",
            'v' to "zh", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        ),
        codes = mapOf(
            "aa" to "a", "ac" to "ao", "ad" to "ai", "ah" to "ang", "ai" to "ai",
            "aj" to "an", "an" to "an", "ao" to "ao", "ba" to "ba", "bb" to "bin",
            "bc" to "bao", "bd" to "bai", "bf" to "ben", "bg" to "beng", "bh" to "bang",
            "bi" to "bi", "bj" to "ban", "bk" to "bing", "bl" to "biang", "bm" to "bian",
            "bn" to "biao", "bo" to "bo", "bp" to "bie", "bu" to "bu", "bw" to "bei",
            "ca" to "ca", "cc" to "cao", "cd" to "cai", "ce" to "ce", "cf" to "cen",
            "cg" to "ceng", "ch" to "cang", "ci" to "ci", "cj" to "can", "co" to "cuo",
            "cr" to "cuan", "cs" to "cong", "cu" to "cu", "cv" to "cui", "cw" to "cei",
            "cy" to "cun", "cz" to "cou", "da" to "da", "dc" to "dao", "dd" to "dai",
            "de" to "de", "df" to "den", "dg" to "deng", "dh" to "dang", "di" to "di",
            "dj" to "dan", "dk" to "ding", "dm" to "dian", "dn" to "diao", "do" to "duo",
            "dp" to "die", "dq" to "diu", "dr" to "duan", "ds" to "dong", "du" to "du",
            "dv" to "dui", "dw" to "dei", "dx" to "dia", "dy" to "dun", "dz" to "dou",
            "ee" to "e", "ef" to "en", "eg" to "eng", "ei" to "ei", "en" to "en",
            "er" to "er", "ew" to "ei", "fa" to "fa", "ff" to "fen", "fg" to "feng",
            "fh" to "fang", "fj" to "fan", "fn" to "fiao", "fo" to "fo", "fu" to "fu",
            "fw" to "fei", "fz" to "fou", "ga" to "ga", "gc" to "gao", "gd" to "gai",
            "ge" to "ge", "gf" to "gen", "gg" to "geng", "gh" to "gang", "gj" to "gan",
            "gk" to "guai", "gl" to "guang", "go" to "guo", "gr" to "guan", "gs" to "gong",
            "gu" to "gu", "gv" to "gui", "gw" to "gei", "gx" to "gua", "gy" to "gun",
            "gz" to "gou", "ha" to "ha", "hc" to "hao", "hd" to "hai", "he" to "he",
            "hf" to "hen", "hg" to "heng", "hh" to "hang", "hj" to "han", "hk" to "huai",
            "hl" to "huang", "hm" to "hm", "ho" to "huo", "hr" to "huan", "hs" to "hong",
            "hu" to "hu", "hv" to "hui", "hw" to "hei", "hx" to "hua", "hy" to "hun",
            "hz" to "hou", "ia" to "cha", "ic" to "chao", "id" to "chai", "ie" to "che",
            "if" to "chen", "ig" to "cheng", "ih" to "chang", "ii" to "chi", "ij" to "chan",
            "ik" to "chuai", "il" to "chuang", "io" to "chuo", "ir" to "chuan", "is" to "chong",
            "iu" to "chu", "iv" to "chui", "ix" to "chua", "iy" to "chun", "iz" to "chou",
            "jb" to "jin", "ji" to "ji", "jk" to "jing", "jl" to "jiang", "jm" to "jian",
            "jn" to "jiao", "jp" to "jie", "jq" to "jiu", "jr" to "juan", "js" to "jiong",
            "jt" to "jue", "ju" to "ju", "jv" to "ju", "jx" to "jia", "jy" to "jun",
            "ka" to "ka", "kc" to "kao", "kd" to "kai", "ke" to "ke", "kf" to "ken",
            "kg" to "keng", "kh" to "kang", "kj" to "kan", "kk" to "kuai", "kl" to "kuang",
            "ko" to "kuo", "kr" to "kuan", "ks" to "kong", "ku" to "ku", "kv" to "kui",
            "kw" to "kei", "kx" to "kua", "ky" to "kun", "kz" to "kou", "la" to "la",
            "lb" to "lin", "lc" to "lao", "ld" to "lai", "le" to "le", "lg" to "leng",
            "lh" to "lang", "li" to "li", "lj" to "lan", "lk" to "ling", "ll" to "liang",
            "lm" to "lian", "ln" to "liao", "lo" to "luo", "lp" to "lie", "lq" to "liu",
            "lr" to "luan", "ls" to "long", "lt" to "lue", "lu" to "lu", "lv" to "lv",
            "lw" to "lei", "lx" to "lia", "ly" to "lun", "lz" to "lou", "ma" to "ma",
            "mb" to "min", "mc" to "mao", "md" to "mai", "me" to "me", "mf" to "men",
            "mg" to "meng", "mh" to "mang", "mi" to "mi", "mj" to "man", "mk" to "ming",
            "mm" to "mian", "mn" to "miao", "mo" to "mo", "mp" to "mie", "mq" to "miu",
            "mu" to "mu", "mw" to "mei", "mz" to "mou", "na" to "na", "nb" to "nin",
            "nc" to "nao", "nd" to "nai", "ne" to "ne", "nf" to "nen", "ng" to "neng",
            "nh" to "nang", "ni" to "ni", "nj" to "nan", "nk" to "ning", "nl" to "niang",
            "nm" to "nian", "nn" to "niao", "no" to "nuo", "np" to "nie", "nq" to "niu",
            "nr" to "nuan", "ns" to "nong", "nt" to "nue", "nu" to "nu", "nv" to "nv",
            "nw" to "nei", "nz" to "nou", "oo" to "o", "ou" to "ou", "oz" to "ou",
            "pa" to "pa", "pb" to "pin", "pc" to "pao", "pd" to "pai", "pf" to "pen",
            "pg" to "peng", "ph" to "pang", "pi" to "pi", "pj" to "pan", "pk" to "ping",
            "pm" to "pian", "pn" to "piao", "po" to "po", "pp" to "pie", "pu" to "pu",
            "pw" to "pei", "pz" to "pou", "qb" to "qin", "qi" to "qi", "qk" to "qing",
            "ql" to "qiang", "qm" to "qian", "qn" to "qiao", "qp" to "qie", "qq" to "qiu",
            "qr" to "quan", "qs" to "qiong", "qt" to "que", "qu" to "qu", "qv" to "qu",
            "qx" to "qia", "qy" to "qun", "rc" to "rao", "re" to "re", "rf" to "ren",
            "rg" to "reng", "rh" to "rang", "ri" to "ri", "rj" to "ran", "ro" to "ruo",
            "rr" to "ruan", "rs" to "rong", "ru" to "ru", "rv" to "rui", "rx" to "rua",
            "ry" to "run", "rz" to "rou", "sa" to "sa", "sc" to "sao", "sd" to "sai",
            "se" to "se", "sf" to "sen", "sg" to "seng", "sh" to "sang", "si" to "si",
            "sj" to "san", "so" to "suo", "sr" to "suan", "ss" to "song", "su" to "su",
            "sv" to "sui", "sy" to "sun", "sz" to "sou", "ta" to "ta", "tc" to "tao",
            "td" to "tai", "te" to "te", "tg" to "teng", "th" to "tang", "ti" to "ti",
            "tj" to "tan", "tk" to "ting", "tm" to "tian", "tn" to "tiao", "to" to "tuo",
            "tp" to "tie", "tr" to "tuan", "ts" to "tong", "tu" to "tu", "tv" to "tui",
            "tw" to "tei", "ty" to "tun", "tz" to "tou", "ua" to "sha", "uc" to "shao",
            "ud" to "shai", "ue" to "she", "uf" to "shen", "ug" to "sheng", "uh" to "shang",
            "ui" to "shi", "uj" to "shan", "uk" to "shuai", "ul" to "shuang", "uo" to "shuo",
            "ur" to "shuan", "uu" to "shu", "uv" to "shui", "uw" to "shei", "ux" to "shua",
            "uy" to "shun", "uz" to "shou", "va" to "zha", "vc" to "zhao", "vd" to "zhai",
            "ve" to "zhe", "vf" to "zhen", "vg" to "zheng", "vh" to "zhang", "vi" to "zhi",
            "vj" to "zhan", "vk" to "zhuai", "vl" to "zhuang", "vo" to "zhuo", "vr" to "zhuan",
            "vs" to "zhong", "vu" to "zhu", "vv" to "zhui", "vw" to "zhei", "vx" to "zhua",
            "vy" to "zhun", "vz" to "zhou", "wa" to "wa", "wd" to "wai", "wf" to "wen",
            "wg" to "weng", "wh" to "wang", "wj" to "wan", "wo" to "wo", "wu" to "wu",
            "ww" to "wei", "xb" to "xin", "xi" to "xi", "xk" to "xing", "xl" to "xiang",
            "xm" to "xian", "xn" to "xiao", "xp" to "xie", "xq" to "xiu", "xr" to "xuan",
            "xs" to "xiong", "xt" to "xue", "xu" to "xu", "xv" to "xu", "xx" to "xia",
            "xy" to "xun", "ya" to "ya", "yb" to "yin", "yc" to "yao", "ye" to "ye",
            "yh" to "yang", "yi" to "yi", "yj" to "yan", "yk" to "ying", "yo" to "yo",
            "yr" to "yuan", "ys" to "yong", "yt" to "yue", "yu" to "yu", "yv" to "yu",
            "yy" to "yun", "yz" to "you", "za" to "za", "zc" to "zao", "zd" to "zai",
            "ze" to "ze", "zf" to "zen", "zg" to "zeng", "zh" to "zang", "zi" to "zi",
            "zj" to "zan", "zo" to "zuo", "zr" to "zuan", "zs" to "zong", "zu" to "zu",
            "zv" to "zui", "zw" to "zei", "zy" to "zun", "zz" to "zou",
        ),
    ) },
    /** 搜狗双拼（rime-ice `double_pinyin_sogou.schema.yaml`）：418 个音节 / 429 个码 */
    "SOGOU" to lazy { ShuangpinTable(
        initials = mapOf(
            'b' to "b", 'c' to "c", 'd' to "d", 'f' to "f", 'g' to "g", 'h' to "h",
            'i' to "ch", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n",
            'p' to "p", 'q' to "q", 'r' to "r", 's' to "s", 't' to "t", 'u' to "sh",
            'v' to "zh", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        ),
        codes = mapOf(
            "aa" to "a", "ah" to "ang", "aj" to "an", "ak" to "ao", "al" to "ai",
            "b;" to "bing", "ba" to "ba", "bc" to "biao", "bd" to "biang", "bf" to "ben",
            "bg" to "beng", "bh" to "bang", "bi" to "bi", "bj" to "ban", "bk" to "bao",
            "bl" to "bai", "bm" to "bian", "bn" to "bin", "bo" to "bo", "bu" to "bu",
            "bx" to "bie", "bz" to "bei", "ca" to "ca", "cb" to "cou", "ce" to "ce",
            "cf" to "cen", "cg" to "ceng", "ch" to "cang", "ci" to "ci", "cj" to "can",
            "ck" to "cao", "cl" to "cai", "co" to "cuo", "cp" to "cun", "cr" to "cuan",
            "cs" to "cong", "cu" to "cu", "cv" to "cui", "cz" to "cei", "d;" to "ding",
            "da" to "da", "db" to "dou", "dc" to "diao", "de" to "de", "df" to "den",
            "dg" to "deng", "dh" to "dang", "di" to "di", "dj" to "dan", "dk" to "dao",
            "dl" to "dai", "dm" to "dian", "do" to "duo", "dp" to "dun", "dq" to "diu",
            "dr" to "duan", "ds" to "dong", "du" to "du", "dv" to "dui", "dw" to "dia",
            "dx" to "die", "dz" to "dei", "ee" to "e", "ef" to "en", "eg" to "eng",
            "er" to "er", "ez" to "ei", "fa" to "fa", "fb" to "fou", "fc" to "fiao",
            "ff" to "fen", "fg" to "feng", "fh" to "fang", "fj" to "fan", "fo" to "fo",
            "fu" to "fu", "fz" to "fei", "ga" to "ga", "gb" to "gou", "gd" to "guang",
            "ge" to "ge", "gf" to "gen", "gg" to "geng", "gh" to "gang", "gj" to "gan",
            "gk" to "gao", "gl" to "gai", "go" to "guo", "gp" to "gun", "gr" to "guan",
            "gs" to "gong", "gu" to "gu", "gv" to "gui", "gw" to "gua", "gy" to "guai",
            "gz" to "gei", "ha" to "ha", "hb" to "hou", "hd" to "huang", "he" to "he",
            "hf" to "hen", "hg" to "heng", "hh" to "hang", "hj" to "han", "hk" to "hao",
            "hl" to "hai", "hm" to "hm", "ho" to "huo", "hp" to "hun", "hr" to "huan",
            "hs" to "hong", "hu" to "hu", "hv" to "hui", "hw" to "hua", "hy" to "huai",
            "hz" to "hei", "ia" to "cha", "ib" to "chou", "id" to "chuang", "ie" to "che",
            "if" to "chen", "ig" to "cheng", "ih" to "chang", "ii" to "chi", "ij" to "chan",
            "ik" to "chao", "il" to "chai", "io" to "chuo", "ip" to "chun", "ir" to "chuan",
            "is" to "chong", "iu" to "chu", "iv" to "chui", "iw" to "chua", "iy" to "chuai",
            "j;" to "jing", "jc" to "jiao", "jd" to "jiang", "ji" to "ji", "jm" to "jian",
            "jn" to "jin", "jp" to "jun", "jq" to "jiu", "jr" to "juan", "js" to "jiong",
            "jt" to "jue", "ju" to "ju", "jw" to "jia", "jx" to "jie", "jy" to "ju",
            "ka" to "ka", "kb" to "kou", "kd" to "kuang", "ke" to "ke", "kf" to "ken",
            "kg" to "keng", "kh" to "kang", "kj" to "kan", "kk" to "kao", "kl" to "kai",
            "ko" to "kuo", "kp" to "kun", "kr" to "kuan", "ks" to "kong", "ku" to "ku",
            "kv" to "kui", "kw" to "kua", "ky" to "kuai", "kz" to "kei", "l;" to "ling",
            "la" to "la", "lb" to "lou", "lc" to "liao", "ld" to "liang", "le" to "le",
            "lg" to "leng", "lh" to "lang", "li" to "li", "lj" to "lan", "lk" to "lao",
            "ll" to "lai", "lm" to "lian", "ln" to "lin", "lo" to "luo", "lp" to "lun",
            "lq" to "liu", "lr" to "luan", "ls" to "long", "lt" to "lue", "lu" to "lu",
            "lw" to "lia", "lx" to "lie", "ly" to "lv", "lz" to "lei", "m;" to "ming",
            "ma" to "ma", "mb" to "mou", "mc" to "miao", "me" to "me", "mf" to "men",
            "mg" to "meng", "mh" to "mang", "mi" to "mi", "mj" to "man", "mk" to "mao",
            "ml" to "mai", "mm" to "mian", "mn" to "min", "mo" to "mo", "mq" to "miu",
            "mu" to "mu", "mx" to "mie", "mz" to "mei", "n;" to "ning", "na" to "na",
            "nb" to "nou", "nc" to "niao", "nd" to "niang", "ne" to "ne", "nf" to "nen",
            "ng" to "neng", "nh" to "nang", "ni" to "ni", "nj" to "nan", "nk" to "nao",
            "nl" to "nai", "nm" to "nian", "nn" to "nin", "no" to "nuo", "nq" to "niu",
            "nr" to "nuan", "ns" to "nong", "nt" to "nue", "nu" to "nu", "nx" to "nie",
            "ny" to "nv", "nz" to "nei", "oa" to "a", "ob" to "ou", "oe" to "e",
            "of" to "en", "og" to "eng", "oh" to "ang", "oj" to "an", "ok" to "ao",
            "ol" to "ai", "oo" to "o", "or" to "er", "ou" to "ou", "oz" to "ei",
            "p;" to "ping", "pa" to "pa", "pb" to "pou", "pc" to "piao", "pf" to "pen",
            "pg" to "peng", "ph" to "pang", "pi" to "pi", "pj" to "pan", "pk" to "pao",
            "pl" to "pai", "pm" to "pian", "pn" to "pin", "po" to "po", "pu" to "pu",
            "px" to "pie", "pz" to "pei", "q;" to "qing", "qc" to "qiao", "qd" to "qiang",
            "qi" to "qi", "qm" to "qian", "qn" to "qin", "qp" to "qun", "qq" to "qiu",
            "qr" to "quan", "qs" to "qiong", "qt" to "que", "qu" to "qu", "qw" to "qia",
            "qx" to "qie", "qy" to "qu", "rb" to "rou", "re" to "re", "rf" to "ren",
            "rg" to "reng", "rh" to "rang", "ri" to "ri", "rj" to "ran", "rk" to "rao",
            "ro" to "ruo", "rp" to "run", "rr" to "ruan", "rs" to "rong", "ru" to "ru",
            "rv" to "rui", "rw" to "rua", "sa" to "sa", "sb" to "sou", "se" to "se",
            "sf" to "sen", "sg" to "seng", "sh" to "sang", "si" to "si", "sj" to "san",
            "sk" to "sao", "sl" to "sai", "so" to "suo", "sp" to "sun", "sr" to "suan",
            "ss" to "song", "su" to "su", "sv" to "sui", "t;" to "ting", "ta" to "ta",
            "tb" to "tou", "tc" to "tiao", "te" to "te", "tg" to "teng", "th" to "tang",
            "ti" to "ti", "tj" to "tan", "tk" to "tao", "tl" to "tai", "tm" to "tian",
            "to" to "tuo", "tp" to "tun", "tr" to "tuan", "ts" to "tong", "tu" to "tu",
            "tv" to "tui", "tx" to "tie", "tz" to "tei", "ua" to "sha", "ub" to "shou",
            "ud" to "shuang", "ue" to "she", "uf" to "shen", "ug" to "sheng", "uh" to "shang",
            "ui" to "shi", "uj" to "shan", "uk" to "shao", "ul" to "shai", "uo" to "shuo",
            "up" to "shun", "ur" to "shuan", "uu" to "shu", "uv" to "shui", "uw" to "shua",
            "uy" to "shuai", "uz" to "shei", "va" to "zha", "vb" to "zhou", "vd" to "zhuang",
            "ve" to "zhe", "vf" to "zhen", "vg" to "zheng", "vh" to "zhang", "vi" to "zhi",
            "vj" to "zhan", "vk" to "zhao", "vl" to "zhai", "vo" to "zhuo", "vp" to "zhun",
            "vr" to "zhuan", "vs" to "zhong", "vu" to "zhu", "vv" to "zhui", "vw" to "zhua",
            "vy" to "zhuai", "vz" to "zhei", "wa" to "wa", "wf" to "wen", "wg" to "weng",
            "wh" to "wang", "wj" to "wan", "wl" to "wai", "wo" to "wo", "wu" to "wu",
            "wz" to "wei", "x;" to "xing", "xc" to "xiao", "xd" to "xiang", "xi" to "xi",
            "xm" to "xian", "xn" to "xin", "xp" to "xun", "xq" to "xiu", "xr" to "xuan",
            "xs" to "xiong", "xt" to "xue", "xu" to "xu", "xw" to "xia", "xx" to "xie",
            "xy" to "xu", "y;" to "ying", "ya" to "ya", "yb" to "you", "ye" to "ye",
            "yh" to "yang", "yi" to "yi", "yj" to "yan", "yk" to "yao", "yn" to "yin",
            "yo" to "yo", "yp" to "yun", "yr" to "yuan", "ys" to "yong", "yt" to "yue",
            "yu" to "yu", "yy" to "yu", "za" to "za", "zb" to "zou", "ze" to "ze",
            "zf" to "zen", "zg" to "zeng", "zh" to "zang", "zi" to "zi", "zj" to "zan",
            "zk" to "zao", "zl" to "zai", "zo" to "zuo", "zp" to "zun", "zr" to "zuan",
            "zs" to "zong", "zu" to "zu", "zv" to "zui", "zz" to "zei",
        ),
    ) },
    /** 微软双拼（rime-ice `double_pinyin_mspy.schema.yaml`）：418 个音节 / 435 个码 */
    "MSPY" to lazy { ShuangpinTable(
        initials = mapOf(
            'b' to "b", 'c' to "c", 'd' to "d", 'f' to "f", 'g' to "g", 'h' to "h",
            'i' to "ch", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n",
            'p' to "p", 'q' to "q", 'r' to "r", 's' to "s", 't' to "t", 'u' to "sh",
            'v' to "zh", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        ),
        codes = mapOf(
            "aa" to "a", "ah" to "ang", "aj" to "an", "ak" to "ao", "al" to "ai",
            "b;" to "bing", "ba" to "ba", "bc" to "biao", "bd" to "biang", "bf" to "ben",
            "bg" to "beng", "bh" to "bang", "bi" to "bi", "bj" to "ban", "bk" to "bao",
            "bl" to "bai", "bm" to "bian", "bn" to "bin", "bo" to "bo", "bu" to "bu",
            "bx" to "bie", "bz" to "bei", "ca" to "ca", "cb" to "cou", "ce" to "ce",
            "cf" to "cen", "cg" to "ceng", "ch" to "cang", "ci" to "ci", "cj" to "can",
            "ck" to "cao", "cl" to "cai", "co" to "cuo", "cp" to "cun", "cr" to "cuan",
            "cs" to "cong", "cu" to "cu", "cv" to "cui", "cz" to "cei", "d;" to "ding",
            "da" to "da", "db" to "dou", "dc" to "diao", "de" to "de", "df" to "den",
            "dg" to "deng", "dh" to "dang", "di" to "di", "dj" to "dan", "dk" to "dao",
            "dl" to "dai", "dm" to "dian", "do" to "duo", "dp" to "dun", "dq" to "diu",
            "dr" to "duan", "ds" to "dong", "du" to "du", "dv" to "dui", "dw" to "dia",
            "dx" to "die", "dz" to "dei", "ee" to "e", "ef" to "en", "eg" to "eng",
            "er" to "er", "ez" to "ei", "fa" to "fa", "fb" to "fou", "fc" to "fiao",
            "ff" to "fen", "fg" to "feng", "fh" to "fang", "fj" to "fan", "fo" to "fo",
            "fu" to "fu", "fz" to "fei", "ga" to "ga", "gb" to "gou", "gd" to "guang",
            "ge" to "ge", "gf" to "gen", "gg" to "geng", "gh" to "gang", "gj" to "gan",
            "gk" to "gao", "gl" to "gai", "go" to "guo", "gp" to "gun", "gr" to "guan",
            "gs" to "gong", "gu" to "gu", "gv" to "gui", "gw" to "gua", "gy" to "guai",
            "gz" to "gei", "ha" to "ha", "hb" to "hou", "hd" to "huang", "he" to "he",
            "hf" to "hen", "hg" to "heng", "hh" to "hang", "hj" to "han", "hk" to "hao",
            "hl" to "hai", "hm" to "hm", "ho" to "huo", "hp" to "hun", "hr" to "huan",
            "hs" to "hong", "hu" to "hu", "hv" to "hui", "hw" to "hua", "hy" to "huai",
            "hz" to "hei", "ia" to "cha", "ib" to "chou", "id" to "chuang", "ie" to "che",
            "if" to "chen", "ig" to "cheng", "ih" to "chang", "ii" to "chi", "ij" to "chan",
            "ik" to "chao", "il" to "chai", "io" to "chuo", "ip" to "chun", "ir" to "chuan",
            "is" to "chong", "iu" to "chu", "iv" to "chui", "iw" to "chua", "iy" to "chuai",
            "j;" to "jing", "jc" to "jiao", "jd" to "jiang", "ji" to "ji", "jm" to "jian",
            "jn" to "jin", "jp" to "jun", "jq" to "jiu", "jr" to "juan", "js" to "jiong",
            "jt" to "jue", "ju" to "ju", "jv" to "jue", "jw" to "jia", "jx" to "jie",
            "jy" to "ju", "ka" to "ka", "kb" to "kou", "kd" to "kuang", "ke" to "ke",
            "kf" to "ken", "kg" to "keng", "kh" to "kang", "kj" to "kan", "kk" to "kao",
            "kl" to "kai", "ko" to "kuo", "kp" to "kun", "kr" to "kuan", "ks" to "kong",
            "ku" to "ku", "kv" to "kui", "kw" to "kua", "ky" to "kuai", "kz" to "kei",
            "l;" to "ling", "la" to "la", "lb" to "lou", "lc" to "liao", "ld" to "liang",
            "le" to "le", "lg" to "leng", "lh" to "lang", "li" to "li", "lj" to "lan",
            "lk" to "lao", "ll" to "lai", "lm" to "lian", "ln" to "lin", "lo" to "luo",
            "lp" to "lun", "lq" to "liu", "lr" to "luan", "ls" to "long", "lt" to "lue",
            "lu" to "lu", "lv" to "lue", "lw" to "lia", "lx" to "lie", "ly" to "lv",
            "lz" to "lei", "m;" to "ming", "ma" to "ma", "mb" to "mou", "mc" to "miao",
            "me" to "me", "mf" to "men", "mg" to "meng", "mh" to "mang", "mi" to "mi",
            "mj" to "man", "mk" to "mao", "ml" to "mai", "mm" to "mian", "mn" to "min",
            "mo" to "mo", "mq" to "miu", "mu" to "mu", "mx" to "mie", "mz" to "mei",
            "n;" to "ning", "na" to "na", "nb" to "nou", "nc" to "niao", "nd" to "niang",
            "ne" to "ne", "nf" to "nen", "ng" to "neng", "nh" to "nang", "ni" to "ni",
            "nj" to "nan", "nk" to "nao", "nl" to "nai", "nm" to "nian", "nn" to "nin",
            "no" to "nuo", "nq" to "niu", "nr" to "nuan", "ns" to "nong", "nt" to "nue",
            "nu" to "nu", "nv" to "nue", "nx" to "nie", "ny" to "nv", "nz" to "nei",
            "oa" to "a", "ob" to "ou", "oe" to "e", "of" to "en", "og" to "eng",
            "oh" to "ang", "oj" to "an", "ok" to "ao", "ol" to "ai", "oo" to "o",
            "or" to "er", "ou" to "ou", "oz" to "ei", "p;" to "ping", "pa" to "pa",
            "pb" to "pou", "pc" to "piao", "pf" to "pen", "pg" to "peng", "ph" to "pang",
            "pi" to "pi", "pj" to "pan", "pk" to "pao", "pl" to "pai", "pm" to "pian",
            "pn" to "pin", "po" to "po", "pu" to "pu", "px" to "pie", "pz" to "pei",
            "q;" to "qing", "qc" to "qiao", "qd" to "qiang", "qi" to "qi", "qm" to "qian",
            "qn" to "qin", "qp" to "qun", "qq" to "qiu", "qr" to "quan", "qs" to "qiong",
            "qt" to "que", "qu" to "qu", "qv" to "que", "qw" to "qia", "qx" to "qie",
            "qy" to "qu", "rb" to "rou", "re" to "re", "rf" to "ren", "rg" to "reng",
            "rh" to "rang", "ri" to "ri", "rj" to "ran", "rk" to "rao", "ro" to "ruo",
            "rp" to "run", "rr" to "ruan", "rs" to "rong", "ru" to "ru", "rv" to "rui",
            "rw" to "rua", "sa" to "sa", "sb" to "sou", "se" to "se", "sf" to "sen",
            "sg" to "seng", "sh" to "sang", "si" to "si", "sj" to "san", "sk" to "sao",
            "sl" to "sai", "so" to "suo", "sp" to "sun", "sr" to "suan", "ss" to "song",
            "su" to "su", "sv" to "sui", "t;" to "ting", "ta" to "ta", "tb" to "tou",
            "tc" to "tiao", "te" to "te", "tg" to "teng", "th" to "tang", "ti" to "ti",
            "tj" to "tan", "tk" to "tao", "tl" to "tai", "tm" to "tian", "to" to "tuo",
            "tp" to "tun", "tr" to "tuan", "ts" to "tong", "tu" to "tu", "tv" to "tui",
            "tx" to "tie", "tz" to "tei", "ua" to "sha", "ub" to "shou", "ud" to "shuang",
            "ue" to "she", "uf" to "shen", "ug" to "sheng", "uh" to "shang", "ui" to "shi",
            "uj" to "shan", "uk" to "shao", "ul" to "shai", "uo" to "shuo", "up" to "shun",
            "ur" to "shuan", "uu" to "shu", "uv" to "shui", "uw" to "shua", "uy" to "shuai",
            "uz" to "shei", "va" to "zha", "vb" to "zhou", "vd" to "zhuang", "ve" to "zhe",
            "vf" to "zhen", "vg" to "zheng", "vh" to "zhang", "vi" to "zhi", "vj" to "zhan",
            "vk" to "zhao", "vl" to "zhai", "vo" to "zhuo", "vp" to "zhun", "vr" to "zhuan",
            "vs" to "zhong", "vu" to "zhu", "vv" to "zhui", "vw" to "zhua", "vy" to "zhuai",
            "vz" to "zhei", "wa" to "wa", "wf" to "wen", "wg" to "weng", "wh" to "wang",
            "wj" to "wan", "wl" to "wai", "wo" to "wo", "wu" to "wu", "wz" to "wei",
            "x;" to "xing", "xc" to "xiao", "xd" to "xiang", "xi" to "xi", "xm" to "xian",
            "xn" to "xin", "xp" to "xun", "xq" to "xiu", "xr" to "xuan", "xs" to "xiong",
            "xt" to "xue", "xu" to "xu", "xv" to "xue", "xw" to "xia", "xx" to "xie",
            "xy" to "xu", "y;" to "ying", "ya" to "ya", "yb" to "you", "ye" to "ye",
            "yh" to "yang", "yi" to "yi", "yj" to "yan", "yk" to "yao", "yn" to "yin",
            "yo" to "yo", "yp" to "yun", "yr" to "yuan", "ys" to "yong", "yt" to "yue",
            "yu" to "yu", "yv" to "yue", "yy" to "yu", "za" to "za", "zb" to "zou",
            "ze" to "ze", "zf" to "zen", "zg" to "zeng", "zh" to "zang", "zi" to "zi",
            "zj" to "zan", "zk" to "zao", "zl" to "zai", "zo" to "zuo", "zp" to "zun",
            "zr" to "zuan", "zs" to "zong", "zu" to "zu", "zv" to "zui", "zz" to "zei",
        ),
    ) },
    /** 紫光双拼（rime-ice `double_pinyin_ziguang.schema.yaml`）：418 个音节 / 417 个码 */
    "ZIGUANG" to lazy { ShuangpinTable(
        initials = mapOf(
            'a' to "ch", 'b' to "b", 'c' to "c", 'd' to "d", 'f' to "f", 'g' to "g",
            'h' to "h", 'i' to "sh", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m",
            'n' to "n", 'p' to "p", 'q' to "q", 'r' to "r", 's' to "s", 't' to "t",
            'u' to "zh", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        ),
        codes = mapOf(
            "aa" to "cha", "ae" to "che", "ag" to "chuang", "ah" to "chong", "ai" to "chi",
            "al" to "chuan", "am" to "chun", "an" to "chui", "ao" to "chuo", "ap" to "chai",
            "aq" to "chao", "ar" to "chan", "as" to "chang", "at" to "cheng", "au" to "chu",
            "aw" to "chen", "ax" to "chua", "ay" to "chuai", "az" to "chou", "b;" to "bing",
            "ba" to "ba", "bb" to "biao", "bd" to "bie", "bf" to "bian", "bg" to "biang",
            "bi" to "bi", "bk" to "bei", "bo" to "bo", "bp" to "bai", "bq" to "bao",
            "br" to "ban", "bs" to "bang", "bt" to "beng", "bu" to "bu", "bw" to "ben",
            "by" to "bin", "ca" to "ca", "ce" to "ce", "ch" to "cong", "ci" to "ci",
            "ck" to "cei", "cl" to "cuan", "cm" to "cun", "cn" to "cui", "co" to "cuo",
            "cp" to "cai", "cq" to "cao", "cr" to "can", "cs" to "cang", "ct" to "ceng",
            "cu" to "cu", "cw" to "cen", "cz" to "cou", "d;" to "ding", "da" to "da",
            "db" to "diao", "dd" to "die", "de" to "de", "df" to "dian", "dh" to "dong",
            "di" to "di", "dj" to "diu", "dk" to "dei", "dl" to "duan", "dm" to "dun",
            "dn" to "dui", "do" to "duo", "dp" to "dai", "dq" to "dao", "dr" to "dan",
            "ds" to "dang", "dt" to "deng", "du" to "du", "dw" to "den", "dx" to "dia",
            "dz" to "dou", "fa" to "fa", "fb" to "fiao", "fk" to "fei", "fo" to "fo",
            "fr" to "fan", "fs" to "fang", "ft" to "feng", "fu" to "fu", "fw" to "fen",
            "fz" to "fou", "ga" to "ga", "ge" to "ge", "gg" to "guang", "gh" to "gong",
            "gk" to "gei", "gl" to "guan", "gm" to "gun", "gn" to "gui", "go" to "guo",
            "gp" to "gai", "gq" to "gao", "gr" to "gan", "gs" to "gang", "gt" to "geng",
            "gu" to "gu", "gw" to "gen", "gx" to "gua", "gy" to "guai", "gz" to "gou",
            "ha" to "ha", "he" to "he", "hg" to "huang", "hh" to "hong", "hk" to "hei",
            "hl" to "huan", "hm" to "hun", "hn" to "hui", "ho" to "huo", "hp" to "hai",
            "hq" to "hao", "hr" to "han", "hs" to "hang", "ht" to "heng", "hu" to "hu",
            "hw" to "hen", "hx" to "hua", "hy" to "huai", "hz" to "hou", "ia" to "sha",
            "ie" to "she", "ig" to "shuang", "ii" to "shi", "ik" to "shei", "il" to "shuan",
            "im" to "shun", "in" to "shui", "io" to "shuo", "ip" to "shai", "iq" to "shao",
            "ir" to "shan", "is" to "shang", "it" to "sheng", "iu" to "shu", "iw" to "shen",
            "ix" to "shua", "iy" to "shuai", "iz" to "shou", "j;" to "jing", "jb" to "jiao",
            "jd" to "jie", "jf" to "jian", "jg" to "jiang", "jh" to "jiong", "ji" to "ji",
            "jj" to "jiu", "jl" to "juan", "jm" to "jun", "jn" to "jue", "ju" to "ju",
            "jv" to "ju", "jx" to "jia", "jy" to "jin", "ka" to "ka", "ke" to "ke",
            "kg" to "kuang", "kh" to "kong", "kk" to "kei", "kl" to "kuan", "km" to "kun",
            "kn" to "kui", "ko" to "kuo", "kp" to "kai", "kq" to "kao", "kr" to "kan",
            "ks" to "kang", "kt" to "keng", "ku" to "ku", "kw" to "ken", "kx" to "kua",
            "ky" to "kuai", "kz" to "kou", "l;" to "ling", "la" to "la", "lb" to "liao",
            "ld" to "lie", "le" to "le", "lf" to "lian", "lg" to "liang", "lh" to "long",
            "li" to "li", "lj" to "liu", "lk" to "lei", "ll" to "luan", "lm" to "lun",
            "ln" to "lue", "lo" to "luo", "lp" to "lai", "lq" to "lao", "lr" to "lan",
            "ls" to "lang", "lt" to "leng", "lu" to "lu", "lv" to "lv", "lx" to "lia",
            "ly" to "lin", "lz" to "lou", "m;" to "ming", "ma" to "ma", "mb" to "miao",
            "md" to "mie", "me" to "me", "mf" to "mian", "mi" to "mi", "mj" to "miu",
            "mk" to "mei", "mo" to "mo", "mp" to "mai", "mq" to "mao", "mr" to "man",
            "ms" to "mang", "mt" to "meng", "mu" to "mu", "mw" to "men", "my" to "min",
            "mz" to "mou", "n;" to "ning", "na" to "na", "nb" to "niao", "nd" to "nie",
            "ne" to "ne", "nf" to "nian", "ng" to "niang", "nh" to "nong", "ni" to "ni",
            "nj" to "niu", "nk" to "nei", "nl" to "nuan", "nn" to "nue", "no" to "nuo",
            "np" to "nai", "nq" to "nao", "nr" to "nan", "ns" to "nang", "nt" to "neng",
            "nu" to "nu", "nv" to "nv", "nw" to "nen", "ny" to "nin", "nz" to "nou",
            "oa" to "a", "oe" to "e", "oj" to "er", "ok" to "ei", "oo" to "o",
            "op" to "ai", "oq" to "ao", "or" to "an", "os" to "ang", "ot" to "eng",
            "ow" to "en", "oz" to "ou", "p;" to "ping", "pa" to "pa", "pb" to "piao",
            "pd" to "pie", "pf" to "pian", "pi" to "pi", "pk" to "pei", "po" to "po",
            "pp" to "pai", "pq" to "pao", "pr" to "pan", "ps" to "pang", "pt" to "peng",
            "pu" to "pu", "pw" to "pen", "py" to "pin", "pz" to "pou", "q;" to "qing",
            "qb" to "qiao", "qd" to "qie", "qf" to "qian", "qg" to "qiang", "qh" to "qiong",
            "qi" to "qi", "qj" to "qiu", "ql" to "quan", "qm" to "qun", "qn" to "que",
            "qu" to "qu", "qv" to "qu", "qx" to "qia", "qy" to "qin", "re" to "re",
            "rh" to "rong", "ri" to "ri", "rl" to "ruan", "rm" to "run", "rn" to "rui",
            "ro" to "ruo", "rq" to "rao", "rr" to "ran", "rs" to "rang", "rt" to "reng",
            "ru" to "ru", "rw" to "ren", "rx" to "rua", "rz" to "rou", "sa" to "sa",
            "se" to "se", "sh" to "song", "si" to "si", "sl" to "suan", "sm" to "sun",
            "sn" to "sui", "so" to "suo", "sp" to "sai", "sq" to "sao", "sr" to "san",
            "ss" to "sang", "st" to "seng", "su" to "su", "sw" to "sen", "sz" to "sou",
            "t;" to "ting", "ta" to "ta", "tb" to "tiao", "td" to "tie", "te" to "te",
            "tf" to "tian", "th" to "tong", "ti" to "ti", "tk" to "tei", "tl" to "tuan",
            "tm" to "tun", "tn" to "tui", "to" to "tuo", "tp" to "tai", "tq" to "tao",
            "tr" to "tan", "ts" to "tang", "tt" to "teng", "tu" to "tu", "tz" to "tou",
            "ua" to "zha", "ue" to "zhe", "ug" to "zhuang", "uh" to "zhong", "ui" to "zhi",
            "uk" to "zhei", "ul" to "zhuan", "um" to "zhun", "un" to "zhui", "uo" to "zhuo",
            "up" to "zhai", "uq" to "zhao", "ur" to "zhan", "us" to "zhang", "ut" to "zheng",
            "uu" to "zhu", "uw" to "zhen", "ux" to "zhua", "uy" to "zhuai", "uz" to "zhou",
            "wa" to "wa", "wk" to "wei", "wo" to "wo", "wp" to "wai", "wr" to "wan",
            "ws" to "wang", "wt" to "weng", "wu" to "wu", "ww" to "wen", "x;" to "xing",
            "xb" to "xiao", "xd" to "xie", "xf" to "xian", "xg" to "xiang", "xh" to "xiong",
            "xi" to "xi", "xj" to "xiu", "xl" to "xuan", "xm" to "xun", "xn" to "xue",
            "xu" to "xu", "xv" to "xu", "xx" to "xia", "xy" to "xin", "y;" to "ying",
            "ya" to "ya", "ye" to "ye", "yh" to "yong", "yi" to "yi", "yl" to "yuan",
            "ym" to "yun", "yn" to "yue", "yo" to "yo", "yq" to "yao", "yr" to "yan",
            "ys" to "yang", "yu" to "yu", "yv" to "yu", "yy" to "yin", "yz" to "you",
            "za" to "za", "ze" to "ze", "zh" to "zong", "zi" to "zi", "zk" to "zei",
            "zl" to "zuan", "zm" to "zun", "zn" to "zui", "zo" to "zuo", "zp" to "zai",
            "zq" to "zao", "zr" to "zan", "zs" to "zang", "zt" to "zeng", "zu" to "zu",
            "zw" to "zen", "zz" to "zou",
        ),
    ) },
    /** 智能ABC双拼（rime-ice `double_pinyin_abc.schema.yaml`）：418 个音节 / 413 个码 */
    "ABC" to lazy { ShuangpinTable(
        initials = mapOf(
            'a' to "zh", 'b' to "b", 'c' to "c", 'd' to "d", 'e' to "ch", 'f' to "f",
            'g' to "g", 'h' to "h", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m",
            'n' to "n", 'p' to "p", 'q' to "q", 'r' to "r", 's' to "s", 't' to "t",
            'v' to "sh", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        ),
        codes = mapOf(
            "aa" to "zha", "ab" to "zhou", "ac" to "zhuai", "ad" to "zhua", "ae" to "zhe",
            "af" to "zhen", "ag" to "zheng", "ah" to "zhang", "ai" to "zhi", "aj" to "zhan",
            "ak" to "zhao", "al" to "zhai", "am" to "zhui", "an" to "zhun", "ao" to "zhuo",
            "ap" to "zhuan", "aq" to "zhei", "as" to "zhong", "at" to "zhuang", "au" to "zhu",
            "ba" to "ba", "bc" to "bin", "bf" to "ben", "bg" to "beng", "bh" to "bang",
            "bi" to "bi", "bj" to "ban", "bk" to "bao", "bl" to "bai", "bo" to "bo",
            "bq" to "bei", "bt" to "biang", "bu" to "bu", "bw" to "bian", "bx" to "bie",
            "by" to "bing", "bz" to "biao", "ca" to "ca", "cb" to "cou", "ce" to "ce",
            "cf" to "cen", "cg" to "ceng", "ch" to "cang", "ci" to "ci", "cj" to "can",
            "ck" to "cao", "cl" to "cai", "cm" to "cui", "cn" to "cun", "co" to "cuo",
            "cp" to "cuan", "cq" to "cei", "cs" to "cong", "cu" to "cu", "da" to "da",
            "db" to "dou", "dd" to "dia", "de" to "de", "df" to "den", "dg" to "deng",
            "dh" to "dang", "di" to "di", "dj" to "dan", "dk" to "dao", "dl" to "dai",
            "dm" to "dui", "dn" to "dun", "do" to "duo", "dp" to "duan", "dq" to "dei",
            "dr" to "diu", "ds" to "dong", "du" to "du", "dw" to "dian", "dx" to "die",
            "dy" to "ding", "dz" to "diao", "ea" to "cha", "eb" to "chou", "ec" to "chuai",
            "ed" to "chua", "ee" to "che", "ef" to "chen", "eg" to "cheng", "eh" to "chang",
            "ei" to "chi", "ej" to "chan", "ek" to "chao", "el" to "chai", "em" to "chui",
            "en" to "chun", "eo" to "chuo", "ep" to "chuan", "es" to "chong", "et" to "chuang",
            "eu" to "chu", "fa" to "fa", "fb" to "fou", "ff" to "fen", "fg" to "feng",
            "fh" to "fang", "fj" to "fan", "fo" to "fo", "fq" to "fei", "fu" to "fu",
            "fz" to "fiao", "ga" to "ga", "gb" to "gou", "gc" to "guai", "gd" to "gua",
            "ge" to "ge", "gf" to "gen", "gg" to "geng", "gh" to "gang", "gj" to "gan",
            "gk" to "gao", "gl" to "gai", "gm" to "gui", "gn" to "gun", "go" to "guo",
            "gp" to "guan", "gq" to "gei", "gs" to "gong", "gt" to "guang", "gu" to "gu",
            "ha" to "ha", "hb" to "hou", "hc" to "huai", "hd" to "hua", "he" to "he",
            "hf" to "hen", "hg" to "heng", "hh" to "hang", "hj" to "han", "hk" to "hao",
            "hl" to "hai", "hm" to "hui", "hn" to "hun", "ho" to "huo", "hp" to "huan",
            "hq" to "hei", "hs" to "hong", "ht" to "huang", "hu" to "hu", "jc" to "jin",
            "jd" to "jia", "ji" to "ji", "jm" to "jue", "jn" to "jun", "jp" to "juan",
            "jr" to "jiu", "js" to "jiong", "jt" to "jiang", "ju" to "ju", "jw" to "jian",
            "jx" to "jie", "jy" to "jing", "jz" to "jiao", "ka" to "ka", "kb" to "kou",
            "kc" to "kuai", "kd" to "kua", "ke" to "ke", "kf" to "ken", "kg" to "keng",
            "kh" to "kang", "kj" to "kan", "kk" to "kao", "kl" to "kai", "km" to "kui",
            "kn" to "kun", "ko" to "kuo", "kp" to "kuan", "kq" to "kei", "ks" to "kong",
            "kt" to "kuang", "ku" to "ku", "la" to "la", "lb" to "lou", "lc" to "lin",
            "ld" to "lia", "le" to "le", "lg" to "leng", "lh" to "lang", "li" to "li",
            "lj" to "lan", "lk" to "lao", "ll" to "lai", "lm" to "lue", "ln" to "lun",
            "lo" to "luo", "lp" to "luan", "lq" to "lei", "lr" to "liu", "ls" to "long",
            "lt" to "liang", "lu" to "lu", "lv" to "lv", "lw" to "lian", "lx" to "lie",
            "ly" to "ling", "lz" to "liao", "ma" to "ma", "mb" to "mou", "mc" to "min",
            "me" to "me", "mf" to "men", "mg" to "meng", "mh" to "mang", "mi" to "mi",
            "mj" to "man", "mk" to "mao", "ml" to "mai", "mo" to "mo", "mq" to "mei",
            "mr" to "miu", "mu" to "mu", "mw" to "mian", "mx" to "mie", "my" to "ming",
            "mz" to "miao", "na" to "na", "nb" to "nou", "nc" to "nin", "ne" to "ne",
            "nf" to "nen", "ng" to "neng", "nh" to "nang", "ni" to "ni", "nj" to "nan",
            "nk" to "nao", "nl" to "nai", "nm" to "nue", "no" to "nuo", "np" to "nuan",
            "nq" to "nei", "nr" to "niu", "ns" to "nong", "nt" to "niang", "nu" to "nu",
            "nv" to "nv", "nw" to "nian", "nx" to "nie", "ny" to "ning", "nz" to "niao",
            "oa" to "a", "ob" to "ou", "oe" to "e", "of" to "en", "og" to "eng",
            "oh" to "ang", "oj" to "an", "ok" to "ao", "ol" to "ai", "oo" to "o",
            "oq" to "ei", "or" to "er", "pa" to "pa", "pb" to "pou", "pc" to "pin",
            "pf" to "pen", "pg" to "peng", "ph" to "pang", "pi" to "pi", "pj" to "pan",
            "pk" to "pao", "pl" to "pai", "po" to "po", "pq" to "pei", "pu" to "pu",
            "pw" to "pian", "px" to "pie", "py" to "ping", "pz" to "piao", "qc" to "qin",
            "qd" to "qia", "qi" to "qi", "qm" to "que", "qn" to "qun", "qp" to "quan",
            "qr" to "qiu", "qs" to "qiong", "qt" to "qiang", "qu" to "qu", "qw" to "qian",
            "qx" to "qie", "qy" to "qing", "qz" to "qiao", "rb" to "rou", "rd" to "rua",
            "re" to "re", "rf" to "ren", "rg" to "reng", "rh" to "rang", "ri" to "ri",
            "rj" to "ran", "rk" to "rao", "rm" to "rui", "rn" to "run", "ro" to "ruo",
            "rp" to "ruan", "rs" to "rong", "ru" to "ru", "sa" to "sa", "sb" to "sou",
            "se" to "se", "sf" to "sen", "sg" to "seng", "sh" to "sang", "si" to "si",
            "sj" to "san", "sk" to "sao", "sl" to "sai", "sm" to "sui", "sn" to "sun",
            "so" to "suo", "sp" to "suan", "ss" to "song", "su" to "su", "ta" to "ta",
            "tb" to "tou", "te" to "te", "tg" to "teng", "th" to "tang", "ti" to "ti",
            "tj" to "tan", "tk" to "tao", "tl" to "tai", "tm" to "tui", "tn" to "tun",
            "to" to "tuo", "tp" to "tuan", "tq" to "tei", "ts" to "tong", "tu" to "tu",
            "tw" to "tian", "tx" to "tie", "ty" to "ting", "tz" to "tiao", "va" to "sha",
            "vb" to "shou", "vc" to "shuai", "vd" to "shua", "ve" to "she", "vf" to "shen",
            "vg" to "sheng", "vh" to "shang", "vi" to "shi", "vj" to "shan", "vk" to "shao",
            "vl" to "shai", "vm" to "shui", "vn" to "shun", "vo" to "shuo", "vp" to "shuan",
            "vq" to "shei", "vt" to "shuang", "vu" to "shu", "wa" to "wa", "wf" to "wen",
            "wg" to "weng", "wh" to "wang", "wj" to "wan", "wl" to "wai", "wo" to "wo",
            "wq" to "wei", "wu" to "wu", "xc" to "xin", "xd" to "xia", "xi" to "xi",
            "xm" to "xue", "xn" to "xun", "xp" to "xuan", "xr" to "xiu", "xs" to "xiong",
            "xt" to "xiang", "xu" to "xu", "xw" to "xian", "xx" to "xie", "xy" to "xing",
            "xz" to "xiao", "ya" to "ya", "yb" to "you", "yc" to "yin", "ye" to "ye",
            "yh" to "yang", "yi" to "yi", "yj" to "yan", "yk" to "yao", "ym" to "yue",
            "yn" to "yun", "yo" to "yo", "yp" to "yuan", "ys" to "yong", "yu" to "yu",
            "yy" to "ying", "za" to "za", "zb" to "zou", "ze" to "ze", "zf" to "zen",
            "zg" to "zeng", "zh" to "zang", "zi" to "zi", "zj" to "zan", "zk" to "zao",
            "zl" to "zai", "zm" to "zui", "zn" to "zun", "zo" to "zuo", "zp" to "zuan",
            "zq" to "zei", "zs" to "zong", "zu" to "zu",
        ),
    ) },
    /** 拼音加加双拼（rime-ice `double_pinyin_jiajia.schema.yaml`）：418 个音节 / 429 个码 */
    "JIAJIA" to lazy { ShuangpinTable(
        initials = mapOf(
            'b' to "b", 'c' to "c", 'd' to "d", 'f' to "f", 'g' to "g", 'h' to "h",
            'i' to "sh", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m", 'n' to "n",
            'p' to "p", 'q' to "q", 'r' to "r", 's' to "s", 't' to "t", 'u' to "ch",
            'v' to "zh", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        ),
        codes = mapOf(
            "aa" to "a", "ad" to "ao", "af" to "an", "ag" to "ang", "as" to "ai",
            "ba" to "ba", "bd" to "bao", "bf" to "ban", "bg" to "bang", "bh" to "biang",
            "bi" to "bi", "bj" to "bian", "bk" to "biao", "bl" to "bin", "bm" to "bie",
            "bo" to "bo", "bq" to "bing", "br" to "ben", "bs" to "bai", "bt" to "beng",
            "bu" to "bu", "bw" to "bei", "ca" to "ca", "cc" to "cuan", "cd" to "cao",
            "ce" to "ce", "cf" to "can", "cg" to "cang", "ci" to "ci", "co" to "cuo",
            "cp" to "cou", "cr" to "cen", "cs" to "cai", "ct" to "ceng", "cu" to "cu",
            "cv" to "cui", "cw" to "cei", "cy" to "cong", "cz" to "cun", "da" to "da",
            "db" to "dia", "dc" to "duan", "dd" to "dao", "de" to "de", "df" to "dan",
            "dg" to "dang", "di" to "di", "dj" to "dian", "dk" to "diao", "dm" to "die",
            "dn" to "diu", "do" to "duo", "dp" to "dou", "dq" to "ding", "dr" to "den",
            "ds" to "dai", "dt" to "deng", "du" to "du", "dv" to "dui", "dw" to "dei",
            "dy" to "dong", "dz" to "dun", "ee" to "e", "eq" to "er", "er" to "en",
            "et" to "eng", "ew" to "ei", "fa" to "fa", "ff" to "fan", "fg" to "fang",
            "fk" to "fiao", "fo" to "fo", "fp" to "fou", "fr" to "fen", "ft" to "feng",
            "fu" to "fu", "fw" to "fei", "ga" to "ga", "gb" to "gua", "gc" to "guan",
            "gd" to "gao", "ge" to "ge", "gf" to "gan", "gg" to "gang", "gh" to "guang",
            "go" to "guo", "gp" to "gou", "gr" to "gen", "gs" to "gai", "gt" to "geng",
            "gu" to "gu", "gv" to "gui", "gw" to "gei", "gx" to "guai", "gy" to "gong",
            "gz" to "gun", "ha" to "ha", "hb" to "hua", "hc" to "huan", "hd" to "hao",
            "he" to "he", "hf" to "han", "hg" to "hang", "hh" to "huang", "hm" to "hm",
            "ho" to "huo", "hp" to "hou", "hr" to "hen", "hs" to "hai", "ht" to "heng",
            "hu" to "hu", "hv" to "hui", "hw" to "hei", "hx" to "huai", "hy" to "hong",
            "hz" to "hun", "ia" to "sha", "ib" to "shua", "ic" to "shuan", "id" to "shao",
            "ie" to "she", "if" to "shan", "ig" to "shang", "ih" to "shuang", "ii" to "shi",
            "io" to "shuo", "ip" to "shou", "ir" to "shen", "is" to "shai", "it" to "sheng",
            "iu" to "shu", "iv" to "shui", "iw" to "shei", "ix" to "shuai", "iz" to "shun",
            "jb" to "jia", "jc" to "juan", "jh" to "jiang", "ji" to "ji", "jj" to "jian",
            "jk" to "jiao", "jl" to "jin", "jm" to "jie", "jn" to "jiu", "jq" to "jing",
            "ju" to "ju", "jv" to "ju", "jx" to "jue", "jy" to "jiong", "jz" to "jun",
            "ka" to "ka", "kb" to "kua", "kc" to "kuan", "kd" to "kao", "ke" to "ke",
            "kf" to "kan", "kg" to "kang", "kh" to "kuang", "ko" to "kuo", "kp" to "kou",
            "kr" to "ken", "ks" to "kai", "kt" to "keng", "ku" to "ku", "kv" to "kui",
            "kw" to "kei", "kx" to "kuai", "ky" to "kong", "kz" to "kun", "la" to "la",
            "lb" to "lia", "lc" to "luan", "ld" to "lao", "le" to "le", "lf" to "lan",
            "lg" to "lang", "lh" to "liang", "li" to "li", "lj" to "lian", "lk" to "liao",
            "ll" to "lin", "lm" to "lie", "ln" to "liu", "lo" to "luo", "lp" to "lou",
            "lq" to "ling", "ls" to "lai", "lt" to "leng", "lu" to "lu", "lv" to "lv",
            "lw" to "lei", "lx" to "lue", "ly" to "long", "lz" to "lun", "ma" to "ma",
            "md" to "mao", "me" to "me", "mf" to "man", "mg" to "mang", "mi" to "mi",
            "mj" to "mian", "mk" to "miao", "ml" to "min", "mm" to "mie", "mn" to "miu",
            "mo" to "mo", "mp" to "mou", "mq" to "ming", "mr" to "men", "ms" to "mai",
            "mt" to "meng", "mu" to "mu", "mw" to "mei", "na" to "na", "nc" to "nuan",
            "nd" to "nao", "ne" to "ne", "nf" to "nan", "ng" to "nang", "nh" to "niang",
            "ni" to "ni", "nj" to "nian", "nk" to "niao", "nl" to "nin", "nm" to "nie",
            "nn" to "niu", "no" to "nuo", "np" to "nou", "nq" to "ning", "nr" to "nen",
            "ns" to "nai", "nt" to "neng", "nu" to "nu", "nv" to "nv", "nw" to "nei",
            "nx" to "nue", "ny" to "nong", "oa" to "a", "od" to "ao", "oe" to "e",
            "of" to "an", "og" to "ang", "oo" to "o", "op" to "ou", "oq" to "er",
            "or" to "en", "os" to "ai", "ot" to "eng", "ou" to "ou", "ow" to "ei",
            "pa" to "pa", "pd" to "pao", "pf" to "pan", "pg" to "pang", "pi" to "pi",
            "pj" to "pian", "pk" to "piao", "pl" to "pin", "pm" to "pie", "po" to "po",
            "pp" to "pou", "pq" to "ping", "pr" to "pen", "ps" to "pai", "pt" to "peng",
            "pu" to "pu", "pw" to "pei", "qb" to "qia", "qc" to "quan", "qh" to "qiang",
            "qi" to "qi", "qj" to "qian", "qk" to "qiao", "ql" to "qin", "qm" to "qie",
            "qn" to "qiu", "qq" to "qing", "qu" to "qu", "qv" to "qu", "qx" to "que",
            "qy" to "qiong", "qz" to "qun", "rb" to "rua", "rc" to "ruan", "rd" to "rao",
            "re" to "re", "rf" to "ran", "rg" to "rang", "ri" to "ri", "ro" to "ruo",
            "rp" to "rou", "rr" to "ren", "rt" to "reng", "ru" to "ru", "rv" to "rui",
            "ry" to "rong", "rz" to "run", "sa" to "sa", "sc" to "suan", "sd" to "sao",
            "se" to "se", "sf" to "san", "sg" to "sang", "si" to "si", "so" to "suo",
            "sp" to "sou", "sr" to "sen", "ss" to "sai", "st" to "seng", "su" to "su",
            "sv" to "sui", "sy" to "song", "sz" to "sun", "ta" to "ta", "tc" to "tuan",
            "td" to "tao", "te" to "te", "tf" to "tan", "tg" to "tang", "ti" to "ti",
            "tj" to "tian", "tk" to "tiao", "tm" to "tie", "to" to "tuo", "tp" to "tou",
            "tq" to "ting", "ts" to "tai", "tt" to "teng", "tu" to "tu", "tv" to "tui",
            "tw" to "tei", "ty" to "tong", "tz" to "tun", "ua" to "cha", "ub" to "chua",
            "uc" to "chuan", "ud" to "chao", "ue" to "che", "uf" to "chan", "ug" to "chang",
            "uh" to "chuang", "ui" to "chi", "uo" to "chuo", "up" to "chou", "ur" to "chen",
            "us" to "chai", "ut" to "cheng", "uu" to "chu", "uv" to "chui", "ux" to "chuai",
            "uy" to "chong", "uz" to "chun", "va" to "zha", "vb" to "zhua", "vc" to "zhuan",
            "vd" to "zhao", "ve" to "zhe", "vf" to "zhan", "vg" to "zhang", "vh" to "zhuang",
            "vi" to "zhi", "vo" to "zhuo", "vp" to "zhou", "vr" to "zhen", "vs" to "zhai",
            "vt" to "zheng", "vu" to "zhu", "vv" to "zhui", "vw" to "zhei", "vx" to "zhuai",
            "vy" to "zhong", "vz" to "zhun", "wa" to "wa", "wf" to "wan", "wg" to "wang",
            "wo" to "wo", "wr" to "wen", "ws" to "wai", "wt" to "weng", "wu" to "wu",
            "ww" to "wei", "xb" to "xia", "xc" to "xuan", "xh" to "xiang", "xi" to "xi",
            "xj" to "xian", "xk" to "xiao", "xl" to "xin", "xm" to "xie", "xn" to "xiu",
            "xq" to "xing", "xu" to "xu", "xv" to "xu", "xx" to "xue", "xy" to "xiong",
            "xz" to "xun", "ya" to "ya", "yc" to "yuan", "yd" to "yao", "ye" to "ye",
            "yf" to "yan", "yg" to "yang", "yi" to "yi", "yl" to "yin", "yo" to "yo",
            "yp" to "you", "yq" to "ying", "yu" to "yu", "yv" to "yu", "yx" to "yue",
            "yy" to "yong", "yz" to "yun", "za" to "za", "zc" to "zuan", "zd" to "zao",
            "ze" to "ze", "zf" to "zan", "zg" to "zang", "zi" to "zi", "zo" to "zuo",
            "zp" to "zou", "zr" to "zen", "zs" to "zai", "zt" to "zeng", "zu" to "zu",
            "zv" to "zui", "zw" to "zei", "zy" to "zong", "zz" to "zun",
        ),
    ) },
    /** 首道双拼（公开规格直表编码）：416 个音节 / 412 个码 */
    "SHOUDAO" to lazy { ShuangpinTable(
        initials = mapOf(
            'b' to "b", 'c' to "c", 'd' to "d", 'e' to "sh", 'f' to "f", 'g' to "g",
            'h' to "h", 'i' to "ch", 'j' to "j", 'k' to "k", 'l' to "l", 'm' to "m",
            'n' to "n", 'p' to "p", 'q' to "q", 'r' to "r", 's' to "s", 't' to "t",
            'v' to "zh", 'w' to "w", 'x' to "x", 'y' to "y", 'z' to "z",
        ),
        codes = mapOf(
            "aa" to "a", "ai" to "ai", "an" to "an", "ao" to "ao", "ay" to "ang",
            "ba" to "ba", "bc" to "bin", "bd" to "bao", "bf" to "beng", "bg" to "bing",
            "bi" to "bi", "bj" to "ban", "bk" to "ben", "bl" to "bai", "bm" to "bei",
            "bn" to "bian", "bo" to "bo", "bp" to "biao", "br" to "bie", "bu" to "bu",
            "bx" to "biang", "by" to "bang", "ca" to "ca", "cd" to "cao", "ce" to "ce",
            "cf" to "ceng", "ch" to "cong", "ci" to "ci", "cj" to "can", "ck" to "cen",
            "cl" to "cai", "cm" to "cei", "co" to "cuo", "cs" to "cou", "ct" to "cuan",
            "cu" to "cu", "cv" to "cui", "cy" to "cang", "cz" to "cun", "da" to "da",
            "dd" to "dao", "de" to "de", "df" to "deng", "dg" to "ding", "dh" to "dong",
            "di" to "di", "dj" to "dan", "dk" to "den", "dl" to "dai", "dm" to "dei",
            "dn" to "dian", "do" to "duo", "dp" to "diao", "dq" to "diu", "dr" to "die",
            "ds" to "dou", "dt" to "duan", "du" to "du", "dv" to "dui", "dy" to "dang",
            "dz" to "dun", "ea" to "sha", "ed" to "shao", "ee" to "she", "ef" to "sheng",
            "eg" to "shuai", "ei" to "shi", "ej" to "shan", "ek" to "shen", "el" to "shai",
            "em" to "shei", "en" to "en", "eo" to "shuo", "er" to "er", "es" to "shou",
            "et" to "shuan", "eu" to "shu", "ev" to "shui", "ew" to "shua", "ex" to "shuang",
            "ey" to "shang", "ez" to "shun", "fa" to "fa", "ff" to "feng", "fj" to "fan",
            "fk" to "fen", "fm" to "fei", "fo" to "fo", "fp" to "fiao", "fs" to "fou",
            "fu" to "fu", "fy" to "fang", "ga" to "ga", "gd" to "gao", "ge" to "ge",
            "gf" to "geng", "gg" to "guai", "gh" to "gong", "gj" to "gan", "gk" to "gen",
            "gl" to "gai", "gm" to "gei", "go" to "guo", "gs" to "gou", "gt" to "guan",
            "gu" to "gu", "gv" to "gui", "gw" to "gua", "gx" to "guang", "gy" to "gang",
            "gz" to "gun", "ha" to "ha", "hd" to "hao", "he" to "he", "hf" to "heng",
            "hg" to "huai", "hh" to "hong", "hj" to "han", "hk" to "hen", "hl" to "hai",
            "hm" to "hei", "ho" to "huo", "hs" to "hou", "ht" to "huan", "hu" to "hu",
            "hv" to "hui", "hw" to "hua", "hx" to "huang", "hy" to "hang", "hz" to "hun",
            "ia" to "cha", "id" to "chao", "ie" to "che", "if" to "cheng", "ig" to "chuai",
            "ih" to "chong", "ii" to "chi", "ij" to "chan", "ik" to "chen", "il" to "chai",
            "io" to "chuo", "is" to "chou", "it" to "chuan", "iu" to "chu", "iv" to "chui",
            "iw" to "chua", "ix" to "chuang", "iy" to "chang", "iz" to "chun", "jc" to "jin",
            "jg" to "jing", "jh" to "jiong", "ji" to "ji", "jk" to "jia", "jl" to "jue",
            "jn" to "jian", "jp" to "jiao", "jq" to "jiu", "jr" to "jie", "jt" to "juan",
            "ju" to "ju", "jx" to "jiang", "jz" to "jun", "ka" to "ka", "kd" to "kao",
            "ke" to "ke", "kf" to "keng", "kg" to "kuai", "kh" to "kong", "kj" to "kan",
            "kk" to "ken", "kl" to "kai", "km" to "kei", "ko" to "kuo", "ks" to "kou",
            "kt" to "kuan", "ku" to "ku", "kv" to "kui", "kw" to "kua", "kx" to "kuang",
            "ky" to "kang", "kz" to "kun", "la" to "la", "lb" to "lve", "lc" to "lin",
            "ld" to "lao", "le" to "le", "lf" to "leng", "lg" to "ling", "lh" to "long",
            "li" to "li", "lj" to "lan", "lk" to "lia", "ll" to "lai", "lm" to "lei",
            "ln" to "lian", "lo" to "luo", "lp" to "liao", "lq" to "liu", "lr" to "lie",
            "ls" to "lou", "lt" to "luan", "lu" to "lu", "lv" to "lv", "lx" to "liang",
            "ly" to "lang", "lz" to "lun", "ma" to "ma", "mc" to "min", "md" to "mao",
            "me" to "me", "mf" to "meng", "mg" to "ming", "mi" to "mi", "mj" to "man",
            "mk" to "men", "ml" to "mai", "mm" to "mei", "mn" to "mian", "mo" to "mo",
            "mp" to "miao", "mq" to "miu", "mr" to "mie", "ms" to "mou", "mu" to "mu",
            "my" to "mang", "na" to "na", "nb" to "nve", "nc" to "nin", "nd" to "nao",
            "ne" to "ne", "nf" to "neng", "ng" to "ning", "nh" to "nong", "ni" to "ni",
            "nj" to "nan", "nk" to "nen", "nl" to "nai", "nm" to "nei", "nn" to "nian",
            "no" to "nuo", "np" to "niao", "nq" to "niu", "nr" to "nie", "ns" to "nou",
            "nt" to "nuan", "nu" to "nu", "nv" to "nv", "nx" to "niang", "ny" to "nang",
            "oo" to "o", "ou" to "ou", "pa" to "pa", "pc" to "pin", "pd" to "pao",
            "pf" to "peng", "pg" to "ping", "pi" to "pi", "pj" to "pan", "pk" to "pen",
            "pl" to "pai", "pm" to "pei", "pn" to "pian", "po" to "po", "pp" to "piao",
            "pr" to "pie", "ps" to "pou", "pu" to "pu", "py" to "pang", "qc" to "qin",
            "qg" to "qing", "qh" to "qiong", "qi" to "qi", "qk" to "qia", "ql" to "que",
            "qn" to "qian", "qp" to "qiao", "qq" to "qiu", "qr" to "qie", "qt" to "quan",
            "qu" to "qu", "qx" to "qiang", "qz" to "qun", "rd" to "rao", "re" to "re",
            "rf" to "reng", "rh" to "rong", "ri" to "ri", "rj" to "ran", "rk" to "ren",
            "ro" to "ruo", "rs" to "rou", "rt" to "ruan", "ru" to "ru", "rv" to "rui",
            "rw" to "rua", "ry" to "rang", "rz" to "run", "sa" to "sa", "sd" to "sao",
            "se" to "se", "sf" to "seng", "sh" to "song", "si" to "si", "sj" to "san",
            "sk" to "sen", "sl" to "sai", "so" to "suo", "ss" to "sou", "st" to "suan",
            "su" to "su", "sv" to "sui", "sy" to "sang", "sz" to "sun", "ta" to "ta",
            "td" to "tao", "te" to "te", "tf" to "teng", "tg" to "ting", "th" to "tong",
            "ti" to "ti", "tj" to "tan", "tl" to "tai", "tm" to "tei", "tn" to "tian",
            "to" to "tuo", "tp" to "tiao", "tr" to "tie", "ts" to "tou", "tt" to "tuan",
            "tu" to "tu", "tv" to "tui", "ty" to "tang", "tz" to "tun", "ue" to "e",
            "uf" to "eng", "ui" to "ei", "va" to "zha", "vd" to "zhao", "ve" to "zhe",
            "vf" to "zheng", "vg" to "zhuai", "vh" to "zhong", "vi" to "zhi", "vj" to "zhan",
            "vk" to "zhen", "vl" to "zhai", "vm" to "zhei", "vo" to "zhuo", "vs" to "zhou",
            "vt" to "zhuan", "vu" to "zhu", "vv" to "zhui", "vw" to "zhua", "vx" to "zhuang",
            "vy" to "zhang", "vz" to "zhun", "wa" to "wa", "wf" to "weng", "wj" to "wan",
            "wk" to "wen", "wl" to "wai", "wm" to "wei", "wo" to "wo", "wu" to "wu",
            "wy" to "wang", "xc" to "xin", "xg" to "xing", "xh" to "xiong", "xi" to "xi",
            "xk" to "xia", "xl" to "xue", "xn" to "xian", "xp" to "xiao", "xq" to "xiu",
            "xr" to "xie", "xt" to "xuan", "xu" to "xu", "xx" to "xiang", "xz" to "xun",
            "ya" to "ya", "yc" to "yin", "yd" to "yao", "ye" to "ye", "yg" to "ying",
            "yh" to "yong", "yi" to "yi", "yj" to "yan", "yl" to "yue", "yo" to "yo",
            "ys" to "you", "yt" to "yuan", "yu" to "yu", "yy" to "yang", "yz" to "yun",
            "za" to "za", "zd" to "zao", "ze" to "ze", "zf" to "zeng", "zh" to "zong",
            "zi" to "zi", "zj" to "zan", "zk" to "zen", "zl" to "zai", "zm" to "zei",
            "zo" to "zuo", "zs" to "zou", "zt" to "zuan", "zu" to "zu", "zv" to "zui",
            "zy" to "zang", "zz" to "zun",
        ),
    ) },
)
