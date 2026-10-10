#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""给新分类词库赋词频基带，转成主库格式（词<TAB>拼音(空格)<TAB>词频）。

背景：索引格式里没有词频字段，词频只在构建期烧成「同键内的词序」。
     所以词频数值只需让**同音词**排出合理先后，不必精确。
     基带按类别优先级给：日常聊天/网络热词最高，联想词最低。

输出：docs/new_weighted/<name>.txt  （每行一个词，格式同主库源文件）
用法：python tools/dict_builder/weight_extra_dicts.py
"""
import io, os, re, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, "docs")
OUT = os.path.join(DOCS, "new_weighted")

# 类别 -> 基带词频
BASE = {
    "daily_chat": 8000,
    "net_hot":    6000,
    "jargon":     5000,
    "media":      4000,
    "ai_term":    4000,
    "mingren":    3000,
    "yiren":      3000,
    "chengyu":    3000,
    "games":      2000,
    "klbq":       2000,
    "shici":      1500,
    "diming":     1500,
    "wuzhong":    1000,
    "yixue":      1000,
    "huaxue":     1000,
    "yaopin":     1000,
    "lianxiang":  800,
}

# 音节表（切分用）
syls = set()
for l in io.open(os.path.join(DOCS, "dict_review", "音节表.txt"), encoding="utf-8"):
    l = l.strip()
    if l: syls.add(l)

def split_py(py):
    n = len(py); memo = {}
    def dfs(i):
        if i == n: return []
        if i in memo: return memo[i]
        for j in range(min(n, i+6), i, -1):
            if py[i:j] in syls:
                r = dfs(j)
                if r is not None:
                    memo[i] = [py[i:j]] + r
                    return memo[i]
        memo[i] = None
        return None
    r = dfs(0)
    return " ".join(r) if r else None

def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for name, base in BASE.items():
        src = os.path.join(DOCS, "%s_ime_dict.txt" % name)
        if not os.path.isfile(src):
            continue
        rows = []
        bad = 0
        for l in io.open(src, encoding="utf-8"):
            l = l.strip()
            if not l: continue
            c = l.split()
            w = c[0]
            py = "".join(c[1:])
            sp = split_py(py)
            if not sp:
                bad += 1
                continue
            rows.append((w, sp))
        out = os.path.join(OUT, "%s.txt" % name)
        with io.open(out, "w", encoding="utf-8", newline="\n") as f:
            for w, sp in rows:
                f.write("%s\t%s\t%d\n" % (w, sp, base))
        total += len(rows)
        print("%-12s 基带 %5d  %6d 条  切分失败 %d" % (name, base, len(rows), bad))
    print("合计 %d 条 -> %s" % (total, OUT))

if __name__ == "__main__":
    main()
