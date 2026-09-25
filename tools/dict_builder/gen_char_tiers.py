#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
按「现代使用证据」生成单字分档表（当前只出**第 1 档：日常用字**）。

分档口径（用户 2026-09-25 定）
----------------------------
1. 日常用字：日常简体字 + 常见人名地名专业术语 + 常见网络用字（默认档）
2. 繁体字
3. 极其少用的人名、地名、专业术语
4. 以上补集

第 1 档判据（**不照搬任何字表**：规范表只用来划定"候选池"，入选只认使用证据）
--------------------------------------------------------------
    候选池：读法上属《通用规范汉字表》收录的字（mapull 六档 frequency ≤ 4）
            —— 只借它排除繁体 / 异体 / 日文新字体，不用它决定"常用"
    A 现代通用：JunDa 字频 ≥ 300 或 影视字幕 ≥ 50（任一达标即可）
    B 现代人名：120 万现代人名语料中出现 ≥ 100 次（淼 / 喆 / 鑫 一类）
    C 历史用字：25 万古代人名语料中出现 ≥ 400 次（昇 一类，通道很窄）
    D 白名单  ：语料查不到但确实在用的网络字，逐字写理由（目前只有「囧」）
    不在候选池的字（含 8,900 个表外字）一律不进档 1，留给后续档位

为什么不用"一级 + 二级全收"
--------------------------
初版照搬规范字表导致 㤘㧐㧟㸆䁖䏝䥽䦃（扩展 A 方言字，四源全 0）、嘦嫑（0）、
氆氇（34）、氍（5）、嫒（73）这类字混进日常档 —— 规范层级说明的是"它是不是规范汉字"，
不是"它在现代用不用"。改为一律看证据。

数据来源（`docs/freq/`，公开件；出处见 docs/freq/2025-热词与词库清单.md）
    JunDa-Modern.txt                   现代汉语字频（1.935 亿字语料，GB18030）
    SUBTLEX-CH-CHR.zip                 影视字幕字频（4,684 万字，GB18030）
    char_base.json                     mapull/chinese-dictionary（MIT）：字·拼音·六档·繁体写法
    Chinese_Names_Corpus（120W）.txt   现代常见人名（2025-11-09 版）
    Ancient_Names_Corpus（25W）.txt    古代人名（2020-12-13 版）

用法
    python tools/dict_builder/gen_char_tiers.py            # 写 docs/freq/档1-*.txt|tsv
    python tools/dict_builder/gen_char_tiers.py --report   # 只打统计
