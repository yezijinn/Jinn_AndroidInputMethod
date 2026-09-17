#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对拍：新生成的键位表 vs 现有手写的 自然码实现。

目的：换表不能改行为。现有实现（PinyinEngine.kt 的 object Shuangpin）是手写的，
本脚本把它的规则**逐条照抄**成 Python，然后在全部 26×26 = 676 个两键码上
与新生成的 `ShuangpinSchemes.kt`（ZIRANMA 表）比对，输出任何差异。

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
    text = io.open(KT, encoding='utf-8').read()
    start = text.index(scheme_key + ' = ShuangpinScheme(')
    body = text[start:]
    ci = body.index('codes = mapOf(')
    block = body[ci:]
    end = block.index('\n        ),')
    pairs = re.findall(r'"([^"]{2})" to "([^"]*)"', block[:end])
    return {c: s for c, s in pairs}


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
    return 0 if not diffs and not only_old else 1


if __name__ == '__main__':
    sys.exit(main())
