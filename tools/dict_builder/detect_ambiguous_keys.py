#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检测「连写歧义键」——词库里可能缺失多字词的那些键。

问题背景
--------
拼音连写会吞掉音节边界。若某个词的拼音是 S1 + Z（Z 为零声母音节
a / ai / an / ang / ao / e / ei / en / eng / er / o / ou），连写后
C = S1 + Z 恰好等于**另一个合法单音节**，那这个词就与单音节 C 同键。

真实案例：「企鹅」= qi + e → 键 `qie`，与单音节「切」同键；
「西安」= xi + an → 键 `xian`，与「先」同键。

一旦生成词库时按该键「覆盖写入」而不是「合并」，词条就永久丢失——
表现为用户输入 qie（或双拼 qiee）只能看到「切/且/窃」，打不出「企鹅」。

本脚本枚举所有这类冲突键，并挑出「该键存在、但里面只有单字、一个多字词都没有」
的键：这些是最可能丢过词的键，供人工确认后补词。

用法
----
    python detect_ambiguous_keys.py <pinyin_syllables.txt> <pinyin_phrases.txt>
"""
import sys
from pathlib import Path

# 零声母音节：本身即可成音节，前面没有声母
ZERO_FINALS = [
    "a", "ai", "an", "ang", "ao",
    "e", "ei", "en", "eng", "er",
    "o", "ou",
]


def load_lines(path):
    return [line.strip() for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def load_phrases(path):
    """compact拼音 -> [词...]"""
    table = {}
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if "\t" not in line:
            continue
        key, words = line.split("\t", 1)
        table[key] = words.split("|")
    return table


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    syllables = set(load_lines(sys.argv[1]))
    phrases = load_phrases(sys.argv[2])
    print(f"合法音节 {len(syllables)} 个，词库键 {len(phrases)} 个")

    # 所有「连写后仍是合法音节」的歧义键
    ambiguous = []
    for s1 in sorted(syllables):
        for z in ZERO_FINALS:
            c = s1 + z
            if c in syllables:
                ambiguous.append((c, s1, z))
    print(f"歧义键共 {len(ambiguous)} 个（连写结果恰是另一个合法音节）")

    # 可疑：键存在，但里面清一色单字（很可能原本有多字词，被覆盖丢了）
    suspicious = []
    for c, s1, z in ambiguous:
        words = phrases.get(c)
        if not words:
            continue
        if not any(len(w) > 1 for w in words):
            suspicious.append((c, s1, z, len(words)))

    print(f"\n【可疑键】存在但只有单字，共 {len(suspicious)} 个：")
    for c, s1, z, n in suspicious:
        print(f"  {c:<10} = {s1} + {z:<4} 单字 {n} 个，无多字词  ← 可能缺词")

    # 对照：已经正常含多字词的歧义键
    healthy = [
        c for c, _s, _z in ambiguous
        if any(len(w) > 1 for w in phrases.get(c, []))
    ]
    print(f"\n【正常】已含多字词的歧义键 {len(healthy)} 个，例如：{healthy[:12]}")


if __name__ == "__main__":
    main()
