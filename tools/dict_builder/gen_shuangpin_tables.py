#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
从 rime-ice 的双拼 schema 生成 Kotlin 键位数据（app/.../ShuangpinSchemes.kt）。

为什么要有这个脚本
------------------
手抄 8 套方案（自然码/小鹤/搜狗/微软/紫光/ABC/加加/首道）的键位表必然出错且无法自证。
rime 的 `speller/algebra` 是权威定义：本脚本按 librime 的代数语义把它逐条施加到项目的
421 个合法音节上，算出每套方案的编码，再做三件事：

  1. 冲突裁决：同一码对应多个音节时（如 `lo` = lo/luo、`lt` = lue/lve），
     按词库实际写法决定首选，裁决过程写进运行报告。
  2. 键面提示派生：提示由**同一张表**在运行期反推（见生成的 ShuangpinTable），
     保证键面与引擎永不漂移（旧实现是两份手写数据，已出现 'o' 键提示错误）。
  3. 产出 Kotlin：`initials`（声母键）+ `codes`（码→音节，扁平表）。

用法
----
    先 python tools/dict_builder/build_dict_index.py   # 产出 out/rime_ice/pinyin_phrases.txt
    再 python tools/dict_builder/gen_shuangpin_tables.py
    再跑 tools/dict_builder/verify_shuangpin_migration.py 对拍（换表不许改行为）

前置条件：一码多音节的裁决按**词频**定胜负，词频取自 `out/rime_ice/pinyin_phrases.txt`
（`out/` 不入库）。该文件由 `build_dict_index.py` 从 `docs/rime-ice/cn_dicts/` 重算，秒级可重建；
没跑这一步会直接 `FileNotFoundError`，**不要**把它改成「空权重」跑下去 —— 全零时裁决退化成
字母序，实测三个码位会翻转（紫光 `hm` / `ng`、ABC `hm`）。

代数语义（对齐 librime，2026-09-17 逐条核对 C++ 实现）
--------------------------------------------------------
核对对象：`docs/librime-master/src/rime/algo/{calculus.h,calculus.cc,algebra.cc}`（librime 1.17.0）。
逐条结论（左=C++ 权威语义，右=本脚本）：

  xform    Transformation，**默认 addition=true + deletion=true ⇒ 替换**；
           内部是 boost::regex_replace（**全局**替换，`$1` 反向引用）        → 本脚本 re.sub 替换 ✓
  derive   Derivation : Transformation 且 deletion=false ⇒ **保留原拼写 + 追加派生** → 保留+条件追加 ✓
  erase    Erasion：addition=false，且用 boost::regex_**match**（整串匹配）
           命中才清空拼写 ⇒ **整串匹配才删除**（不是部分删除）              → re.fullmatch ✓
  xlit     Transliteration：逐字符映射，Parse 要求两侧**字符数相等**，
           否则整个 algebra 加载失败                                        → 等长校验（本次补）✓
  fuzz/abbrev/correction
           只是 Derivation 的子类，差异在 credibility/type，
           **字符串集合与 derive 完全相同**                                 → 忽略 tag ✓（对码表无影响）
  集合语义 Projection::Apply：**每个 op 作用于上一轮的整个拼写集合**，
           派生出的新拼写成为下一轮的输入（链式组合）；map 键天然去重        → cur→nxt→去重 ✓
  未匹配   原样保留（Transformation::Apply 返回 false 时 caller 直接沿用）   → re.sub 无匹配即原串 ✓
  正则语义 boost::regex（Perl 语法），其 \\w \\d \\b 默认按 **ASCII**           → 统一加 re.ASCII（本次补）✓
  解析     分隔符取「首个非小写字母字符」；多余参数（tag）忽略                 → 严格 op/…/… + 未知 op 响亮失败（本次补）✓

**本次修正的 4 处潜伏差异**（对当前 7 套 schema 判定为「零语义变更」——重生成后
`ShuangpinSchemes.kt` 逐字节不变，且迁移闸门仍为「真回归 0」）：
  1. xform 结果为空时 librime 会丢弃该拼写（`!s.str.empty()`），旧实现会把空串留在集合里；
  2. derive 同样不追加空结果；
  3. xlit 两侧不等长时 librime 直接拒绝，旧实现被 zip 静默截断；
  4. \w 等的 ASCII/Unicode 语义差异（当前无规则受影响，属预防性对齐）。

