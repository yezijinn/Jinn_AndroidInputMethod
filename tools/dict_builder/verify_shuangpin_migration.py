#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对拍：生成的键位表 vs 迁移前的手写 自然码实现（比对基线）。

目的：换表不能改行为。迁移前的实现（原 `PinyinEngine.kt` 的 object Shuangpin）是手写的，
本脚本把它的规则**逐条照抄**成 Python，然后在全部 26×26 = 676 个两键码上
与 `ShuangpinSchemes.kt` 的 ZIRANMA 表比对。

判据（重要）：只有**旧实现给出合法音节**的差异才算回归。旧实现是纯公式，对任意两键都作答
（`ab`→`aou`、`ag`→`aeng` 这些音节并不存在），而新表由 rime-ice 的完整音节集生成、
对这些组合正确地「没有码」——拿胡编结果当基线会让判据永远失败，闸门就形同虚设。
「仅新表认」的码属新增容错/新音节，不算差异。

用法：python verify_shuangpin_migration.py
"""

import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, 'app', 'src', 'main', 'java', 'com', 'jinn', 'inputmethod',
                  'ShuangpinSchemes.kt')

# ── 1) 照抄现有实现（PinyinEngine.kt: object Shuangpin）──────────────────────
ZERO_TABLE = {
    'aa': 'a', 'ee': 'e', 'oo': 'o',
    'an': 'an', 'ai': 'ai', 'ao': 'ao', 'ei': 'ei', 'en': 'en', 'er': 'er', 'ou': 'ou',
    'ah': 'ang', 'eg': 'eng',
    'aj': 'an', 'al': 'ai', 'ak': 'ao', 'ez': 'ei', 'ef': 'en', 'or': 'er',
    'oj': 'an', 'ol': 'ai', 'ok': 'ao', 'oh': 'ang', 'oz': 'ei', 'of': 'en',
    'og': 'eng', 'ob': 'ou', 'oa': 'a', 'oe': 'e',
}
SINGLE_FINALS = {
    'a': 'a', 'l': 'ai', 'j': 'an', 'h': 'ang', 'k': 'ao',
    'e': 'e', 'z': 'ei', 'f': 'en', 'g': 'eng',
    'i': 'i', 'm': 'ian', 'c': 'iao', 'x': 'ie',
    'n': 'in', 'q': 'iu', 'b': 'ou', 'u': 'u',
}
UAI_INITIALS = {'g', 'k', 'h', 'zh', 'ch', 'sh'}
UA_INITIALS = {'g', 'k', 'h', 'zh', 'ch', 'sh'}
JQX = {'j', 'q', 'x'}
JQX_Y = {'j', 'q', 'x', 'y'}
L_N = {'l', 'n'}


def old_initial(k):
    return {'v': 'zh', 'i': 'ch', 'u': 'sh'}.get(k, k if 'a' <= k <= 'z' else '')


def old_final(initial, key):
    if key == 's':
        return 'iong' if initial in JQX else 'ong'
    if key == 'r':
        return 'uan'
    if key == 't':
        return 'ue'
    if key == 'p':
        return 'un'
    if key == 'v':
        if initial in L_N:
            return 'v'
        if initial in JQX_Y:
            return 'u'
        return 'ui'
    if key == 'o':
        return 'o' if initial in {'b', 'p', 'm', 'f', 'w'} else 'uo'
    if key == 'y':
        return 'uai' if initial in UAI_INITIALS else 'ing'
    if key == 'w':
        return 'ua' if initial in UA_INITIALS else 'ia'
    if key == 'd':
        return 'uang' if initial in UA_INITIALS else 'iang'
    return SINGLE_FINALS.get(key)


def old_syllable(k1, k2):
    hit = ZERO_TABLE.get(k1 + k2)
    if hit:
        return hit
    ini = old_initial(k1)
    if not ini:
        return None
    fin = old_final(ini, k2)
    if fin is None:
        return None
    return ini + fin


# ── 2) 读新生成的表 ────────────────────────────────────────────────────────
def new_table(scheme_key):
    """从生成的 Kotlin 表里抠出「两键码 → 音节」。

    生成格式（见 `gen_shuangpin_tables.py` 的模板）：
        "ZIRANMA" to lazy { ShuangpinTable(
            initials = mapOf( ... ),
            codes = mapOf(
                "aa" to "a", ...
            ),
        ) },
    键位表在双拼重构中由「枚举构造器」改成了「String → Lazy<ShuangpinTable> 的映射」，
    本函数已按新格式改写：旧写法找 `ZIRANMA = ShuangpinScheme(` 会直接抛 ValueError，
    而脚本一崩，「换表不许改行为」这道闸门就等于不存在。
    """
    text = io.open(KT, encoding='utf-8').read()
    start = text.index('"%s" to lazy { ShuangpinTable(' % scheme_key)
    block = text[text.index('codes = mapOf(', start):]
    end = re.search(r'\n\s*\)', block).start()          # codes 映射的收尾括号
    pairs = re.findall(r'"([^"]{2})" to "([^"]*)"', block[:end])
    if not pairs:
        raise SystemExit(
            '未能从 ShuangpinSchemes.kt 解析出 %s 的码表——生成格式可能又变了，'
            '请同步本函数' % scheme_key)
    return {c: s for c, s in pairs}


def valid_syllables():
    """合法音节全集（`assets/pinyin_syllables.txt`）：判据只认「旧结果落在合法音节里」的差异。"""
    p = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'pinyin_syllables.txt')
    return set(io.open(p, encoding='utf-8').read().split())


def main():
    table = new_table('ZIRANMA')
    print(f'新表条目 {len(table)}')
    diffs, only_old, only_new = [], [], []
    for a in 'abcdefghijklmnopqrstuvwxyz':
        for b in 'abcdefghijklmnopqrstuvwxyz':
            code = a + b
            o, n = old_syllable(a, b), table.get(code)
            if o == n:
                continue
            if o is not None and n is None:
                only_old.append((code, o))
            elif o is None and n is not None:
                only_new.append((code, n))
            else:
                diffs.append((code, o, n))
    print(f'两边都有但结果不同: {len(diffs)}')
    for c, o, n in diffs[:20]:
        print(f'   {c}: 旧={o}  新={n}')
    print(f'仅旧实现认（新表缺失）: {len(only_old)}')
    for c, o in only_old[:20]:
        print(f'   {c}: 旧={o}')
    print(f'仅新表认（旧实现不认，属新增容错）: {len(only_new)}')
    for c, n in only_new[:25]:
        print(f'   {c}: 新={n}')

    # ── 判据：只有「旧实现给出**合法音节**」的差异才算回归 ───────────────────
    # 旧实现是纯公式，对任意两键都作答（ab→aou、az→aei 这些音节并不存在）；
    # 新表由 rime-ice 的完整音节集生成，对它们正确地「没有码」。
    # 若不筛掉这些胡编结果，闸门会永远为红，等于没有闸门（此前的实现正是如此：
    # valid_syllables() 定义了却没被调用）。
    valid = valid_syllables()
    regress_diff = [(c, o, n) for c, o, n in diffs if o in valid]
    regress_missing = [(c, o) for c, o in only_old if o in valid]
    # 反向情况：旧实现给非法音节、新表给合法音节，属修正而非回归，单独列出便于核对
    fixed = [(c, o, n) for c, o, n in diffs if o not in valid]

    print('-' * 68)
    print(f'判据：仅当旧结果为合法音节（{len(valid)} 个）时才算回归')
    print(f'真回归（旧=合法、新缺失）: {len(regress_missing)}')
    for c, o in regress_missing[:20]:
        print(f'   {c}: 旧={o}')
    print(f'真回归（两边都有但不同，且旧=合法）: {len(regress_diff)}')
    for c, o, n in regress_diff[:20]:
        print(f'   {c}: 旧={o}  新={n}')
    if fixed:
        print(f'（旧=非法、新=合法，属修正: {len(fixed)}）')
        for c, o, n in fixed[:10]:
            print(f'   {c}: 旧={o}（非法） -> 新={n}')
    ok = not regress_diff and not regress_missing
    print('结论：' + ('换表未改变行为 ✓' if ok else '存在行为回归 ✗'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
