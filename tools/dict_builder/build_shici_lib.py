#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""诗词库重建（第二步）：从选集与教材构建最终诗词库并落盘。

收录 = 教科书选诗（文言文+诗词曲）+ 唐诗三百首 + 宋词三百首 + 千家诗 +
       古诗十九首 + 南唐二主词 + 三字经 + 诗经（教材篇目 + 名句清单）
剔除 = 花间集 / 声律启蒙 / 诗经冷僻句（用户 2026-10-10 确认）

拼音：库内条目复用库内拼音（源文件 `.bak` 超集）；库外条目用 **pypinyin**（今音，与库内口径一致）
依赖：`pip install pypinyin`（本机 0.55.0）；输入 `docs/诗词选集/_shijing_mingju.txt`
      （先跑 `filter_shici.py` 导出）
产物：docs/shici_ime_dict.txt（`词 拼音`）、docs/new_weighted/shici.txt（`词<TAB>拼音<TAB>1500`）
用法：python tools/dict_builder/build_shici_lib.py [--write]
"""
import io
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SEL = os.path.join(ROOT, "docs", "诗词选集", "huajianji", "data")
LIB_SRC = os.path.join(ROOT, "docs", "shici_ime_dict.txt.bak")   # 原始源文件（33.5 万，超集）
TSCHARS = os.path.join(ROOT, "docs", "freq", "OpenCC-TSCharacters.txt")
SYLL = os.path.join(ROOT, "docs", "dict_review", "音节表.txt")
OUT_SRC = os.path.join(ROOT, "docs", "shici_ime_dict.txt")
OUT_NW = os.path.join(ROOT, "docs", "new_weighted", "shici.txt")
from pypinyin import lazy_pinyin  # noqa: E402

# 诗经保留清单（由 filter_shici.py 导出：教材篇目命中 + 名句清单）
SHIJING_MINGJU = os.path.join(ROOT, "docs", "诗词选集", "_shijing_mingju.txt")
if not os.path.isfile(SHIJING_MINGJU):
    raise SystemExit("缺少 %s —— 请先运行 tools/dict_builder/filter_shici.py 导出诗经清单" % SHIJING_MINGJU)
SHIJING_SET = set()
for l in io.open(SHIJING_MINGJU, encoding="utf-8"):
    w = l.strip()
    if w:
        SHIJING_SET.add(w)

HAN = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff\U00020000-\U0003ffff]{2,10}$")
SPLIT = re.compile(r"[，。！？；：、,.;:!?…—「」『』（）()《》\[\]\"'“”‘’·\s]+")
T2S = {}
for l in io.open(TSCHARS, encoding="utf-8"):
    if l.startswith("#") or not l.strip():
        continue
    c = l.rstrip("\n").split("\t")
    if len(c) == 2 and len(c[0]) == 1:
        T2S[c[0]] = c[1].split(" ")[0]

syls = set()
for l in io.open(SYLL, encoding="utf-8"):
    s = l.strip()
    if s:
        syls.add(s)


def valid_pinyin(py):
    n = len(py)
    memo = {}

    def dfs(i):
        if i == n:
            return True
        if i in memo:
            return memo[i]
        for j in range(min(n, i + 6), i, -1):
            if py[i:j] in syls and dfs(j):
                memo[i] = True
                return True
        memo[i] = False
        return False

    return dfs(0)


def to_simp(s):
    return "".join(T2S.get(ch, ch) for ch in s)


def walk(o, acc):
    if isinstance(o, list):
        for x in o:
            walk(x, acc)
    elif isinstance(o, dict):
        for k in ("title", "paragraphs"):
            v = o.get(k)
            if isinstance(v, str):
                for s in SPLIT.split(to_simp(v)):
                    if HAN.match(s):
                        acc.add(s)
            elif isinstance(v, list):
                for p in v:
                    if isinstance(p, str):
                        for s in SPLIT.split(to_simp(p)):
                            if HAN.match(s):
                                acc.add(s)
                    else:
                        walk(p, acc)


def load(sub):
    acc = set()
    d = os.path.join(SEL, sub)
    for fn in sorted(os.listdir(d)):
        if fn.endswith(".json") and "介绍" not in fn:   # 跳过「介绍」说明文
            walk(json.load(io.open(os.path.join(d, fn), encoding="utf-8")), acc)
    return acc


# 库（原 33.5 万源文件，超集）词 → 拼音：优先复用，避免 pypinyin 多音字误差
lib = {}
for l in io.open(LIB_SRC, encoding="utf-8"):
    c = l.rstrip("\n").split(" ")
    if len(c) >= 2 and c[1]:
        lib.setdefault(c[0], c[1])

textbook = load("教科书选诗")
shijing = load("诗经")
keep = set(textbook)
for sub in ("唐诗三百首", "宋词三百首", "千家诗", "古诗十九首", "南唐二主词", "三字经"):
    keep |= load(sub)
keep |= (shijing & textbook) | (shijing & SHIJING_SET)

inside = keep & set(lib)
outside = keep - set(lib)
print("收录 %d 条：库内 %d / 库外 %d" % (len(keep), len(inside), len(outside)))

META_RE = re.compile(r"^(其[一二三四五六七八九十]|卷[一二三四五六七八九十]|第[一二三四五六七八九十])$")
META_KW = ("版本", "作者", "注本", "诗选")


def is_meta(w):
    return bool(META_RE.match(w)) or any(k in w for k in META_KW)


rows = {}       # 词 -> 拼音
bad = []
white = []      # 库外疑似白话句（含「的」等虚词）
for w in sorted(keep):
    if w in lib:
        rows[w] = lib[w]
        continue
    if "的" in w or is_meta(w):         # 白话说明文字 / 版本元数据混入教材数据
        white.append(w)
        continue
    py = "".join(lazy_pinyin(w)).replace("ü", "v").lower()
    py = re.sub(r"[^a-z]", "", py)
    if not py or not valid_pinyin(py):
        bad.append((w, py))
        continue
    rows[w] = py
print("注音完成 %d 条；音节校验失败 %d 条 %s；剔除白话句 %d 条 %s"
      % (len(rows), len(bad), bad[:8], len(white), white[:12]))

if "--write" in sys.argv:
    # 写入闸（2026-10-10）：选集数据缺失时 keep 会瘦成几百条 —— 直接覆盖会把成品库毁掉。
    # 正常口径 ≥2 万条（教材+选集去重后 21,588），下限取 1 万留足余量。
    if len(rows) < 10000:
        raise SystemExit("产出仅 %d 条（预期 ≥1 万）—— 选集/教材数据不全？已拒绝覆盖成品库" % len(rows))
    with io.open(OUT_SRC, "w", encoding="utf-8", newline="\n") as f:
        for w in sorted(rows):
            f.write("%s %s\n" % (w, rows[w]))
    with io.open(OUT_NW, "w", encoding="utf-8", newline="\n") as f:
        for w in sorted(rows):
            f.write("%s\t%s\t1500\n" % (w, rows[w]))
    print("已写入：%s（%d 条）与 %s" % (OUT_SRC, len(rows), OUT_NW))
else:
    import random
    random.seed(5)
    ok_out = [w for w in sorted(outside) if w in rows]
    for w in random.sample(ok_out, 60):
        print("  %s → %s" % (w, rows[w]))
    SUS = ("唐诗", "宋词", "诗集", "诗选", "注本", "版本", "作者", "全书", "卷一", "卷二", "其三", "其四")
    sus = [w for w in ok_out if any(k in w for k in SUS)]
    print("— 可疑（版本/选集类词）%d 条：%s" % (len(sus), " / ".join(sus[:40])))