原说明如下（保留）：
代数语义（对齐 librime）
------------------------
  xform/P/R/   替换（就地变换）      derive/P/R/  派生（追加，原拼写保留）
  erase/P/     fullmatch 命中则删     xlit/F/T/    逐字符映射（等长）
"""

import io
import json
import lzma
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asset_io import read_asset_text, write_bytes_atomically  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RIME = os.path.join(ROOT, 'docs', 'rime-ice')
SYLL = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'pinyin_syllables.txt.xz')
# 词库文本已在 Stage 1 移出 assets（运行时改读二进制索引），这里改读流水线留档文本：
# 由 convert_rime_ice.py / build_dict_index.py 写到 out/rime_ice/pinyin_phrases.txt。
DICT = os.path.join(ROOT, 'tools', 'dict_builder', 'out', 'rime_ice', 'pinyin_phrases.txt')
OUT_KT = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'jinn', 'inputmethod',
                      'ShuangpinSchemes.kt')
OUT_DIR = os.path.join(ROOT, 'tools', 'dict_builder', 'out', 'shuangpin')

SCHEMES = [
    ('ziranma', 'double_pinyin', '自然码双拼'),
    ('flypy', 'double_pinyin_flypy', '小鹤双拼'),
    ('sogou', 'double_pinyin_sogou', '搜狗双拼'),
    ('mspy', 'double_pinyin_mspy', '微软双拼'),
    ('ziguang', 'double_pinyin_ziguang', '紫光双拼'),
    ('abc', 'double_pinyin_abc', '智能ABC双拼'),
    ('jiajia', 'double_pinyin_jiajia', '拼音加加双拼'),
    # 首道双拼上游**没有** rime 方案（2026 新方案，检索无果），schema 传 None 走下面的规格表分支
    ('shoudao', None, '首道双拼'),
]

# ── 表驱动方案：没有 rime schema 可依，直接按公开键位规格编码 ────────────────────
# 首道双拼规格（4 源一致：少数派原文/键位图、青简文档、WindInput、MSIME），
# 设计与出处见 `docs/设计-双拼/首道双拼接入-2026-10-31.md`。
# 与 rime 分支的唯一差别是数据来源：本表即为真源，不再施加 algebra。
TABLE_SCHEMES = {
    'shoudao': {
        'cn': '首道双拼',
        'note': '公开规格直表编码',
        # 声母 → 键；其余单字母声母自映射（b p m f d t n l g k h j q x r z c s y w）
        'initials': {'zh': 'v', 'ch': 'i', 'sh': 'e'},
        # 韵母 → 键（26 键全覆盖；üe 在 b、ue 在 l，jqxy 后的 ü 按项目写法仍作 u）
        'finals': {
            'a': 'a', 'o': 'o', 'e': 'e', 'ai': 'l', 'ei': 'm', 'ao': 'd', 'ou': 's',
            'an': 'j', 'en': 'k', 'ang': 'y', 'eng': 'f', 'ong': 'h',
            'i': 'i', 'ia': 'k', 'ie': 'r', 'iao': 'p', 'iu': 'q', 'ian': 'n', 'in': 'c',
            'iang': 'x', 'ing': 'g', 'iong': 'h',
            'u': 'u', 'ua': 'w', 'uo': 'o', 'uai': 'g', 'ui': 'v', 'ue': 'l', 'uan': 't',
            'un': 'z', 'uang': 'x',
            'v': 'v', 've': 'b',
        },
        # 零声母：**不可推导**（e/ei/eng 被 sh 占键，改 u 引导；ang 走 ay），必须显式列
        'zero': {
            'a': 'aa', 'o': 'oo', 'ai': 'ai', 'ao': 'ao', 'ou': 'ou', 'an': 'an',
            'en': 'en', 'er': 'er', 'ang': 'ay', 'e': 'ue', 'ei': 'ui', 'eng': 'uf',
        },
        # 叹词/无词库音节：表里编不出也不需要（其余由 SKIP_SYLLABLES 兜）
        'skip': {'hm', 'ng'},
    },
}


PROBE_INITIALS = ['b', 'p', 'm', 'f', 'd', 't', 'n', 'l', 'g', 'k', 'h', 'j', 'q', 'x',
                  'zh', 'ch', 'sh', 'r', 'z', 'c', 's', 'y', 'w']

# 一码多音节时的裁决（冲突在 rime 七套里形态一致，规则统一；首道只用到后三条）：
#   键 = 被淘汰写法，值 = 保留写法；值为 None 表示「按对手保留」。
#   依据：词库实际写法计数 + 「不改动已上线行为」原则。
COLLISION_PREFER = {
    # 「两键一音节」模型下一码只能映射一个音节，同码者必须让位。裁决原则：
    #   一是换表前已上线的行为不动（自然码老实现是 finalFor 的手写分支），
    #   二是词库 0 条的叹词让位给真实音节。
    'lo': 'luo',    # 让位给 luo（与旧实现一致：旧实现 'o' 在 l 后取 uo）→ 咯/啰 双拼打不出
                    # （全拼仍可输入；rime 因为保留多个拼写所以两个都能打，本项目不保留多拼写）
    'den': 'dia',   # 首道专用：k 键 = [en, ia] ⇒ 两者同码 dk。取嗲（常用字）：
                    # 扽只在二级字档、默认档被闸掉（实测 dk 零候选），全拼 den 仍可输入
    'lue': 'lai',   # 首道专用：l 键 = [ai, ue] ⇒ lue/lai 同码 ll，lüe 让位
    'nue': 'nai',   # 首道专用：同一原因 ⇒ nue/nai 同码 nl，nüe 让位（lve/nve 另有码 lb/nb）
    'ng': None,     # ng 是叹词（词库 0 条），按对手保留（neng / nang / niang）
    'hm': None,     # 同上（词库 0 条），按对手保留（hun / hui）
    'lve': 'lue',   # üe 让位给 ue 写法（与旧实现一致）。注意：词库里的 lve/nve 词条**仍能命中**，
    'nve': 'nue',   # 因为查询链路有 ue↔ve 变体回退（PinyinEngine.variants），故无功能损失
}
# 词库 0 条、且任何双拼都编不出来的音节（rime 同样编不出）：跳过
SKIP_SYLLABLES = {'hng', 'm', 'n', 'junding'}
# 自然码历史兼容零声母（libime 风格，rime 无此规则；旧实现支持，保留以免回归）
ZIRANMA_COMPAT = {
    'aj': 'an', 'al': 'ai', 'ak': 'ao', 'ez': 'ei', 'ef': 'en', 'or': 'er',
    'oj': 'an', 'ol': 'ai', 'ok': 'ao', 'oh': 'ang', 'oz': 'ei', 'of': 'en',
    'og': 'eng', 'ob': 'ou', 'oa': 'a', 'oe': 'e',
}

KT_HEADER = '''package com.jinn.inputmethod

/**
 * 双拼键位数据（由脚本生成，勿手改）。
 *
 * 生成器：`tools/dict_builder/gen_shuangpin_tables.py`
 * 权威来源：`docs/rime-ice/double_pinyin*.schema.yaml` 的 `speller/algebra`，
 * 脚本按 librime 的代数语义（xform 替换 / derive 追加 / erase / xlit）逐条施加到
 * `assets/pinyin_syllables.txt` 的 %d 个合法音节上，算出每套方案的码表。
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
        return if (hasRedInitial) list.joinToString(" ") else list.joinToString("\\n")
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
'''


def parse_algebra(path):
    text = io.open(path, encoding='utf-8').read()
    lines = text.split('\n')
    in_algebra, rules = False, []
    for line in lines:
        if re.match(r'^  algebra:\s*$', line):
            in_algebra = True
            continue
        if in_algebra:
            # 段结束：下一行缩进回到 ≤2（新的 YAML 键）或顶格。
            # 旧写法只判 ^\S，一旦上游把 algebra 挪到文件中部，就会把后面
            # preedit_format/comment_format 的规则一起吃进来（实测当前 algebra 都在文件末尾，
            # 所以尚未暴露——这里按 YAML 结构收紧，避免依赖文件顺序）。
            if line.strip() and len(line) - len(line.lstrip()) <= 2:
                break
            m = re.match(r'^\s*-\s*(xform|derive|erase|xlit)/(.*)/\s*(#.*)?$', line)
            if m:
                rules.append((m.group(1), m.group(2).split('/')))
                continue
            # 不认识的 op（librime 还有 fuzz/abbrev/reorder）或格式异常：**必须响亮失败**。
            # 静默跳过会让键位表悄悄少一条规则，而所有测试仍然全绿（最危险的就是这种静默错误）。
            if line.strip().startswith('-') or re.match(r'^\s*-\s*[a-z]', line):
                raise SystemExit(
                    f'algebra 段出现本脚本不认识的规则，拒绝继续（否则会静默生成错误键位）: {line!r}'
                )
    return rules


def parse_alphabet(path):
    m = re.search(r'^\s*alphabet:\s*(\S+)\s*$', io.open(path, encoding='utf-8').read(), re.M)
    return set(m.group(1)) if m else set()


def apply_rules(rules, spelling):
    cur = [spelling]
    for kind, parts in rules:
        nxt = []
        # 正则一律用 re.ASCII：librime 用的是 boost::regex，其 \w \d \b \s 默认按 ASCII，
        # 而 Python 3 的 re 默认是 Unicode 宽字符。当前 7 套 schema 里没有「先产生特殊符号、
        # 再用 \w 匹配」的规则（已核查），所以此改动不改变今天的任何键位；但一旦上游调整顺序，
        # 不做这个对齐就会静默算错。
        FLAGS = re.ASCII
        if kind in ('xform', 'derive'):
            pat = parts[0]
            rep = (parts[1] if len(parts) > 1 else '').replace('$', '\\')
            for s in cur:
                if kind == 'derive':
                    nxt.append(s)                       # derive：deletion=false → 原拼写保留
                    if re.search(pat, s, FLAGS):
                        res = re.sub(pat, rep, s, flags=FLAGS)
                        if res:                         # addition 且非空才追加（librime 有 !s.str.empty()）
                            nxt.append(res)
                else:
                    # xform：deletion=true（原拼写丢弃）+ addition=true；结果为空 ⇒ 该拼写消失
                    res = re.sub(pat, rep, s, flags=FLAGS)
                    if res:
                        nxt.append(res)
        elif kind == 'erase':
            # librime 用 boost::regex_match（**整串**匹配）才清空拼写；不是部分删除
            nxt = [s for s in cur if not re.fullmatch(parts[0], s, FLAGS)]
        elif kind == 'xlit':
            left, right = parts[0], parts[1] if len(parts) > 1 else ''
            if len(left) != len(right):
                # librime 的 Transliteration::Parse 要求两侧字符数相等，否则整个 algebra 加载失败；
                # Python 的 zip 会静默截断到较短一方 → 变成「悄悄少映射几个字符」，必须显式拒绝
                raise SystemExit(f'xlit 两侧字符数不等（{len(left)} vs {len(right)}），librime 会拒绝该规则')
            table = {ord(a): b for a, b in zip(left, right)}
            nxt = [s.translate(table) for s in cur]
        seen = set()
        cur = [x for x in nxt if not (x in seen or seen.add(x))]
    return cur


def dict_key_counts():
    """键 → 词条数（冲突裁决时的权重参考）；源为流水线留档文本。"""
    counts = {}
    with io.open(DICT, encoding='utf-8') as f:
        for line in f:
            k = line.split('\t')[0]
            counts[k] = counts.get(k, 0) + 1
    return counts


def pick_canonical(sylls, counts):
    if len(sylls) == 1:
        return sylls[0]
    # 裁决表可能以任一写法为键（如 {'lve': 'lue'}），故逐个候选查
    for cand in sylls:
        pref = COLLISION_PREFER.get(cand)
        if pref and pref in sylls:
            return pref
    if counts is None:
        # 表驱动方案没有词频可依（也刻意不读 —— 判据必须是显式规则，不能随语料漂移）
        raise SystemExit(f'表驱动方案出现未裁决的同码 {sylls}：请在 COLLISION_PREFER 里显式指定')

    def weight(s):
        return sum(v for k, v in counts.items() if k.startswith(s))

    return sorted(sylls, key=lambda s: (-weight(s), s))[0]


def build_table_scheme(spec, syllables):
    """表驱动方案（上游无 rime schema）：按公开规格表编码。

    返回 (initials, per_syllable, code_map, collisions, bad)，与 rime 分支同形，
    下游的渲染 / 报告 / JSON 落盘共用一套代码。
    """
    skip = SKIP_SYLLABLES | spec.get('skip', set())
    ini_key = {c: c for c in 'bpmfdtnlgkhjqxrzcsyw'}
    ini_key.update(spec['initials'])
    initials = {key: ini for ini, key in ini_key.items()}
    pairs, bad = [], []
    for syl in syllables:
        if syl in skip:
            continue
        if syl in spec['zero']:                 # 零声母不可推导，表里是显式键对
            pairs.append((spec['zero'][syl], syl))
            continue
        ini = next((c for c in ('zh', 'ch', 'sh') if syl.startswith(c)), None)
        if ini is None and syl[:1] in ini_key:
            ini = syl[0]
        if ini is None:
            bad.append(syl)
            continue
        fin = spec['finals'].get(syl[len(ini):])
        if fin is None:
            bad.append(syl)
            continue
        pairs.append((ini_key[ini] + fin, syl))

    rev = {}
    for code, syl in pairs:
        rev.setdefault(code, []).append(syl)
    code_map, collisions = {}, []
    for code, sylls in sorted(rev.items()):
        uniq = sorted(set(sylls))
        chosen = pick_canonical(uniq, None)      # 无词频：未显式裁决的同码会直接报错
        code_map[code] = chosen
        if len(uniq) > 1:
            collisions.append((code, uniq, chosen))
    per_syllable = {syl: [code] for code, syl in pairs}
    return initials, per_syllable, code_map, collisions, bad


def render_block(key, cn, source, syllable_count, initials, code_map):
    """生成一个方案的 Kotlin 数据块（两种数据来源共用同一份排版）。"""
    lines = [f'    /** {cn}（{source}）：{syllable_count} 个音节 / {len(code_map)} 个码 */',
             f'    "{key.upper()}" to lazy {{ ShuangpinTable(',
             '        initials = mapOf(']
    ini_items = [f"'{k}' to \"{v}\"" for k, v in sorted(initials.items())]
    for i in range(0, len(ini_items), 6):
        lines.append('            ' + ', '.join(ini_items[i:i + 6]) + ',')
    lines.append('        ),')
    lines.append('        codes = mapOf(')
    code_items = [f'"{c}" to "{s}"' for c, s in sorted(code_map.items())]
    for i in range(0, len(code_items), 5):
        lines.append('            ' + ', '.join(code_items[i:i + 5]) + ',')
    lines.append('        ),')
    lines.append('    ) },')
    return '\n'.join(lines)


def splice_block(src, key, block, syllable_count):
    """只把 [key] 的块写进现有 Kotlin，其余块逐字保留。

    存在的理由：词频留档 `out/rime_ice/pinyin_phrases.txt` 不入库，换机后要先重建 46MB 语料
    才能全量重生成；而新增一个**表驱动**方案不需要任何词频（同码全部显式裁决）。头部仍按
    KT_HEADER 重写，保证「生成器模板与源码头部逐行一致」这条守卫仍然成立。
    """
    marker = 'internal val SHUANGPIN_TABLES: Map<String, Lazy<ShuangpinTable>> = mapOf('
    idx = src.index(marker)
    body = src[idx + len(marker):].lstrip('\n')
    head = (KT_HEADER % syllable_count).rstrip('\n')   # 模板已含到 mapOf( 为止（与守卫对拍口径一致）
    tag = f'    "{key.upper()}" to lazy {{'
    if tag in body:
        # 已有该块：整块替换（连同它上方那行 `/** … */` 注释），其余块一字不动
        start = body.rfind('\n    /**', 0, body.index(tag))
        end = body.find('\n    ) },', body.index(tag))
        if start < 0 or end < 0:
            raise SystemExit('找不到该方案的注释行或块尾，拒绝替换（源码结构变了？）')
        body = body[:start + 1] + block + body[end + len('\n    ) },'):]
    else:
        close = body.rstrip().rfind(')')
        if close < 0:
            raise SystemExit('源码尾部结构变了：找不到 mapOf( 的收尾括号')
        body = body[:close] + block + '\n' + body[close:]
    return head + '\n' + body


def main():
    argv = sys.argv[1:]
    only = None
    if '--only' in argv:
        at = argv.index('--only')
        only = argv[at + 1] if at + 1 < len(argv) else None
        if not only:
            raise SystemExit('--only 需要一个方案 key（如 --only shoudao）')
        if only not in {k for k, _, _ in SCHEMES}:
            raise SystemExit(f'未知方案 {only}；可选：{", ".join(k for k, _, _ in SCHEMES)}')

    syllables = [l.strip() for l in read_asset_text(SYLL).split('\n') if l.strip()]
    counts = None if only else dict_key_counts()
    print(f'音节表 {len(syllables)} 个；词库唯一键 {"未读（--only）" if counts is None else len(counts)} 个')
    os.makedirs(OUT_DIR, exist_ok=True)

    kt_blocks, report = [], []
    for key, schema, cn in SCHEMES:
        if only and key != only:
            continue
        if schema is None:
            spec = TABLE_SCHEMES[key]
            initials, per_syllable, code_map, collisions, bad = build_table_scheme(spec, syllables)
            if bad:
                raise SystemExit(f'{cn} 有音节编不出来：{bad}（先补规格表，或显式进 skip）')
            source = spec.get('note', '规格表')
        else:
            if only:
                raise SystemExit('--only 只支持表驱动方案：rime 方案的同码裁决需要词频留档')
            path = os.path.join(RIME, schema + '.schema.yaml')
            rules = parse_algebra(path)
            alphabet = parse_alphabet(path)
            source = f'rime-ice `{schema}.schema.yaml`'

            initials = {}
            for ini in PROBE_INITIALS:
                codes = apply_rules(rules, ini + 'a')
                if codes:
                    initials.setdefault(codes[0][0], ini)

            per_syllable, bad = {}, []
            for syl in syllables:
                if syl in SKIP_SYLLABLES:
                    continue
                codes = [c for c in apply_rules(rules, syl)
                         if len(c) == 2 and set(c) <= alphabet]
                if not codes:
                    bad.append(syl)
                else:
                    per_syllable[syl] = codes

            rev = {}
            for syl, codes in per_syllable.items():
                for c in codes:
                    rev.setdefault(c, []).append(syl)
            code_map, collisions = {}, []
            for c, sylls in sorted(rev.items()):
                uniq = sorted(set(sylls))
                chosen = pick_canonical(uniq, counts)
                code_map[c] = chosen
                if len(uniq) > 1:
                    collisions.append((c, uniq, chosen))

        if key == 'ziranma':
            code_map.update(ZIRANMA_COMPAT)

        needs_semi = any(';' in c for c in code_map)
        report.append(f'{cn:12s} 音节 {len(per_syllable):3d}/{len(syllables)}  码 {len(code_map):3d}  '
                      f'冲突 {len(collisions)}  未编码 {len(bad)}  '
                      f'分号键 {"需要" if needs_semi else "不需要"}')
        for c, sylls, chosen in collisions:
            report.append(f'        裁决 {c:>3s}: {"/".join(sylls)} → {chosen}')
        if bad:
            report.append('        未编码: ' + ' '.join(bad))

        kt_blocks.append(render_block(key, cn, source, len(per_syllable), initials, code_map))

        io.open(os.path.join(OUT_DIR, key + '.json'), 'w', encoding='utf-8').write(
            json.dumps({'name': cn, 'schema': schema, 'initials': initials,
                        'codes': code_map}, ensure_ascii=False, indent=1))

    print('\n'.join(report))
    if only:
        src = io.open(OUT_KT, encoding='utf-8').read()
        out = splice_block(src, only, kt_blocks[0], len(syllables))
        write_bytes_atomically(OUT_KT, out.encode('utf-8'))
        print(f'\n仅重写 {only} 块（其余块逐字保留）：{OUT_KT}')
    else:
        body = KT_HEADER % len(syllables) + '\n'.join(kt_blocks) + '\n)\n'
        # 原子落盘（BUG.md L-122）：OUT_KT 是**仓库内 Kotlin 源码**，半截源码照样能编译，只会静默缺尾
        write_bytes_atomically(OUT_KT, body.encode('utf-8'))
        print(f'\nKotlin 数据已写入 {OUT_KT}')


if __name__ == '__main__':
    sys.exit(main())
