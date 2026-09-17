#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 rime-ice 的双拼 schema 生成 Kotlin 键位数据（app/.../ShuangpinSchemes.kt）。

为什么要有这个脚本
------------------
手抄 7 套方案（自然码/小鹤/搜狗/微软/紫光/ABC/加加）的键位表必然出错且无法自证。
rime 的 `speller/algebra` 是权威定义：本脚本按 librime 的代数语义把它逐条施加到项目的
422 个合法音节上，算出每套方案的编码，再做三件事：

  1. 冲突裁决：同一码对应多个音节时（如 `lo` = lo/luo、`lt` = lue/lve），
     按词库实际写法决定首选，裁决过程写进运行报告。
  2. 键面提示派生：提示由**同一张表**在运行期反推（见生成的 ShuangpinTable），
     保证键面与引擎永不漂移（旧实现是两份手写数据，已出现 'o' 键提示错误）。
  3. 产出 Kotlin：`initials`（声母键）+ `codes`（码→音节，扁平表）。

用法
----
    python tools/dict_builder/gen_shuangpin_tables.py
    再跑 tools/dict_builder/verify_shuangpin_migration.py 对拍（换表不许改行为）

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

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RIME = os.path.join(ROOT, 'docs', 'rime-ice')
SYLL = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'pinyin_syllables.txt')
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
]

PROBE_INITIALS = ['b', 'p', 'm', 'f', 'd', 't', 'n', 'l', 'g', 'k', 'h', 'j', 'q', 'x',
                  'zh', 'ch', 'sh', 'r', 'z', 'c', 's', 'y', 'w']

