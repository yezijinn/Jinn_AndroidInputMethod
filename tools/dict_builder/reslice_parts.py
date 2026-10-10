#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""下载包分片重切（B+，2026-10-10 起固定口径）：旧 4 片 → 新 3 片。

口径（用户 2026-10-10 确认，已发布 tag `dict-parts-20261010-v1`）：
  新 part2 = 旧 part2 + 旧 part3 + 基带 ≥6000 的新词（daily_chat / net_hot）
  新 part3 = 旧 part4 + 常用类新词（10 库）+ 倒挂修正词
  新 part4 = 专业长尾 5 库（lianxiang / wuzhong / yixue / huaxue / yaopin）− 倒挂修正词

  倒挂修正：专业长尾库中「基带 > 同键旧 part4 词频」的词挪到 part3 —— 跨包候选顺序 = **包序优先**
  （`PinyinEngine.phrasesFor`），不修正会出现「旧低频词排在基带更高的新词之前」。
  去重：新词与旧池（第 1 部分 + 旧 2/3/4）同词一律丢弃（保旧）；分片闸要求全库词唯一。

输入（冻结口径，可重复运行；不会读到「已经重切过」的当前文件）：
  · 旧片：`docs/所有词库 - 备份20261010重切前/短语/词库_第{2,3,4}部分.txt`
  · 第 1 部分：`docs/所有词库/短语/词库_第1部分.txt`（重切不改它；只取词集合做去重）
  · 新词：`docs/new_weighted/*.txt`（17 库，`词<TAB>连写拼音<TAB>词频`）
输出：`docs/所有词库/短语/词库_第{2,3,4}部分.txt`（原地覆盖；`--out-dir` 可写到别处做对拍）
后续：输出变了必须重跑 `build_dicts.py --only parts` 重建下载包（并换新 tag 发布，见交接文档第十节）

