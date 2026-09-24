package com.jinn.inputmethod

/**
 * 拼音键盘的静态布局数据：QWERTY 行定义、数字层映射、符号分组（含日文假名表）。
 *
 * 单独成文件：[PinyinKeyboardView] 原本把这 400 余行数据内嵌在类里，
 * 使「数据」与「视图逻辑」混杂、类体量虚高且不便查阅。这些数据无任何逻辑依赖，
 * 独立出来后视图类只保留行为代码。
 */

// ── 字母行定义（标准 QWERTY） ──────────────────────────
internal val KEYBOARD_ROWS = arrayOf(
    "qwertyuiop",
    "asdfghjkl",
    "zxcvbnm",
)

/** 数字层定义：键位 → 数字/符号 */
internal val DIGIT_MAP = mapOf(
    'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
    'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
    'a' to "-", 's' to "/", 'd' to ":", 'f' to ";", 'g' to "(",
    'h' to ")", 'j' to "¥", 'k' to "@", 'l' to "&",
    'z' to ".", 'x' to ",", 'c' to "?", 'v' to "!", 'b' to "'",
    'n' to "\"", 'm' to "%",
)

/** 符号分组：label 为候选栏标签；pages 为该组内的符号页（键盘滑动在此组内翻页），可自由添加页数不限 */
internal class SymbolGroup(val label: String, val pages: List<Map<Char, String>>)

/**
 * 一页符号里「最宽」的那条显示标签。
 *
 * 宽度估算与字体排版对齐：**ASCII 记 1（约 0.5em）、其余字符记 2（约 1em）**。
 * 只在「谁更宽」的相对比较里用，估偏大是安全方向（整页字号略小），估偏小会让真宽标签
 * 超出键面被裁掉：曾经按「长度 + 全角标点额外记 1」估算，把 `\`（2 个 ASCII≈1em）与
 * `……`（2 个全角≈2em）判成同宽，基准落到窄的一条 ⇒ 全角/标点页的 `……`、`——` 被切两端。
 *
 * 符号层用它统一页内字号（`PinyinKey.uniformMeasureText`）：同一页不会出现「2 字大、4 字小」。
 * 纯函数，可直接 JVM 单测（见 `SymbolLayoutTest`，含逐页「基准不得比任何标签窄」的属性断言）。
 */
internal fun widestSymbolLabel(labels: Collection<String>): String =
    labels.maxByOrNull { s -> s.count { it.code < 0x80 } + 2 * s.count { it.code >= 0x80 } }.orEmpty()

