#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
把 rime-ice 的 tencent.dict.yaml 转换成可选词库包（本项目格式）。

tencent 词库格式与 base/ext 不同：
    columns: text, weight     -> 每行 `词<TAB>权重`
    **没有拼音列**，需要自动注音。

注音策略（按可靠性排序）：
  1. 查 base/ext 词库 —— 若该词已收录，直接用它的拼音（人工校对过，最准）
  2. 逐字查 8105 字表 —— 该表带字频权重，多音字取权重最高的读音
     （如「的」de 权重远高于 di，取 de）
  3. 若某字查不到，或命中多个读音且权重接近（比值 < 阈值）无法判断 -> 丢弃该词

输出：release/opt_tencent.txt.xz（可选包，供下载）
      tools/dict_builder/out/tencent/report.txt

用法：
    python tools/dict_builder/convert_rime_tencent.py --dry-run
    python tools/dict_builder/convert_rime_tencent.py
"""
import argparse
import lzma
import os
import re
import sys
from collections import defaultdict

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RIME = os.path.join(ROOT, "docs", "rime-ice", "cn_dicts")
OUT_DIR = os.path.join(ROOT, "tools", "dict_builder", "out", "tencent")
OUT_XZ = os.path.join(ROOT, "release", "opt_tencent.txt.xz")
# 注：基础包的文本资产在「词库改二进制索引」（P1 Stage 1）后已移出 APK，
# 本脚本不再需要它（这个常量本来就没被使用，是历史遗留的死代码）。

XZ_FILTERS = [{"id": lzma.FILTER_LZMA2, "preset": 7, "lc": 4, "pb": 0}]

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asset_io import read_asset_text  # noqa: E402

SYLLABLES = set(read_asset_text(os.path.join(ROOT, "app", "src", "main", "assets",
                                             "pinyin_syllables.txt.xz")).split())
MAX_SYL = max(len(s) for s in SYLLABLES)

# 多音字判定：最高权重 / 次高权重低于该比值时认为难以判断
AMBIGUOUS_RATIO = 5.0

WORD_OK = re.compile(r"^[\u4e00-\u9fff·]+$")
MAX_WORD_LEN = 8


def parse_yaml_dict(path, has_pinyin):
    """解析 rime dict.yaml。has_pinyin=True 时取 `词\t拼音\t权重`，否则 `词\t权重`。"""
    out = []
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
            if len(parts) < 2 or not parts[0].strip():
                continue
            word = parts[0].strip()
            if has_pinyin:
                pinyin = parts[1].strip()
                if not re.fullmatch(r"[a-z]+(?: [a-z]+)*", pinyin):
                    continue
                weight = int(parts[2]) if len(parts) >= 3 and parts[2].strip().isdigit() else 0
                out.append((word, pinyin.replace(" ", ""), weight))
            else:
                weight = int(parts[1]) if parts[1].strip().isdigit() else 0
                out.append((word, weight))
    return out


def can_segment(key):
    n = len(key)
    reach = [False] * (n + 1)
    reach[0] = True
    for i in range(n):
        if not reach[i]:
            continue
        for L in range(1, MAX_SYL + 1):
            if i + L <= n and key[i:i + L] in SYLLABLES:
                reach[i + L] = True
    return reach[n]


def main():
    ap = argparse.ArgumentParser(description="转换 rime-ice tencent 词库为可选包")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    report = []
    def say(s=""):
        print(s)
        report.append(s)

    say("=" * 70)
    say("rime-ice tencent 词库转换（可选包）")
    say("=" * 70)
    say()

    # ── 1. 建 字 → [(拼音, 权重)]（来自 8105 字表）────────────
    char_readings = defaultdict(list)
    for word, pinyin, weight in parse_yaml_dict(os.path.join(RIME, "8105.dict.yaml"), True):
        if len(word) == 1:
            char_readings[word].append((pinyin, weight))
    say(f"8105 字表: {len(char_readings):,} 个单字，"
        f"其中多音字 {sum(1 for v in char_readings.values() if len(v) > 1):,} 个")

    # ── 2. 建 词 → 拼音（来自 base + ext，最准）──────────────
    word_pinyin = {}
    for name in ("base", "ext"):
        p = os.path.join(RIME, f"{name}.dict.yaml")
        if not os.path.isfile(p):
            continue
        for word, pinyin, _ in parse_yaml_dict(p, True):
            word_pinyin.setdefault(word, pinyin)
    say(f"base+ext 可反查词: {len(word_pinyin):,}")
    say()

    # ── 3. 解析 tencent ──────────────────────────────────────
    entries = parse_yaml_dict(os.path.join(RIME, "tencent.dict.yaml"), False)
    say(f"tencent 原始词条: {len(entries):,}")
    say()

    # ── 4. 注音 ──────────────────────────────────────────────
    by_key = defaultdict(dict)
    stat = defaultdict(int)
    samples = defaultdict(list)

    for word, weight in entries:
        if not WORD_OK.match(word) or len(word) > MAX_WORD_LEN:
            stat["非纯中文或超长"] += 1
            if len(samples["非纯中文或超长"]) < 8:
                samples["非纯中文或超长"].append(word)
            continue

        # 策略 1：base/ext 反查
        key = word_pinyin.get(word)
        if key:
            stat["base/ext 反查命中"] += 1

        # 策略 2：逐字注音（多音字取最高权重读音）
        if not key:
            parts = []
            ok = True
            for ch in word:
                readings = char_readings.get(ch)
                if not readings:
                    ok = False
                    stat["缺字"] += 1
                    if len(samples["缺字"]) < 8:
                        samples["缺字"].append(f"{word}({ch})")
                    break
                readings = sorted(readings, key=lambda x: -x[1])
                if len(readings) > 1:
                    top, second = readings[0][1], readings[1][1]
                    ratio = top / second if second > 0 else 999
                    if ratio < AMBIGUOUS_RATIO:
                        ok = False
                        stat["多音字难判"] += 1
                        if len(samples["多音字难判"]) < 8:
                            samples["多音字难判"].append(
                                f"{word}({ch}:{'/'.join(p for p,_ in readings[:3])})")
                        break
                parts.append(readings[0][0])
            if not ok:
                continue
            key = "".join(parts)
            stat["逐字注音"] += 1

        # 校验切分
        if not can_segment(key):
            stat["切分失败"] += 1
            if len(samples["切分失败"]) < 8:
                samples["切分失败"].append(f"{word}/{key}")
            continue

        prev = by_key[key].get(word)
        if prev is None or weight > prev:
            by_key[key][word] = weight

    keys = len(by_key)
    words = sum(len(v) for v in by_key.values())
    say("=== 注音统计 ===")
    for k in ("base/ext 反查命中", "逐字注音", "缺字", "多音字难判", "切分失败", "非纯中文或超长"):
        if stat[k]:
            s = samples.get(k, [])
            say(f"  {k:16} {stat[k]:>9,}   {('例: ' + '、'.join(s[:5])) if s else ''}")
    say()
    say(f"有效拼音键 : {keys:,}")
    say(f"有效词条   : {words:,}")
    say()

    if not args.dry_run:
        text = "\n".join(f"{k}\t{'|'.join(w for w, _ in sorted(by_key[k].items(), key=lambda x: -x[1]))}"
                         for k in sorted(by_key))
        out = lzma.compress(text.encode("utf-8"), filters=XZ_FILTERS)
        os.makedirs(os.path.dirname(OUT_XZ), exist_ok=True)
        open(OUT_XZ, "wb").write(out)
        say(f"已写入 {OUT_XZ}")
        say(f"  文本 {len(text.encode())/1024/1024:.2f} MB -> xz {len(out)/1024/1024:.2f} MB")
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, "report.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(report))
    else:
        say("[dry-run] 未写入文件")

    if not args.dry_run:
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, "report.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(report))


if __name__ == "__main__":
    main()