"""

import argparse
import collections
import io
import json
import os
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC_DIR = os.path.join(ROOT, "docs", "freq")

# ── 收录阈值（改这里即改档 1 规模；四通道取「或」）──
# JunDa 300 会把 榛/樽/殒/璨/犷/馄（250~299 档）这类正常字误剔，故取 200
A_JUNDA, A_SUBTLEX = 200, 30        # 现代通用语料
B_NAME = 100                        # 现代人名语料
C_ANCIENT = 400                     # 古代人名语料（很窄，只放历史名人用字）

# 白名单：语料查不到、但确认在用的字。逐字写理由，别塞生僻字形梗。
WHITELIST = {
    "囧": "网络高频用字（字幕语料 37 次，未达阈值）",
}


def read_junda(path):
    """现代汉语字频表 → {字: 频次}。GB18030、Tab 分列，首列为序号。"""
    out = {}
    for line in io.open(path, encoding="gb18030", errors="replace"):
        c = line.split("\t")
        if len(c) >= 3 and c[0].strip().isdigit() and len(c[1]) == 1:
            out[c[1]] = int(c[2])
    return out


def read_subtlex(path):
    """字幕字频包 → {字: 频次}；包内 TSV 为 GB18030。"""
    z = zipfile.ZipFile(path)
    name = [n for n in z.namelist() if not n.endswith(".xlsx")][0]
    out = {}
    for line in z.read(name).decode("gb18030", errors="replace").split("\r\n"):
        c = line.split("\t")
        if len(c) > 1 and len(c[0]) == 1 and c[1].isdigit():
            out[c[0]] = int(c[1])
    return out


def read_names(path):
    """人名语料 → {字: 次数}（跳过表头两行）。"""
    cnt = collections.Counter()
    for i, line in enumerate(io.open(path, encoding="utf-8", errors="replace")):
        if i >= 2:
            s = line.strip()
            if s:
                cnt.update(s)
    return cnt


def read_mapull(path):
    """mapull 字表 → {字: frequency}。JSON Lines，行尾带逗号。"""
    out = {}
    for line in io.open(path, encoding="utf-8"):
        s = line.strip().rstrip(",")
        if not s or s in ("[", "]"):
            continue
        obj = json.loads(s)
        ch = obj.get("char", "")
        if len(ch) == 1:
            out[ch] = obj.get("frequency")
    return out


def main():
    ap = argparse.ArgumentParser(description="生成单字分档表（第 1 档：日常用字）")
    ap.add_argument("--src", default=SRC_DIR, help="数据源目录（默认 docs/freq）")
    ap.add_argument("--out", default=SRC_DIR, help="输出目录（默认 docs/freq）")
    ap.add_argument("--report", action="store_true", help="只打统计，不写文件")
    args = ap.parse_args()

    junda = read_junda(os.path.join(args.src, "JunDa-Modern.txt"))
    subtlex = read_subtlex(os.path.join(args.src, "SUBTLEX-CH-CHR.zip"))
    names = read_names(os.path.join(args.src, "Chinese_Names_Corpus（120W）.txt"))
    ancient = read_names(os.path.join(args.src, "Ancient_Names_Corpus（25W）.txt"))
    freq = read_mapull(os.path.join(args.src, "char_base.json"))

    picked = {}                                  # 字 → 来源标签
    stat = collections.Counter()
    for ch, f in sorted(freq.items()):
        if f is None or f > 4:                   # 候选池外（繁体/异体/日韩/扩展 B）
            continue
        if junda.get(ch, 0) >= A_JUNDA:
            picked[ch] = "JunDa%d" % junda[ch]
        elif subtlex.get(ch, 0) >= A_SUBTLEX:
            picked[ch] = "字幕%d" % subtlex[ch]
        elif names.get(ch, 0) >= B_NAME:
            picked[ch] = "现名%d" % names[ch]
        elif ancient.get(ch, 0) >= C_ANCIENT:
            picked[ch] = "古名%d" % ancient[ch]
        else:
            continue
        stat[picked[ch][:2]] += 1
    for ch, why in WHITELIST.items():
        picked[ch] = "白名单"
        stat["白名单"] += 1

    rows = sorted(picked)
    print("第 1 档（日常用字）: %d 字" % len(rows))
    for k in ("Ju", "字幕", "现名", "古名", "白名单"):
        print("   %-4s %5d" % (k, stat[k]))
    print("覆盖率：JunDa 语料 %.3f%%｜字幕语料 %.3f%%" % (
        100.0 * sum(v for c, v in junda.items() if c in picked) / sum(junda.values()),
        100.0 * sum(v for c, v in subtlex.items() if c in picked) / sum(subtlex.values())))
    tail = sorted(rows, key=lambda c: (junda.get(c, 0), subtlex.get(c, 0)))[:60]
    print("低频端 60 字（人工复核用）: %s" % "".join(tail))
    print("人名/古名通道单独贡献: 现名 %d 字、古名 %d 字（其余皆有现代语料证据）" % (
        sum(1 for c in rows if picked[c].startswith("现名")),
        sum(1 for c in rows if picked[c].startswith("古名"))))
    print("被本口径挡掉的样本: %s" % " ".join(
        "%s(J%s/字%s/名%s)" % (c, junda.get(c, 0), subtlex.get(c, 0), names.get(c, 0))
        for c in "㤘㧐㧟㸆䁖䏝䥽䦃嘦嫑嫒氅氆氇氍欻扽"))

    if args.report:
        return

    os.makedirs(args.out, exist_ok=True)
    with io.open(os.path.join(args.out, "档1-日常用字.txt"), "w",
                 encoding="utf-8", newline="\n") as fh:
        fh.write("# 第 1 档：日常用字（日常简体字 + 常见人名地名专业术语 + 常见网络用字）\n")
        fh.write("# 由 tools/dict_builder/gen_char_tiers.py 生成，共 %d 字\n" % len(rows))
        fh.write("# 入选只认使用证据：JunDa>=%d 或 字幕>=%d 或 现名>=%d 或 古名>=%d（或白名单）\n"
                 % (A_JUNDA, A_SUBTLEX, B_NAME, C_ANCIENT))
        for i in range(0, len(rows), 50):        # 每行 50 字，便于人工核对
            fh.write("".join(rows[i:i + 50]) + "\n")
    with io.open(os.path.join(args.out, "档1-日常用字-明细.tsv"), "w",
                 encoding="utf-8", newline="\n") as fh:
        fh.write("字\t来源\tJunDa\t字幕\t现人名\t古名\t规范档位\n")
        for ch in rows:
            f = freq.get(ch)
            fh.write("\t".join(str(x) for x in (
                ch, picked[ch], junda.get(ch, 0), subtlex.get(ch, 0),
                names.get(ch, 0), ancient.get(ch, 0),
                {0: "一级", 1: "一级", 2: "一级", 3: "二级", 4: "三级", 5: "表外",
                 None: "表外"}.get(f, "表外"),
            )) + "\n")
    print("已写入 %s/{档1-日常用字.txt, 档1-日常用字-明细.tsv}" % os.path.relpath(args.out, ROOT))


if __name__ == "__main__":
    main()
