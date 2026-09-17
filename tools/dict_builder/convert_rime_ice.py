#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
把 rime-ice（雾凇拼音）词库转换成本项目格式，并输出对比报告。

用途：评估用 rime-ice 替换现有词库的可行性（阶段 1：纯读取 + 统计）。

输入：docs/rime-ice/cn_dicts/{base,ext}.dict.yaml
      格式 `词<TAB>拼音(空格分隔无调)<TAB>词频(可选)`
输出：tools/dict_builder/out/rime_ice/pinyin_phrases.txt        （转换结果，本项目格式）
      tools/dict_builder/out/rime_ice/base.txt / ext.txt        （按词长切分预览）
      tools/dict_builder/out/rime_ice/report.txt                （对比报告）

本项目格式：`拼音<TAB>词1|词2|词3...`，**同键内按词频降序**（这是现有词库缺失的信息）。

用法：
    python tools/dict_builder/convert_rime_ice.py
    python tools/dict_builder/convert_rime_ice.py --dry-run   # 只出报告不落盘
"""
import argparse
import lzma
import os
import random
import re
import sys
from collections import defaultdict

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RIME_DIR = os.path.join(ROOT, "docs", "rime-ice", "cn_dicts")
OUT_DIR = os.path.join(ROOT, "tools", "dict_builder", "out", "rime_ice")
# 文本资产不再直接进 APK（运行时改读 build_dict_index.py 产出的二进制索引）；
# 这里只留一份「上一版文本」用于体积/差异对比。
CURRENT_XZ = os.path.join(OUT_DIR, "pinyin_phrases.prev.txt.xz")

# 与本项目词库一致的压缩参数（见项目笔记：preset=7 兼顾体积与解压内存）
XZ_FILTERS = [{"id": lzma.FILTER_LZMA2, "preset": 7, "lc": 4, "pb": 0}]

# 基础包词长上限：≤4 字内置（含四字成语），>4 字进扩展包
BASE_MAX_LEN = 4

PINYIN_RE = re.compile(r"[a-z]+(?: [a-z]+)*")


def parse_rime_dict(path):
    """解析 rime dict.yaml，返回 [(词, 拼音键, 词频)]。拼音去掉空格即本项目的键。"""
    entries = []
    started = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                continue
            if line.strip() == "...":
                started = True
                continue
            if not started:
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            word, pinyin = parts[0].strip(), parts[1].strip()
            if not word or not PINYIN_RE.fullmatch(pinyin):
                continue
            freq = 0
            if len(parts) >= 3 and parts[2].strip().isdigit():
                freq = int(parts[2])
            entries.append((word, pinyin.replace(" ", ""), freq))
    return entries


def build_dict(entries):
    """按键聚合 + 同键内按词频降序去重。返回 {键: [(词, 词频), ...]}。"""
    by_key = defaultdict(dict)
    for word, key, freq in entries:
        prev = by_key[key].get(word)
        if prev is None or freq > prev:
            by_key[key][word] = freq
    return {k: sorted(v.items(), key=lambda x: -x[1]) for k, v in by_key.items()}


def render(d):
    """渲染成本项目格式文本。"""
    return "\n".join(
        f"{k}\t{'|'.join(w for w, _ in d[k])}" for k in sorted(d)
    )


def stat(d):
    keys = len(d)
    words = sum(len(v) for v in d.values())
    return keys, words


def main():
    ap = argparse.ArgumentParser(description="rime-ice 词库转换与对比")
    ap.add_argument("--dry-run", action="store_true", help="只出报告，不写文件")
    args = ap.parse_args()

    if not os.path.isdir(RIME_DIR):
        print(f"[错误] 未找到 rime-ice 词库目录: {RIME_DIR}", file=sys.stderr)
        sys.exit(1)

    report = []
    def say(s=""):
        print(s)
        report.append(s)

    say("=" * 68)
    say("rime-ice 词库转换与对比报告")
    say("=" * 68)
    say()

    # ── 1. 解析 rime-ice ──────────────────────────────
    all_entries = []
    for name in ("base", "ext"):
        path = os.path.join(RIME_DIR, f"{name}.dict.yaml")
        if not os.path.isfile(path):
            say(f"[跳过] 缺少 {name}.dict.yaml")
            continue
        ent = parse_rime_dict(path)
        say(f"解析 {name}.dict.yaml : {len(ent):,} 词条")
        all_entries += ent
    say(f"合计                : {len(all_entries):,} 词条")
    say()

    # ── 2. 聚合 ───────────────────────────────────────
    d = build_dict(all_entries)
    keys, words = stat(d)
    say(f"转换后拼音键        : {keys:,}")
    say(f"转换后词条(去重)    : {words:,}")
    say(f"平均每键            : {words / keys:.1f} 条")
    say()

    text = render(d)
    raw_mb = len(text.encode("utf-8")) / 1024 / 1024
    xz_bytes = lzma.compress(text.encode("utf-8"), filters=XZ_FILTERS)
    xz_mb = len(xz_bytes) / 1024 / 1024
    say(f"转换后文本体积      : {raw_mb:.2f} MB")
    say(f"xz 压缩后           : {xz_mb:.2f} MB")
    say()

    # ── 3. 与现有词库对比 ─────────────────────────────
    if os.path.isfile(CURRENT_XZ):
        cur_raw = lzma.decompress(open(CURRENT_XZ, "rb").read())
        cur_lines = [l for l in cur_raw.decode("utf-8").split("\n") if "\t" in l]
        cur_keys = len(cur_lines)
        cur_words = sum(len(l.split("\t", 1)[1].split("|")) for l in cur_lines)
        cur_xz_mb = os.path.getsize(CURRENT_XZ) / 1024 / 1024
        cur_raw_mb = len(cur_raw) / 1024 / 1024
        say("-" * 68)
        say(f"{'':22}{'现有词库':>14}{'rime-ice':>14}")
        say(f"{'拼音键':22}{cur_keys:>14,}{keys:>14,}")
        say(f"{'词条':22}{cur_words:>14,}{words:>14,}")
        say(f"{'文本体积(MB)':22}{cur_raw_mb:>14.2f}{raw_mb:>14.2f}")
        say(f"{'xz 体积(MB)':22}{cur_xz_mb:>14.2f}{xz_mb:>14.2f}")
        say("-" * 68)
        say(f"体积变化: {(xz_mb - cur_xz_mb) / cur_xz_mb * 100:+.1f}%")
        say()
    else:
        say("[提示] 未找到现有词库，跳过对比")
        say()

    # ── 4. 词长分布 + 切分预览 ────────────────────────
    base_words = sum(1 for v in d.values() for w, _ in v if len(w) <= BASE_MAX_LEN)
    ext_words = words - base_words
    say(f"基础包(≤{BASE_MAX_LEN}字) : {base_words:,} 词条")
    say(f"扩展包(>{BASE_MAX_LEN}字)  : {ext_words:,} 词条")
    say()

    # ── 5. 连写歧义抽查（本项目踩过的坑）──────────────
    say("连写歧义抽查（词的拼音为 S1+零声母音节，连写后可能等于另一合法单音节）:")
    amb = [k for k in ("qie", "xian", "jie", "bie", "guo") if k in d]
    for k in amb:
        sample = [w for w, _ in d[k][:6]]
        say(f"  {k:6} -> {'|'.join(sample)}")
    say()

    # ── 6. 随机样例 ───────────────────────────────────
    random.seed(7)
    say("转换结果随机样例（同键内按词频降序）:")
    for k in random.sample(sorted(d), 12):
        sample = [w for w, _ in d[k][:6]]
        say(f"  {k:16} {'|'.join(sample)}")
    say()

    # ── 7. 落盘 ───────────────────────────────────────
    if args.dry_run:
        say("[dry-run] 未写入任何文件")
    else:
        os.makedirs(OUT_DIR, exist_ok=True)
        out_all = os.path.join(OUT_DIR, "pinyin_phrases.txt")
        with open(out_all, "w", encoding="utf-8") as f:
            f.write(text)
        base_lines, ext_lines = [], []
        for line in text.split("\n"):
            k, v = line.split("\t", 1)
            bw = [w for w in v.split("|") if len(w) <= BASE_MAX_LEN]
            ew = [w for w in v.split("|") if len(w) > BASE_MAX_LEN]
            if bw:
                base_lines.append(f"{k}\t{'|'.join(bw)}")
            if ew:
                ext_lines.append(f"{k}\t{'|'.join(ew)}")
        with open(os.path.join(OUT_DIR, "base.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(base_lines))
        with open(os.path.join(OUT_DIR, "ext.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(ext_lines))
        base_xz = lzma.compress("\n".join(base_lines).encode("utf-8"), filters=XZ_FILTERS)
        ext_xz = lzma.compress("\n".join(ext_lines).encode("utf-8"), filters=XZ_FILTERS)
        say(f"已写入 {OUT_DIR}/")
        say(f"  pinyin_phrases.txt   全量转换结果")
        say(f"  base.txt             {len(base_lines):,} 键  -> xz {len(base_xz)/1024/1024:.2f} MB")
        say(f"  ext.txt              {len(ext_lines):,} 键  -> xz {len(ext_xz)/1024/1024:.2f} MB")

    with open(os.path.join(OUT_DIR, "report.txt") if not args.dry_run else os.devnull,
              "w", encoding="utf-8") as f:
        if not args.dry_run:
            f.write("\n".join(report))


if __name__ == "__main__":
    main()