用法：python tools/dict_builder/reslice_parts.py [--write] [--out-dir DIR] [--force]
"""
import collections
import io
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PHRASES = os.path.join(ROOT, "docs", "所有词库", "短语")
NEW = os.path.join(ROOT, "docs", "new_weighted")
BACKUP = os.path.join(ROOT, "docs", "所有词库 - 备份20261010重切前", "短语")
PARTS = {2: "词库_第2部分.txt", 3: "词库_第3部分.txt", 4: "词库_第4部分.txt"}
PART1 = "词库_第1部分.txt"

# 类别基带（与 weight_extra_dicts.BASE / build_shici_lib 同步；本脚本按此值重写第三列）
BANDS = {
    "daily_chat": 8000, "net_hot": 6000, "jargon": 5000, "media": 4000, "ai_term": 4000,
    "mingren": 3000, "yiren": 3000, "chengyu": 3000, "games": 2000, "klbq": 2000,
    "shici": 1500, "diming": 1500, "wuzhong": 1000, "yixue": 1000, "huaxue": 1000,
    "yaopin": 1000, "lianxiang": 800,
}
TO_PART2 = ("daily_chat", "net_hot")
LONGTAIL = ("lianxiang", "wuzhong", "yixue", "huaxue", "yaopin")

# 2026-10-10 首次重切的产出（用于自检；口径有意变更时同步改这里，或用 --force 跳过）
EXPECT = {2: 900061, 3: 700606, 4: 213194}
MISMATCH_WARN = "产出与 2026-10-10 基准不符"

# 音节表（连写 → 空格切分）
syls = set()
for l in io.open(os.path.join(ROOT, "docs", "dict_review", "音节表.txt"), encoding="utf-8"):
    s = l.strip()
    if s:
        syls.add(s)


def split_py(py):
    n = len(py)
    memo = {}

    def dfs(i):
        if i == n:
            return []
        if i in memo:
            return memo[i]
        for j in range(min(n, i + 6), i, -1):
            if py[i:j] in syls:
                r = dfs(j)
                if r is not None:
                    memo[i] = [py[i:j]] + r
                    return memo[i]
        memo[i] = None
        return None

    r = dfs(0)
    return " ".join(r) if r else None


def key_of(py_col):
    return py_col.replace(" ", "").lower()


def read_old(path):
    """旧片：行原样保留；返回 (原始行列表, 词集合, 每键最大词频)。"""
    rows = []
    keymax = {}
    words = set()
    for l in io.open(path, encoding="utf-8"):
        line = l.rstrip("\n")
        c = line.split("\t")
        if len(c) < 3:
            continue
        rows.append(line)
        words.add(c[0])
        k = key_of(c[1])
        f = int(c[2]) if c[2].isdigit() else 0
        if f > keymax.get(k, -1):
            keymax[k] = f
    return rows, words, keymax


def read_new(name):
    """新词库：连写拼音切分为空格，词频重写为类别基带（与清单口径一致）。"""
    rows = []
    words = set()
    badpy = 0
    band = BANDS[name]
    for l in io.open(os.path.join(NEW, "%s.txt" % name), encoding="utf-8"):
        c = l.rstrip("\n").split("\t")
        if len(c) < 3:
            continue
        w, py = c[0], c[1]
        if " " in py:
            # 已是空格格式（weight_extra_dicts 现行版输出）：原样透传，不再反解
            sp = py
        else:
            sp = split_py(py)
            if sp is None:
                badpy += 1
                sp = py
        rows.append("%s\t%s\t%d" % (w, sp, band))
        words.add(w)
    return rows, words, badpy


def main():
    missing = [p for p in PARTS.values() if not os.path.isfile(os.path.join(BACKUP, p))]
    if missing:
        raise SystemExit("缺少重切前备份 %s/*：%s\n（该目录是冻结输入，删了就无法按 2026-10-10 口径复现）"
                         % (BACKUP, missing))

    print("== 读旧片（重切前备份）==")
    old = {}
    for p in (2, 3, 4):
        rows, words, keymax = read_old(os.path.join(BACKUP, PARTS[p]))
        old[p] = (rows, words, keymax)
        print("  part%d: %d 条 / %d 词" % (p, len(rows), len(words)))

    # 全局已用词：第 1 部分（内置，重切不改）+ 旧三片
    part1_words = set()
    for l in io.open(os.path.join(PHRASES, PART1), encoding="utf-8"):
        c = l.split("\t")
        if c:
            part1_words.add(c[0])
    print("  part1（内置，只取词集合）: %d 词" % len(part1_words))

    seen = set(part1_words)
    for p in (2, 3, 4):
        seen |= old[p][1]

    missing_new = [n for n in BANDS if not os.path.isfile(os.path.join(NEW, "%s.txt" % n))]
    if missing_new:
        raise SystemExit("缺少新词库文件 docs/new_weighted/*.txt：%s\n（由 convert_wanxiang / gen_* / "
                         "weight_extra_dicts / build_shici_lib 生成）" % missing_new)

    print("== 读新词并分流 ==")
    new_rows = {k: [] for k in BANDS}
    moved = []          # 倒挂修正：挪到 part3 的长尾词
    skipped = 0
    for name in sorted(BANDS):
        rows, words, badpy = read_new(name)
        band = BANDS[name]
        moved_inv = 0
        out = []
        for line in rows:
            w = line.split("\t")[0]
            if w in seen:               # 与旧池同词：保旧（旧词已在某片里，用户照样能打出）
                skipped += 1
                continue
            seen.add(w)
            if name in LONGTAIL:
                k = key_of(line.split("\t")[1])
                m4 = old[4][2].get(k)
                if m4 is not None and band > m4:
                    moved_inv += 1
                    moved.append(line)
                    continue
            out.append(line)
        new_rows[name] = out
        print("  %-10s %6d 条（音节切分失败 %d，倒挂挪动 %d）" % (name, len(out), badpy, moved_inv))
    print("  与旧池同词跳过：%d 条" % skipped)
    print("  倒挂修正挪入 part3：%d 条" % len(moved))

    part2_rows = old[2][0] + old[3][0]
    for name in TO_PART2:
        part2_rows += new_rows[name]
    part3_rows = old[4][0] + moved
    for name in BANDS:
        if name not in TO_PART2 and name not in LONGTAIL:
            part3_rows += new_rows[name]
    part4_rows = []
    for name in LONGTAIL:
        part4_rows += new_rows[name]

    ok = True
    for label, p, rows in (("part2'", 2, part2_rows), ("part3'", 3, part3_rows), ("part4'", 4, part4_rows)):
        mark = "OK" if len(rows) == EXPECT[p] else "!! 期望 %d" % EXPECT[p]
        if len(rows) != EXPECT[p]:
            ok = False
        # 片内词唯一（分片闸的前置条件）
        ws = [l.split("\t")[0] for l in rows]
        dup = len(ws) - len(set(ws))
        print("%s: %d 条（%s；片内重复 %d）" % (label, len(rows), mark, dup))

    if "--write" in sys.argv:
        if not ok and "--force" not in sys.argv:
            raise SystemExit("%s —— 如口径有意变更请同步 EXPECT 并加 --force\n（未写盘）" % MISMATCH_WARN)
        out_dir = PHRASES
        for i, a in enumerate(sys.argv):
            if a == "--out-dir":
                out_dir = sys.argv[i + 1]
        for p, rows in ((2, part2_rows), (3, part3_rows), (4, part4_rows)):
            dst = os.path.join(out_dir, PARTS[p])
            os.makedirs(out_dir, exist_ok=True)
            with io.open(dst, "w", encoding="utf-8", newline="\n") as f:
                for line in rows:
                    f.write(line + "\n")
            print("已写入 %s（%d 条）" % (dst, len(rows)))
        if out_dir == PHRASES:
            print("⚠ 分片已变：请重跑 build_dicts.py --only parts，并按交接文档第十节换新 tag 发布")


main()
