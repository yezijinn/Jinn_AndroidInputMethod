#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
向现有词库追加词条 —— 用于修复「连写歧义」类漏词。

用法
----
    python add_words.py <现有 pinyin_phrases.txt> <补词表.txt> -o <输出目录>

补词表格式（TAB 分隔，第三列可选）
--------------------------------
    词<TAB>拼音（空格分隔）[<TAB>插入位置(1-based，默认追加到末尾)]

例：企鹅	qi e	4

为什么需要它
-----------
拼音连写会吞掉音节边界：词的拼音 S1 + Z（Z 为零声母音节 a/ai/an/ang/ao/e/ei/en/eng/er/o/ou）
连写后若恰好等于另一个合法单音节，该词就与那个单音节同键。
「企鹅」(qi e) 与「切」(qie) 同键、「西安」(xi an) 与「先」(xian) 同键。

一旦源词表漏收这类词，用户输入 qie（双拼 qiee）只能看到「切/且/窃」，永远打不出「企鹅」。
识别工具见同目录 detect_ambiguous_keys.py。

本脚本只做数据合并，不改动已有词条的相对顺序；位置参数用于把补入的词插到
常用单字之后，避免它抢走第一候选，也避免被埋在几十个候选的末尾翻不到。
"""
import sys
from collections import OrderedDict
from pathlib import Path


def load_existing(path):
    """compact拼音 -> [词...]（保持原顺序）"""
    table = OrderedDict()
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or "\t" not in line:
                continue
            key, words = line.split("\t", 1)
            table[key] = words.split("|")
    return table


def load_additions(path):
    """补词表 -> [(词, compact键, 位置或None)]"""
    out = []
    with open(path, encoding="utf-8") as f:
        for lineno, raw in enumerate(f, 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            parts = [p.strip() for p in line.split("\t") if p.strip()]
            if len(parts) < 2:
                print(f"[警告] 第 {lineno} 行缺少拼音，已跳过: {line}", file=sys.stderr)
                continue
            word, pinyin = parts[0], parts[1]
            pos = int(parts[2]) if len(parts) > 2 and parts[2].isdigit() else None
            out.append((word, pinyin.replace(" ", ""), pos))
    return out


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    src, add_path = sys.argv[1], sys.argv[2]
    out_dir = Path(sys.argv[sys.argv.index("-o") + 1]) if "-o" in sys.argv else Path("out")
    out_dir.mkdir(parents=True, exist_ok=True)

    table = load_existing(src)
    print(f"现有词库 {len(table)} 键")

    additions = load_additions(add_path)
    added, moved, skipped = 0, 0, 0
    for word, key, pos in additions:
        bucket = table.setdefault(key, [])
        if word in bucket:
            bucket.remove(word)
            moved += 1
        if pos is None or pos > len(bucket) + 1:
            bucket.append(word)
        else:
            bucket.insert(pos - 1, word)
        added += 1

    out_path = out_dir / "pinyin_phrases.txt"
    with open(out_path, "w", encoding="utf-8") as f:
        for key, words in table.items():
            if words:
                f.write(f"{key}\t{'|'.join(words)}\n")
    print(f"补入 {added} 词（其中重排已有词 {moved}），跳过 {skipped}")
    print(f"输出: {out_path}（{len(table)} 键）")


if __name__ == "__main__":
    main()
