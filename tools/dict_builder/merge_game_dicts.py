#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
清洗游戏词库并合并进扩展包。

输入：docs/games_ime_dict.txt / docs/klbq_ime_dict.txt
      格式 `词 拼音(空格分隔无调)`（与 rime-ice 同格式）
      注意：docs/ 是只读目录，本脚本只读取，不改动源文件。

输出：release/dict_ext.txt.xz（在 rime-ice 扩展包基础上追加游戏词）
      tools/dict_builder/out/game_merge/report.txt（清洗报告）

清洗规则（均可判定，逐条可复核）：
  1. 删「含数字或英文字母」的词 —— 拼音引擎按合法音节切分，字母/数字切不开，
     这类条目当键永远打不出来（与网络词 yyds 同理），是纯废条目
  2. 删「超长词」（> MAX_WORD_LEN 字）—— 多半是句子或描述性文本
  3. 删「含标点（括号/逗号/斜杠等）」的词
  4. 保留：纯中文，允许间隔号 ·（中文译名常见，如「奥黛丽·格罗夫」）

用法：
    python tools/dict_builder/merge_game_dicts.py --dry-run   # 只看报告
    python tools/dict_builder/merge_game_dicts.py             # 实际合并并压缩
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
DOCS = os.path.join(ROOT, "docs")
OUT_DIR = os.path.join(ROOT, "tools", "dict_builder", "out", "game_merge")
EXT_XZ = os.path.join(ROOT, "release", "dict_ext.txt.xz")
BASE_XZ = os.path.join(ROOT, "app", "src", "main", "assets", "pinyin_phrases.txt.xz")

XZ_FILTERS = [{"id": lzma.FILTER_LZMA2, "preset": 7, "lc": 4, "pb": 0}]

MAX_WORD_LEN = 8
# 允许的中文词字符集：汉字 + 间隔号（中文译名用）
WORD_OK = re.compile(r"^[\u4e00-\u9fff·]+$")
PINYIN_OK = re.compile(r"^[a-z]+(?: [a-z]+)*$")


def is_junk(word):
    """返回 (是否垃圾, 原因)。规则见文件头注释。"""
    if re.search(r"[0-9A-Za-z]", word):
        return True, "含数字或字母"
    if len(word) > MAX_WORD_LEN:
        return True, f"超长(>{MAX_WORD_LEN}字)"
    if not WORD_OK.match(word):
        return True, "含标点或其他字符"
    return False, ""


def parse_game_dict(path):
    """解析 `词 拼音` 格式，返回 [(词, 键)]，同时统计垃圾词。"""
    kept, junk = [], defaultdict(list)
    if not os.path.isfile(path):
        return kept, junk, 0
    total = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            total += 1
            parts = line.split()
            if len(parts) < 2:
                junk["拼音缺失"].append(line[:40])
                continue
            word = parts[0]
            pinyin = " ".join(parts[1:])
            bad, why = is_junk(word)
            if bad:
                junk[why].append(word)
                continue
            if not PINYIN_OK.match(pinyin):
                junk["拼音非纯字母"].append(f"{word} / {pinyin}")
                continue
            kept.append((word, pinyin.replace(" ", "")))
    return kept, junk, total


def load_ext_dict(path):
    """读取现有扩展包，返回 {键: [词...]}（保持原有顺序）。"""
    if not os.path.isfile(path):
        return {}
    raw = lzma.decompress(open(path, "rb").read()).decode("utf-8")
    d = {}
    for line in raw.split("\n"):
        if "\t" not in line:
            continue
        k, v = line.split("\t", 1)
        d[k] = [w for w in v.split("|") if w]
    return d


def load_base_words(path):
    """读取基础包的全部词，用于合并时排除——避免同一词在两包重复导致重复候选。"""
    if not os.path.isfile(path):
        return set()
    raw = lzma.decompress(open(path, "rb").read()).decode("utf-8")
    words = set()
    for line in raw.split("\n"):
        if "\t" not in line:
            continue
        words.update(w for w in line.split("\t", 1)[1].split("|") if w)
    return words


def main():
    ap = argparse.ArgumentParser(description="清洗游戏词库并合并进扩展包")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    report = []
    def say(s=""):
        print(s)
        report.append(s)

    say("=" * 66)
    say("游戏词库清洗与合并报告")
    say("=" * 66)
    say()

    all_kept = []
    for name in ("games_ime_dict.txt", "klbq_ime_dict.txt"):
        path = os.path.join(DOCS, name)
        kept, junk, total = parse_game_dict(path)
        say(f"=== {name} ===")
        say(f"  原始 {total} 条  ->  保留 {len(kept)} 条  （删除 {total - len(kept)} 条）")
        for why, items in sorted(junk.items(), key=lambda x: -len(x[1])):
            say(f"    ✗ {why:16} {len(items):>4} 条   例: {'、'.join(items[:5])}")
        all_kept += kept
        say()

    say(f"两库合计保留: {len(all_kept)} 条")

    # 按拼音聚合（同键多词）
    by_key = defaultdict(list)
    for word, key in all_kept:
        if word not in by_key[key]:
            by_key[key].append(word)
    say(f"聚合后拼音键: {len(by_key)} 个")
    say()

    # 与现有扩展包合并
    ext = load_ext_dict(EXT_XZ)
    base_words = load_base_words(BASE_XZ)
    say(f"现有扩展包: {len(ext)} 键")
    say(f"基础包已有词: {len(base_words):,} 个（合并时排除，避免重复候选）")
    skipped = 0
    added_keys = 0
    added_words = 0
    for key, words in by_key.items():
        # 排除基础包已收录的词：否则 base + ext 合并后候选栏会出现两个相同的词
        fresh = [w for w in words if w not in base_words]
        skipped += len(words) - len(fresh)
        if not fresh:
            continue
        if key in ext:
            for w in fresh:
                if w not in ext[key]:
                    ext[key].append(w)
                    added_words += 1
        else:
            ext[key] = fresh
            added_keys += 1
            added_words += len(fresh)
    say(f"  因基础包已有而跳过: {skipped} 条")
    say(f"合并后: {len(ext)} 键（新增键 {added_keys} 个，追加词 {added_words} 条）")
    total_words = sum(len(v) for v in ext.values())
    say(f"扩展包词条总数: {total_words:,}")
    say()

    if args.dry_run:
        say("[dry-run] 未写入任何文件")
    else:
        text = "\n".join(f"{k}\t{'|'.join(ext[k])}" for k in sorted(ext))
        out = lzma.compress(text.encode("utf-8"), filters=XZ_FILTERS)
        old_size = os.path.getsize(EXT_XZ) if os.path.isfile(EXT_XZ) else 0
        os.makedirs(OUT_DIR, exist_ok=True)
        os.makedirs(os.path.dirname(EXT_XZ), exist_ok=True)
        open(EXT_XZ, "wb").write(out)
        say(f"已写入 {EXT_XZ}")
        say(f"  {old_size/1024/1024:.2f} MB -> {len(out)/1024/1024:.2f} MB")
        with open(os.path.join(OUT_DIR, "report.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(report))

    if not args.dry_run:
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, "report.txt"), "w", encoding="utf-8") as f:
            f.write("\n".join(report))


if __name__ == "__main__":
    main()