# 一码多音节时的裁决（冲突在 7 套方案里形态一致，规则统一）：
#   键 = 被淘汰写法，值 = 保留写法；值为 None 表示「按对手保留」。
#   依据：词库实际写法计数 + 「不改动已上线行为」原则。
COLLISION_PREFER = {
    # 「两键一音节」模型下一码只能映射一个音节，同码者必须让位。裁决原则：
    #   ① 不改动换表前已上线的行为（自然码老实现是 finalFor 的手写分支）；
    #   ② 词库 0 条的叹词让位给真实音节。
    'lo': 'luo',    # 让位给 luo（与旧实现一致：旧实现 'o' 在 l 后取 uo）→ 咯/啰 双拼打不出
                    # （全拼仍可输入；rime 因为保留多个拼写所以两个都能打，本项目不保留多拼写）
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
 * 双拼键位数据（**由脚本生成，勿手改**）。
 *
 * 生成器：`tools/dict_builder/gen_shuangpin_tables.py`
 * 权威来源：`docs/rime-ice/double_pinyin*.schema.yaml` 的 `speller/algebra`——
 * 脚本按 librime 的代数语义（xform 替换 / derive 追加 / erase / xlit）逐条施加到
 * `assets/pinyin_syllables.txt` 的 %d 个合法音节上，算出每套方案的码表。
 *
 * 因此本文件的键位与 rime-ice 完全一致（含 rime 的 derive 容错码）；唯一的人工裁决是
 * 「一码多音节」的取舍（如 `lo` = lo/luo），规则见生成器里的 COLLISION_PREFER 注释。
 *
 * 键面提示（字母键下方的韵母、zh/ch/sh 红字）**由本表在运行期反推**，不另存一份，
 * 以保证键面与引擎永不漂移。
 *
 * **每套方案惰性构建**（`Lazy`）：7 套表一次全建约 3,000 条目，实测首访耗时可观，
 * 而键盘视图是在 `onCreateInputView`（键盘首次弹出）里创建的——若在该路径同步建表会拖慢首次弹出。
 * 因此改为「用到哪套建哪套」，并在 IME 服务创建时由后台线程预热（见 `Shuangpin.warmUp`）。
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
     * 韵母表规范顺序：仅用于**同一行内**的提示排序（长者仍在前）。
     * 目的：让「哪两个韵母同屏」有确定且符合直觉的次序，例如微软 v 键显示 `ui ue` 而非 `ue ui`。
     */
    private val finalOrder = listOf(
        "a", "o", "e", "ai", "ei", "ao", "ou", "an", "en", "ang", "eng", "ong",
        "i", "ia", "ie", "iao", "iu", "ian", "in", "iang", "ing", "iong",
        "u", "ua", "uo", "uai", "ui", "uan", "un", "uang", "ueng",
        "v", "ve", "van", "vn",
    )

    /** 该声母键对应的声母；非声母键返回 null */
    fun initialOf(key: Char): String? = initials[key]

    /** 声母反查键（红字提示用）：zh → v */
    fun keyOfInitial(initial: String): Char? =
        initials.entries.firstOrNull { it.value == initial }?.key

    /** 韵母键 → 该键承载的韵母（由码表反推，因此永远与引擎一致） */
    private val finalsByKey: Map<Char, List<String>> = LinkedHashMap<Char, MutableList<String>>().apply {
        for ((code, syllable) in codes) {
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
     * 规则（**用户明示，不得违反**）：
     *  1. **绝不显示与该键字母相同的韵母**——e 键不显示 e、v 键不显示 v、a/i/u 同理，
     *     属纯冗余；
     *  2. ü 的两种写法同时出现时（u 对应 jqxy 后、v 对应 l/n 后）**只留 v**，
     *     否则同一键上会出现两个表示同一读音的项；
     *  3. **总显示行数强制 ≤ 2**（含红色 zh/ch/sh 那一行）：
     *     · 该键承担 zh/ch/sh 时韵母挤成一行（红色占第 2 行）；
     *     · 否则每个韵母各占一行（最多 2 个）。
     *
     * 例：自然码/小鹤/加加 v → `ui` + 红 `zh`；微软 v → 一行 `ui ue` + 红 `zh`；
     * 搜狗/微软 y → `uai` / `v` 两行。
     */
    fun finalHint(key: Char): String {
        val raw = finalsByKey[key] ?: return ""
        // 注意顺序：先去重 ü 的两种写法（此时 v 还在，才能判断），再剔除与键位相同的韵母。
        // 反过来做会漏掉「v 键的 u」（自然码/小鹤/加加的 v 键原始集合是 {ui, u, v}：
        // 先去自身会得到 {ui, u}，u 便再无参照可去）。
        var list = if ("v" in raw && "u" in raw) raw.filter { it != "u" } else raw.toList()
        list = list.filter { it != key.toString() }
        list = list.distinct().sortedWith(
        compareByDescending<String> { it.length }
            .thenBy { finalOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } },
    ).take(2)
        if (list.isEmpty()) return ""
        if (list.size == 1) return list[0]
        val hasRedInitial = (initials[key]?.length ?: 0) > 1
        return if (hasRedInitial) list.joinToString(" ") else list.joinToString("\\n")
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
            if re.match(r'^\S', line):
                break
            m = re.match(r'^\s*-\s*(xform|derive|erase|xlit)/(.*)/\s*(#.*)?$', line)
            if not m:
                continue
            rules.append((m.group(1), m.group(2).split('/')))
    return rules


def parse_alphabet(path):
    m = re.search(r'^\s*alphabet:\s*(\S+)\s*$', io.open(path, encoding='utf-8').read(), re.M)
    return set(m.group(1)) if m else set()


def apply_rules(rules, spelling):
    cur = [spelling]
    for kind, parts in rules:
        nxt = []
        if kind in ('xform', 'derive'):
            pat = parts[0]
            rep = (parts[1] if len(parts) > 1 else '').replace('$', '\\')
            for s in cur:
                if kind == 'derive':
                    nxt.append(s)
                    if re.search(pat, s):
                        nxt.append(re.sub(pat, rep, s))
                else:
                    nxt.append(re.sub(pat, rep, s))
        elif kind == 'erase':
            nxt = [s for s in cur if not re.fullmatch(parts[0], s)]
        elif kind == 'xlit':
            table = {ord(a): b for a, b in zip(parts[0], parts[1])}
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

    def weight(s):
        return sum(v for k, v in counts.items() if k.startswith(s))

    return sorted(sylls, key=lambda s: (-weight(s), s))[0]


def main():
    syllables = [l.strip() for l in io.open(SYLL, encoding='utf-8') if l.strip()]
    counts = dict_key_counts()
    print(f'音节表 {len(syllables)} 个；词库唯一键 {len(counts)} 个')
    os.makedirs(OUT_DIR, exist_ok=True)

    kt_blocks, report = [], []
    for key, schema, cn in SCHEMES:
        path = os.path.join(RIME, schema + '.schema.yaml')
        rules = parse_algebra(path)
        alphabet = parse_alphabet(path)

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

        lines = [f'    /** {cn}（rime-ice `{schema}.schema.yaml`）：'
                 f'{len(per_syllable)} 个音节 / {len(code_map)} 个码 */',
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
        kt_blocks.append('\n'.join(lines))

        io.open(os.path.join(OUT_DIR, key + '.json'), 'w', encoding='utf-8').write(
            json.dumps({'name': cn, 'schema': schema, 'initials': initials,
                        'codes': code_map}, ensure_ascii=False, indent=1))

    print('\n'.join(report))
    body = KT_HEADER % len(syllables) + '\n'.join(kt_blocks) + '\n)\n'
    io.open(OUT_KT, 'w', encoding='utf-8', newline='\n').write(body)
    print(f'\nKotlin 数据已写入 {OUT_KT}')


if __name__ == '__main__':
    sys.exit(main())