internal val SYMBOL_GROUPS: List<SymbolGroup> = listOf(
    // 全角：只放全角符号（中文全角标点 + 全角 ASCII 变体 + 无全/半角之分的通用符号），
    // 不得混入 ASCII、数字或字母（由 SymbolLayoutTest 守卫）；68 项按序铺满三页（26 + 26 + 16）
    SymbolGroup("全角", listOf(
        mapOf(
            'q' to "，", 'w' to "。", 'e' to "、", 'r' to "；", 't' to "：",
            'y' to "？", 'u' to "！", 'i' to "“", 'o' to "”", 'p' to "‘",
            'a' to "’", 's' to "（", 'd' to "）", 'f' to "《", 'g' to "》",
            'h' to "【", 'j' to "】", 'k' to "……", 'l' to "——",
            'z' to "·", 'x' to "～", 'c' to "＋", 'v' to "－", 'b' to "＝",
            'n' to "＜", 'm' to "＞",
        ),
        mapOf(
            'q' to "％", 'w' to "＆", 'e' to "＊", 'r' to "＠", 't' to "＃",
            'y' to "￥", 'u' to "．", 'i' to "／", 'o' to "＼", 'p' to "｜",
            'a' to "｛", 's' to "｝", 'd' to "［", 'f' to "］", 'g' to "＂",
            'h' to "＇", 'j' to "｀", 'k' to "＿", 'l' to "＾",
            'z' to "￡", 'x' to "€", 'c' to "¢", 'v' to "°", 'b' to "±",
            'n' to "×", 'm' to "÷",
        ),
        mapOf(
            'q' to "℃", 'w' to "‰", 'e' to "§", 'r' to "¶", 't' to "©",
            'y' to "®", 'u' to "〈", 'i' to "〉", 'o' to "『", 'p' to "』",
            'a' to "「", 's' to "」", 'd' to "〖", 'f' to "〗", 'g' to "〔",
            'h' to "〕",
        ),
    )),
    // 半角：只放半角符号（ASCII），且不得含数字（数字走数字层）。
    // 32 个可打印 ASCII 符号中 31 个有全角对应，按「其全角对应在全角组中的出现顺序」排列
    // （半角顺序尽量与全角对应：全角第 1 个是逗号，半角第 1 个也是逗号）；
    // 没有全角对应的 $ 放末尾。26 键铺满第 1 页，余 6 个进末页。
    SymbolGroup("半角", listOf(
        mapOf(
            'q' to ",", 'w' to ".", 'e' to ";", 'r' to ":", 't' to "?", 'y' to "!",
            'u' to "\"", 'i' to "'", 'o' to "(", 'p' to ")",
            'a' to "<", 's' to ">", 'd' to "[", 'f' to "]", 'g' to "~", 'h' to "+",
            'j' to "-", 'k' to "=", 'l' to "%",
            'z' to "&", 'x' to "*", 'c' to "@", 'v' to "#", 'b' to "/", 'n' to "\\",
            'm' to "|",
        ),
        mapOf(
            'q' to "{", 'w' to "}", 'e' to "`", 'r' to "_", 't' to "^", 'y' to "$",
        ),
    )),
    // 变量：输出「随当前时间变化」的内容（原「编程」组的关键字已整体移除：实测用不上）。
    // 取值写成 DynamicSymbols.token("短名")：键面显示短名，上屏时按点击那一刻的时间展开。
    // 新增一项只需在 DynamicSymbols.expand 里加同名分支，护栏见 DynamicSymbolsTest。
    SymbolGroup("变量", listOf(
        mapOf(
            'q' to DynamicSymbols.token("星期"), 'w' to DynamicSymbols.token("农历"),
            'e' to DynamicSymbols.token("季度"), 'r' to DynamicSymbols.token("财年"),
            't' to DynamicSymbols.token("生肖"), 'y' to DynamicSymbols.token("天数"),
            'u' to DynamicSymbols.token("周数"), 'i' to DynamicSymbols.token("分辨率"),
            'o' to DynamicSymbols.token("时间戳"), 'p' to DynamicSymbols.token("毫秒戳"),
            'a' to DynamicSymbols.token("年日中"), 's' to DynamicSymbols.token("年日数"),
            'd' to DynamicSymbols.token("年日符"), 'f' to DynamicSymbols.token("时秒中"),
            'g' to DynamicSymbols.token("时秒数"), 'h' to DynamicSymbols.token("时秒符"),
            'j' to DynamicSymbols.token("长时中"), 'k' to DynamicSymbols.token("长时数"),
            'l' to DynamicSymbols.token("长时符"),
        ),
    )),
    // 标点
    SymbolGroup("标点", listOf(
        mapOf(
            'q' to "‘", 'w' to "’", 'e' to "「", 'r' to "」", 't' to "『",
            'y' to "』", 'u' to "〔", 'i' to "〕", 'o' to "〈", 'p' to "〉",
            'a' to "…", 's' to "……", 'd' to "—", 'f' to "——", 'g' to "·",
            'h' to "、", 'j' to "，", 'k' to "､", 'l' to "｡",
            'z' to "！", 'x' to "？", 'c' to "；", 'v' to "：", 'b' to "。",
            'n' to "，", 'm' to "……",
        ),
        mapOf(
            'q' to "※", 'w' to "々", 'e' to "〆", 'r' to "〇", 't' to "〈",
            'y' to "〉", 'u' to "《", 'i' to "》", 'o' to "「", 'p' to "」",
            'a' to "『", 's' to "』", 'd' to "〔", 'f' to "〕", 'g' to "〈",
            'h' to "＞", 'j' to "＜", 'k' to "＞", 'l' to "≪",
            'z' to "≫", 'x' to "〈", 'c' to "〉", 'v' to "「", 'b' to "」",
            'n' to "‖", 'm' to "……",
        ),
        // 排版/装饰符号
        mapOf(
            'q' to "＊", 'w' to "§", 'e' to "¶", 'r' to "†", 't' to "‡",
            'y' to "«", 'u' to "»", 'i' to "‹", 'o' to "›", 'p' to "≈",
            'a' to "「", 's' to "」", 'd' to "『", 'f' to "』", 'g' to "【",
            'h' to "】", 'j' to "〈", 'k' to "〉", 'l' to "⌒",
            'z' to "々", 'x' to "〆", 'c' to "△", 'v' to "◇", 'b' to "○",
            'n' to "□", 'm' to "☆",
        ),
    )),
    // 序号
    // 特殊：符号 / 天气 / emoji / 箭头 / 货币 / 数理几何，共 6 页
    SymbolGroup("特殊", listOf(
        mapOf(
            'q' to "♥", 'w' to "♦", 'e' to "♣", 'r' to "♠", 't' to "★",
            'y' to "☆", 'u' to "♪", 'i' to "♫", 'o' to "☺", 'p' to "☹",
            'a' to "♡", 's' to "♢", 'd' to "♧", 'f' to "♤", 'g' to "☞",
            'h' to "☜", 'j' to "❀", 'k' to "☆", 'l' to "⚡",
            'z' to "✓", 'x' to "✗", 'c' to "☑", 'v' to "☐", 'b' to "①②",
            'n' to "③", 'm' to "④",
        ),
        mapOf(
            'q' to "☀", 'w' to "☁", 'e' to "☂", 'r' to "❄", 't' to "☃",
            'y' to "☎", 'u' to "✉", 'i' to "✈", 'o' to "⚓", 'p' to "⚑",
            'a' to "❤", 's' to "☻", 'd' to "☼", 'f' to "☽", 'g' to "♨",
            // 行星符号：本页其余条目都是天气/天文/符号类，早前这里误录成汉字「由」「白」
            // （见 SymbolLayoutTest 的回归用例）；k/l 补上同行序列缺的木星与天王星符号。
            'h' to "☿", 'j' to "♄", 'k' to "♃", 'l' to "♅",
            'z' to "☘", 'x' to "♁", 'c' to "☛", 'v' to "☚", 'b' to "➜",
            'n' to "⏏", 'm' to "※",
        ),
        // 常用 emoji
        mapOf(
            'q' to "😀", 'w' to "😁", 'e' to "😂", 'r' to "🤣", 't' to "😊",
            'y' to "😉", 'u' to "😍", 'i' to "😘", 'o' to "😎", 'p' to "🤔",
            'a' to "😏", 's' to "😒", 'd' to "😢", 'f' to "😭", 'g' to "😅",
            'h' to "😳", 'j' to "🤗", 'k' to "💪", 'l' to "👌",
            'z' to "👍", 'x' to "👎", 'c' to "🙏", 'v' to "💯", 'b' to "❤️",
            'n' to "🔥", 'm' to "🎉",
        ),
        // 第 4 页：箭头
        mapOf(
            'q' to "→", 'w' to "←", 'e' to "↑", 'r' to "↓", 't' to "⇒",
            'y' to "⇐", 'u' to "⇑", 'i' to "⇓", 'o' to "↔", 'p' to "↕",
            'a' to "➤", 's' to "➔", 'd' to "↗", 'f' to "↘", 'g' to "↙",
            'h' to "↖", 'j' to "⤴", 'k' to "⤵", 'l' to "↩",
            'z' to "↪", 'x' to "⇄", 'c' to "⇅", 'v' to "⇆", 'b' to "⇧",
            'n' to "⇩", 'm' to "⏎",
        ),
        // 第 5 页：货币与金融
        mapOf(
            'q' to "¥", 'w' to "$", 'e' to "€", 'r' to "£", 't' to "₩",
            'y' to "₽", 'u' to "¢", 'i' to "₪", 'o' to "₹", 'p' to "₫",
            'a' to "₴", 's' to "₦", 'd' to "₱", 'f' to "﷼", 'g' to "₨",
            'h' to "₡", 'j' to "₭", 'k' to "₮", 'l' to "₲",
            'z' to "₵", 'x' to "₸", 'c' to "₺", 'v' to "₼", 'b' to "₾",
            'n' to "¤", 'm' to "₿",
        ),
        // 第 6 页：数理与几何
        mapOf(
            'q' to "≠", 'w' to "≈", 'e' to "≤", 'r' to "≥", 't' to "±",
            'y' to "×", 'u' to "÷", 'i' to "∞", 'o' to "∵", 'p' to "∴",
            'a' to "∠", 's' to "⊥", 'd' to "∥", 'f' to "△", 'g' to "▲",
            'h' to "▽", 'j' to "▼", 'k' to "◇", 'l' to "◆",
            'z' to "□", 'x' to "■", 'c' to "○", 'v' to "●", 'b' to "◎",
            'n' to "⊙", 'm' to "⌒",
        ),
    )),
    SymbolGroup("序号", listOf(
        mapOf(
            'q' to "①", 'w' to "②", 'e' to "③", 'r' to "④", 't' to "⑤",
            'y' to "⑥", 'u' to "⑦", 'i' to "⑧", 'o' to "⑨", 'p' to "⑩",
            'a' to "❶", 's' to "❷", 'd' to "❸", 'f' to "❹", 'g' to "❺",
            'h' to "❻", 'j' to "❼", 'k' to "❽", 'l' to "❾",
            'z' to "Ⅰ", 'x' to "Ⅱ", 'c' to "Ⅲ", 'v' to "Ⅳ", 'b' to "Ⅴ",
            'n' to "Ⅵ", 'm' to "Ⅶ",
        ),
        mapOf(
            'q' to "⑪", 'w' to "⑫", 'e' to "⑬", 'r' to "⑭", 't' to "⑮",
            'y' to "⑯", 'u' to "⑰", 'i' to "⑱", 'o' to "⑲", 'p' to "⑳",
            'a' to "㈠", 's' to "㈡", 'd' to "㈢", 'f' to "㈣", 'g' to "㈤",
            'h' to "⒈", 'j' to "⒉", 'k' to "⒊", 'l' to "⒋",
            'z' to "Ⅷ", 'x' to "Ⅸ", 'c' to "Ⅹ", 'v' to "Ⅺ", 'b' to "Ⅻ",
            'n' to "ⅰ", 'm' to "ⅱ",
        ),
        // 圈中文 + 字母序号
        mapOf(
            'q' to "㊀", 'w' to "㊁", 'e' to "㊂", 'r' to "㊃", 't' to "㊄",
            'y' to "㊅", 'u' to "㊆", 'i' to "㊇", 'o' to "㊈", 'p' to "㊉",
            'a' to "Ⓐ", 's' to "Ⓑ", 'd' to "Ⓒ", 'f' to "Ⓓ", 'g' to "Ⓔ",
            'h' to "Ⓕ", 'j' to "Ⓖ", 'k' to "Ⓗ", 'l' to "Ⓘ",
            'z' to "Ⓙ", 'x' to "Ⓚ", 'c' to "Ⓛ", 'v' to "Ⓜ", 'b' to "Ⓝ",
            'n' to "Ⓞ", 'm' to "Ⓟ",
        ),
        mapOf(
            'q' to "㈥", 'w' to "㈦", 'e' to "㈧", 'r' to "㈨", 't' to "㈩",
            'y' to "⒌", 'u' to "⒍", 'i' to "⒎", 'o' to "⒏", 'p' to "⒐",
            'a' to "⒑", 's' to "⒒", 'd' to "⒓", 'f' to "⒔", 'g' to "⒕",
            'h' to "⒖", 'j' to "⒗", 'k' to "⒘", 'l' to "⒙",
            'z' to "⒚", 'x' to "⒛", 'c' to "Ⓠ", 'v' to "Ⓡ", 'b' to "Ⓢ",
            'n' to "Ⓣ", 'm' to "Ⓤ",
        ),
        // 第 5 页：圈字母收尾 + 带括号数字
        mapOf(
            'q' to "Ⓥ", 'w' to "Ⓦ", 'e' to "Ⓧ", 'r' to "Ⓨ", 't' to "Ⓩ",
            'y' to "⑴", 'u' to "⑵", 'i' to "⑶", 'o' to "⑷", 'p' to "⑸",
            'a' to "⑹", 's' to "⑺", 'd' to "⑻", 'f' to "⑼", 'g' to "⑽",
            'h' to "⑾", 'j' to "⑿", 'k' to "⒀", 'l' to "⒁",
            'z' to "⒂", 'x' to "⒃", 'c' to "⒄", 'v' to "⒅", 'b' to "⒆",
            'n' to "⒇", 'm' to "0.",
        ),
        // 第 6 页：带括号小写字母 + 大数圈号 + 汉字数字
        mapOf(
            'q' to "⒜", 'w' to "⒝", 'e' to "⒞", 'r' to "⒟", 't' to "⒠",
            'y' to "⒡", 'u' to "⒢", 'i' to "⒣", 'o' to "⒤", 'p' to "⒥",
            'a' to "⒦", 's' to "⒧", 'd' to "⒨", 'f' to "⒩", 'g' to "⒪",
            'h' to "⒫", 'j' to "⒬", 'k' to "⒭", 'l' to "⒮",
            'z' to "⒯", 'x' to "⒰", 'c' to "⒱", 'v' to "⒲", 'b' to "⒳",
            'n' to "⒴", 'm' to "⒵",
        ),
        // 第 7 页：更大的圈号与中文数字
        mapOf(
            'q' to "㉑", 'w' to "㉒", 'e' to "㉓", 'r' to "㉔", 't' to "㉕",
            'y' to "㉖", 'u' to "㉗", 'i' to "㉘", 'o' to "㉙", 'p' to "㉚",
            'a' to "㉛", 's' to "㉜", 'd' to "㉝", 'f' to "㉞", 'g' to "㉟",
            'h' to "〇", 'j' to "壹", 'k' to "贰", 'l' to "叁",
            'z' to "肆", 'x' to "伍", 'c' to "陆", 'v' to "柒", 'b' to "捌",
            'n' to "玖", 'm' to "拾",
        ),
    )),
    // 数学
    SymbolGroup("数学", listOf(
        mapOf(
            'q' to "＋", 'w' to "－", 'e' to "×", 'r' to "÷", 't' to "＝",
            'y' to "≠", 'u' to "≈", 'i' to "±", 'o' to "＜", 'p' to "＞",
            'a' to "≤", 's' to "≥", 'd' to "√", 'f' to "∞", 'g' to "∝",
            'h' to "∑", 'j' to "∏", 'k' to "∫", 'l' to "％",
            'z' to "∠", 'x' to "π", 'c' to "⊥", 'v' to "‖", 'b' to "∈",
            'n' to "∉", 'm' to "≈",
        ),
        mapOf(
            'q' to "＋", 'w' to "−", 'e' to "×", 'r' to "÷", 't' to "=",
            'y' to "≡", 'u' to "≠", 'i' to "≒", 'o' to "＜", 'p' to "＞",
            'a' to "≤", 's' to "≥", 'd' to "√", 'f' to "∛", 'g' to "∜",
            'h' to "∂", 'j' to "∇", 'k' to "∫∫∫", 'l' to "∭",
            'z' to "∞", 'x' to "㏕", 'c' to "⋂", 'v' to "⋃", 'b' to "∣",
            'n' to "∤", 'm' to "≈",
        ),
        // 希腊字母
        mapOf(
            'q' to "α", 'w' to "β", 'e' to "γ", 'r' to "δ", 't' to "ε",
            'y' to "ζ", 'u' to "η", 'i' to "θ", 'o' to "λ", 'p' to "μ",
            'a' to "ξ", 's' to "π", 'd' to "ρ", 'f' to "σ", 'g' to "τ",
            'h' to "φ", 'j' to "χ", 'k' to "ψ", 'l' to "ω",
            'z' to "Α", 'x' to "Β", 'c' to "Γ", 'v' to "Δ", 'b' to "Θ",
            'n' to "Λ", 'm' to "Ω",
        ),
        mapOf(
            'q' to "ι", 'w' to "κ", 'e' to "ν", 'r' to "ο", 't' to "υ",
            'y' to "Ε", 'u' to "Ζ", 'i' to "Η", 'o' to "Ι", 'p' to "Κ",
            'a' to "Μ", 's' to "Ν", 'd' to "Ξ", 'f' to "Ο", 'g' to "Π",
            'h' to "Ρ", 'j' to "Σ", 'k' to "Τ", 'l' to "Υ",
            'z' to "Φ", 'x' to "Χ", 'c' to "Ψ", 'v' to "Ω", 'b' to "Ϝ",
            'n' to "Ϟ", 'm' to "Ϡ",
        ),
    )),
    // 单位
    SymbolGroup("单位", listOf(
        mapOf(
            'q' to "￥", 'w' to "＄", 'e' to "€", 'r' to "£", 't' to "￡",
            'y' to "℃", 'u' to "℉", 'i' to "°", 'o' to "％", 'p' to "‰",
            'a' to "㎝", 's' to "㎜", 'd' to "㎞", 'f' to "㎡", 'g' to "㎥",
            'h' to "μ", 'j' to "Ω", 'k' to "Ｖ", 'l' to "Ａ",
            'z' to "²", 'x' to "³", 'c' to "＃", 'v' to "＆", 'b' to "＠",
            'n' to "¥", 'm' to "＄",
        ),
        mapOf(
            'q' to "㎡", 'w' to "㎞", 'e' to "ｇ", 'r' to "ｍ", 't' to "Ｌ",
            'y' to "℃", 'u' to "℉", 'i' to "°", 'o' to "％", 'p' to "‰",
            'a' to "㎎", 's' to "㎏", 'd' to "㏄", 'f' to "㏗", 'g' to "㎒",
            'h' to "㎓", 'j' to "㎑", 'k' to "ｋｍ", 'l' to "ｃｍ",
            'z' to "②", 'x' to "③", 'c' to "④", 'v' to "⑤", 'b' to "⑥",
            'n' to "⑦", 'm' to "⑧",
        ),
        mapOf(
            'q' to "㏑", 'w' to "㏒", 'e' to "㏈", 'r' to "㏉", 't' to "㏊",
            'y' to "㏋", 'u' to "㏌", 'i' to "㏍", 'o' to "㏎", 'p' to "㏏",
            'a' to "㏐", 's' to "㏓", 'd' to "㏔", 'f' to "㏕", 'g' to "㏖",
            'h' to "㏘", 'j' to "㏙", 'k' to "㏚", 'l' to "㏛",
            'z' to "㏜", 'x' to "㏝", 'c' to "㎖", 'v' to "㎗", 'b' to "㎘",
            'n' to "㎠", 'm' to "㎰",
        ),
    )),
    // 平假名
    // 日本：平假名 + 片假名合并（原为两个分组，内容二合一，共 10 页）
    SymbolGroup("日本", listOf(
        // ── 平假名 ──
        mapOf(
            'q' to "あ", 'w' to "い", 'e' to "う", 'r' to "え", 't' to "お",
            'y' to "か", 'u' to "き", 'i' to "く", 'o' to "け", 'p' to "こ",
            'a' to "さ", 's' to "し", 'd' to "す", 'f' to "せ", 'g' to "そ",
            'h' to "た", 'j' to "ち", 'k' to "つ", 'l' to "て",
            'z' to "な", 'x' to "に", 'c' to "ぬ", 'v' to "ね", 'b' to "の",
            'n' to "は", 'm' to "ひ",
        ),
        mapOf(
            'q' to "ふ", 'w' to "へ", 'e' to "ほ", 'r' to "ま", 't' to "み",
            'y' to "む", 'u' to "め", 'i' to "も", 'o' to "や", 'p' to "ゆ",
            'a' to "よ", 's' to "ら", 'd' to "り", 'f' to "る", 'g' to "れ",
            'h' to "ろ", 'j' to "わ", 'k' to "を", 'l' to "ん",
            'z' to "が", 'x' to "ぎ", 'c' to "ぐ", 'v' to "げ", 'b' to "ご",
            'n' to "ぱ", 'm' to "ぴ",
        ),
        mapOf(
            'q' to "ざ", 'w' to "じ", 'e' to "ず", 'r' to "ぜ", 't' to "ぞ",
            'y' to "だ", 'u' to "ぢ", 'i' to "づ", 'o' to "で", 'p' to "ど",
            'a' to "ば", 's' to "び", 'd' to "ぶ", 'f' to "べ", 'g' to "ぼ",
            'h' to "ぱ", 'j' to "ぴ", 'k' to "ぷ", 'l' to "ぺ",
            'z' to "ぽ", 'x' to "ぁ", 'c' to "ぃ", 'v' to "ぅ", 'b' to "ぇ",
            'n' to "ぉ", 'm' to "ゃ",
        ),
        mapOf(
            'q' to "ゅ", 'w' to "ょ", 'e' to "っ", 'r' to "ゎ", 't' to "ゐ",
            'y' to "ゑ", 'u' to "ゝ", 'i' to "ゞ", 'o' to "ゕ", 'p' to "ゖ",
            'a' to "きゃ", 's' to "きゅ", 'd' to "きょ", 'f' to "しゃ", 'g' to "しゅ",
            'h' to "しょ", 'j' to "ちゃ", 'k' to "ちゅ", 'l' to "ちょ",
            'z' to "にゃ", 'x' to "にゅ", 'c' to "にょ", 'v' to "ひゃ", 'b' to "ひゅ",
            'n' to "ひょ", 'm' to "みゃ",
        ),
        // 拗音（续）+ 长音符与浊点（补齐末页空位）
        mapOf(
            'q' to "みゅ", 'w' to "みょ", 'e' to "りゃ", 'r' to "りゅ", 't' to "りょ",
            'y' to "ぎゃ", 'u' to "ぎゅ", 'i' to "ぎょ", 'o' to "じゃ", 'p' to "じゅ",
            'a' to "じょ", 's' to "びゃ", 'd' to "びゅ", 'f' to "びょ", 'g' to "ぴゃ",
            'h' to "ぴゅ", 'j' to "ぴょ", 'k' to "ー", 'l' to "゛",
            'z' to "゜", 'x' to "・", 'c' to "〜", 'v' to "ゔ", 'b' to "゠",
            'n' to "ゟ", 'm' to "ゝゞ",
        ),
        // ── 片假名 ──
        mapOf(
            'q' to "ア", 'w' to "イ", 'e' to "ウ", 'r' to "エ", 't' to "オ",
            'y' to "カ", 'u' to "キ", 'i' to "ク", 'o' to "ケ", 'p' to "コ",
            'a' to "サ", 's' to "シ", 'd' to "ス", 'f' to "セ", 'g' to "ソ",
            'h' to "タ", 'j' to "チ", 'k' to "ツ", 'l' to "テ",
            'z' to "ナ", 'x' to "ニ", 'c' to "ヌ", 'v' to "ネ", 'b' to "ノ",
            'n' to "ハ", 'm' to "ヒ",
        ),
        mapOf(
            'q' to "フ", 'w' to "ヘ", 'e' to "ホ", 'r' to "マ", 't' to "ミ",
            'y' to "ム", 'u' to "メ", 'i' to "モ", 'o' to "ヤ", 'p' to "ユ",
            'a' to "ヨ", 's' to "ラ", 'd' to "リ", 'f' to "ル", 'g' to "レ",
            'h' to "ロ", 'j' to "ワ", 'k' to "ヲ", 'l' to "ン",
            'z' to "ガ", 'x' to "ギ", 'c' to "グ", 'v' to "ゲ", 'b' to "ゴ",
            'n' to "パ", 'm' to "ピ",
        ),
        mapOf(
            'q' to "ザ", 'w' to "ジ", 'e' to "ズ", 'r' to "ゼ", 't' to "ゾ",
            'y' to "ダ", 'u' to "ヂ", 'i' to "ヅ", 'o' to "デ", 'p' to "ド",
            'a' to "バ", 's' to "ビ", 'd' to "ブ", 'f' to "ベ", 'g' to "ボ",
            'h' to "パ", 'j' to "ピ", 'k' to "プ", 'l' to "ペ",
            'z' to "ポ", 'x' to "ァ", 'c' to "ィ", 'v' to "ゥ", 'b' to "ェ",
            'n' to "ォ", 'm' to "ャ",
        ),
        mapOf(
            'q' to "ュ", 'w' to "ョ", 'e' to "ッ", 'r' to "ヮ", 't' to "ヰ",
            'y' to "ヱ", 'u' to "ヽ", 'i' to "ヾ", 'o' to "ヵ", 'p' to "ヶ",
            'a' to "キャ", 's' to "キュ", 'd' to "キョ", 'f' to "シャ", 'g' to "シュ",
            'h' to "ショ", 'j' to "チャ", 'k' to "チュ", 'l' to "チョ",
            'z' to "ニャ", 'x' to "ニュ", 'c' to "ニョ", 'v' to "ヒャ", 'b' to "ヒュ",
            'n' to "ヒョ", 'm' to "ミャ",
        ),
        // 拗音（续）+ 长音符与浊点（补齐末页空位）
        mapOf(
            'q' to "ミュ", 'w' to "ミョ", 'e' to "リャ", 'r' to "リュ", 't' to "リョ",
            'y' to "ギャ", 'u' to "ギュ", 'i' to "ギョ", 'o' to "ジャ", 'p' to "ジュ",
            'a' to "ジョ", 's' to "ビャ", 'd' to "ビュ", 'f' to "ビョ", 'g' to "ピャ",
            'h' to "ピュ", 'j' to "ピョ", 'k' to "ー", 'l' to "゛",
            'z' to "゜", 'x' to "・", 'c' to "〜", 'v' to "ヷ", 'b' to "ヸ",
            'n' to "ヹ", 'm' to "ヺ",
        ),
    )),
    // 拉丁
    SymbolGroup("拉丁", listOf(
        mapOf(
            'q' to "á", 'w' to "à", 'e' to "ä", 'r' to "â", 't' to "é",
            'y' to "è", 'u' to "ë", 'i' to "ê", 'o' to "í", 'p' to "ì",
            'a' to "ï", 's' to "î", 'd' to "ó", 'f' to "ò", 'g' to "ö",
            'h' to "ô", 'j' to "ú", 'k' to "ù", 'l' to "ü",
            'z' to "û", 'x' to "ç", 'c' to "ñ", 'v' to "ß", 'b' to "œ",
            'n' to "ÿ", 'm' to "æ",
        ),
        mapOf(
            'q' to "Á", 'w' to "À", 'e' to "Ä", 'r' to "Â", 't' to "É",
            'y' to "È", 'u' to "Ë", 'i' to "Ê", 'o' to "Í", 'p' to "Ì",
            'a' to "Ï", 's' to "Î", 'd' to "Ó", 'f' to "Ò", 'g' to "Ö",
            'h' to "Ô", 'j' to "Ú", 'k' to "Ù", 'l' to "Ü",
            'z' to "Œ", 'x' to "Ÿ", 'c' to "Û", 'v' to "Ñ", 'b' to "Æ",
            'n' to "©", 'm' to "®",
        ),
        mapOf(
            'q' to "ø", 'w' to "å", 'e' to "Ø", 'r' to "Å", 't' to "þ",
            'y' to "ð", 'u' to "Đ", 'i' to "đ", 'o' to "ħ", 'p' to "ŋ",
            'a' to "ŧ", 's' to "Ŧ", 'd' to "ẞ", 'f' to "Š", 'g' to "š",
            'h' to "Ž", 'j' to "ž", 'k' to "Ə", 'l' to "ə",
            'z' to "Ɛ", 'x' to "ɛ", 'c' to "Ɔ", 'v' to "ɔ", 'b' to "ɐ",
            'n' to "ɑ", 'm' to "ɒ",
        ),
    )),
    // 特殊
    // 注音：第 1 页声调（带调拼音字母 + 声调符号），其后为完整注音符号
    SymbolGroup("注音", listOf(
        mapOf(
            'q' to "ā", 'w' to "á", 'e' to "ǎ", 'r' to "à", 't' to "ō",
            'y' to "ó", 'u' to "ǒ", 'i' to "ò", 'o' to "ē", 'p' to "é",
            'a' to "ě", 's' to "è", 'd' to "ī", 'f' to "í", 'g' to "ǐ",
            'h' to "ì", 'j' to "ū", 'k' to "ú", 'l' to "ǔ",
            'z' to "ù", 'x' to "ǖ", 'c' to "ǘ", 'v' to "ǚ", 'b' to "ǜ",
            'n' to "ˉ", 'm' to "ˊ",
        ),
        // 第 2 页：剩余声调符号 + 注音声母/介音
        mapOf(
            'q' to "ˇ", 'w' to "ˋ", 'e' to "˙", 'r' to "ㄅ", 't' to "ㄆ",
            'y' to "ㄇ", 'u' to "ㄈ", 'i' to "ㄉ", 'o' to "ㄊ", 'p' to "ㄋ",
            'a' to "ㄌ", 's' to "ㄍ", 'd' to "ㄎ", 'f' to "ㄏ", 'g' to "ㄐ",
            'h' to "ㄑ", 'j' to "ㄒ", 'k' to "ㄓ", 'l' to "ㄔ",
            'z' to "ㄕ", 'x' to "ㄖ", 'c' to "ㄗ", 'v' to "ㄘ", 'b' to "ㄙ",
            'n' to "ㄧ", 'm' to "ㄨ",
        ),
        // 第 3 页：注音韵母（不满 26 键，剩余按键显示空文本）
        mapOf(
            'q' to "ㄩ", 'w' to "ㄚ", 'e' to "ㄛ", 'r' to "ㄜ", 't' to "ㄝ",
            'y' to "ㄞ", 'u' to "ㄟ", 'i' to "ㄠ", 'o' to "ㄡ", 'p' to "ㄢ",
            'a' to "ㄣ", 's' to "ㄤ", 'd' to "ㄥ", 'f' to "ㄦ", 'g' to "ㄭ",
        ),
    )),
)

/** 「收藏」组页内铺键用的键位序（与其它组一致的 QWERTY 行序，26 键） */
private const val FAVORITE_KEYS = "qwertyuiopasdfghjklzxcvbnm"

/**
 * 「收藏」组：用户自由 DIY（数据在 [Prefs.favoriteSymbols]，规则见 [FavoriteSymbols]）。
 * 页内符号按序铺 [FAVORITE_KEYS]，尾页可不满；删光后为单页全空键。
 */
internal fun favoriteGroup(pages: List<List<String>>): SymbolGroup =
    SymbolGroup(FavoriteSymbols.LABEL, pages.map { page ->
        buildMap {
            page.forEachIndexed { i, s -> if (i < FAVORITE_KEYS.length) put(FAVORITE_KEYS[i], s) }
        }
    }.ifEmpty { listOf(emptyMap()) })
