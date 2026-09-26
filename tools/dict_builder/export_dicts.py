#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
把随包与发布物里的字典还原成本项目标准导入格式文本，供人工审核。

为什么需要
----------
APK 内的基础包是二进制索引（pinyin_index.bin.xz），人不可读；审核「常用字 / 生僻字
如何分配」必须回到文本。本脚本是 `build_dict_index.py` 的逆操作（索引 → 文本），
并把单字表拆成「规范表一二级 / 规范表三级 / 表外」三份，便于看出哪些字被过滤
（现行判据 = 一二级 6500 + 三级 1407，只有表外字随「加更多生僻字」开关）。

标准格式（与 `convert_rime_ice.render` 一致，可直接被 `loadPhrasesText` 导入）
    词库    : `拼音<TAB>词1|词2|…`   同键内按词频降序
    单字表  : `音节<TAB>字1,字2,…`   同音节内按字频降序
    常用字表: `#` 开头为注释，其余行的汉字全部计入常用字
    音节表  : 每行一个合法音节
文件一律 UTF-8、LF 换行。

输出（默认 `docs/dict_review/`，docs 不入库）
    词库-基础包.txt            全量（索引还原，604,077 键）
    词库-热词子集.txt          随包高频子集
    词库-长词包.txt            release/dict_ext.txt.xz
    词库-腾讯大词库.txt        release/opt_tencent.txt.xz
    词库-被过滤词条.txt        关闭「加更多生僻字」时被整条丢弃的词
    词库-基础包-分片/*.txt     按拼音首字母切分，便于逐片审阅
    单字表.txt                 完整单字表（含被过滤字）
    单字表-三级字.txt          规范表三级字（现行已默认加载，仅作审阅视图）
    单字表-表外字.txt          非规范表字（繁体 / 异体 / 日韩 / 扩展区）—— 现行不加载
    常用字表.txt               一二级 6500 字（asset 原样导出）
    三级字表.txt               三级 1407 字（asset 原样导出）
    音节表.txt
    说明.md                    格式、行数、体积与统计

用法
    python tools/dict_builder/export_dicts.py
    python tools/dict_builder/export_dicts.py --no-split    # 不切首字母分片
"""

import argparse
import lzma
import os
import struct
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asset_io import read_asset_text  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
RELEASE = os.path.join(ROOT, "release")
RIME_DIR = os.path.join(ROOT, "docs", "rime-ice", "cn_dicts")
OUT_DIR = os.path.join(ROOT, "docs", "dict_review")

INDEX_ASSET = os.path.join(ASSETS, "pinyin_index.bin.xz")
HOT_ASSET = os.path.join(ASSETS, "hot_phrases.txt.xz")
CHARS_ASSET = os.path.join(ASSETS, "pinyin_chars.txt.xz")
COMMON_ASSET = os.path.join(ASSETS, "common_chars.txt.xz")
TIER3_ASSET = os.path.join(ASSETS, "tier3_chars.txt.xz")
SYLLABLE_ASSET = os.path.join(ASSETS, "pinyin_syllables.txt.xz")
TABLE_8105 = os.path.join(RIME_DIR, "8105.dict.yaml")

# 与 PinyinEngine 的判据保持一致
HAN_LO, HAN_HI = 0x4E00, 0x9FFF


def read_index(path):
    """解析二进制索引 v2，返回 [(键, [词, ...]), ...]（键已是字典序）。"""
    raw = lzma.decompress(open(path, "rb").read())
    magic, version, key_count, keys_len, words_len, _reserved, _digest = \
        struct.unpack_from("<4sHIIIIQ", raw, 0)
    if magic != b"JNIH":
        raise SystemExit("索引 magic 不符，不是本项目的词库索引: %r" % magic)
    if version != 2:
        raise SystemExit("索引版本 %d 未经本脚本支持（当前支持 v2）" % version)
    off = 30
    keys_blob = raw[off:off + keys_len]; off += keys_len
    key_lens = raw[off:off + key_count]; off += key_count
    words_blob = raw[off:off + words_len]; off += words_len
    word_lens = struct.unpack_from("<%dH" % key_count, raw, off)

    out = []
    ko = wo = 0
    for i in range(key_count):
        key = keys_blob[ko:ko + key_lens[i]].decode("utf-8"); ko += key_lens[i]
        seg = words_blob[wo:wo + word_lens[i]].decode("utf-8"); wo += word_lens[i]
        out.append((key, seg.split("|") if seg else []))
    return out


def read_plain_text_xz(path):
    """读 xz 文本包（词库格式），返回 [(键, [词, ...]), ...]。

    换行归一为 LF：`release/dict_ext.txt.xz` 这一版的原始内容是 CRLF，
    运行时走 `readLine()` / `lineSequence()` 会自动剥离 `\\r`，导出时同样归一，避免审阅文件里混进控制字符。
    """
    text = lzma.decompress(open(path, "rb").read()).decode("utf-8").replace("\r\n", "\n")
    out = []
    for line in text.split("\n"):
        if "\t" not in line:
            continue
        key, seg = line.split("\t", 1)
        out.append((key, [w for w in seg.split("|") if w]))
    return out


def read_common_chars(path):
    """读常用字表：`#` 注释跳过，其余行的汉字全部计入。"""
    chars = set()
    for line in read_asset_text(path).split("\n"):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        chars.update(s)
    return chars


def read_table_8105(path):
    """读 rime-ice 的 8105.dict.yaml，取《通用规范汉字表》全表（含三级）。"""
    chars = set()
    started = False
    for line in open(path, encoding="utf-8"):
        if not started:
            if line.strip() == "...":
                started = True
            continue
        if not line or line.startswith("#"):
            continue
        cell = line.split("\t")[0]
        if len(cell) == 1:
            chars.add(cell)
    return chars


def read_chars_table(path):
    """读单字表，返回 [(音节, [字, ...]), ...]（保持顺序）。"""
    out = []
    for line in read_asset_text(path).split("\n"):
        if not line or "\t" not in line:
            continue
        syl, seg = line.split("\t", 1)
        out.append((syl, [c for c in seg.split(",") if c]))
    return out


def is_loadable(c, common):
    """现行判据：< 0x4E00 放行；基本区查常用字位图；其余（含扩展区）判生僻。"""
    code = ord(c)
    if code < HAN_LO:
        return True
    if code <= HAN_HI:
        return c in common
    return False


def render_keys(pairs):
    return "\n".join("%s\t%s" % (k, "|".join(ws)) for k, ws in pairs)


def render_chars(pairs):
    return "\n".join("%s\t%s" % (s, ",".join(cs)) for s, cs in pairs)


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    return os.path.getsize(path) / 1024 / 1024


def main():
    ap = argparse.ArgumentParser(description="导出字典文本供人工审核")
    ap.add_argument("--out", default=OUT_DIR, help="输出目录（默认 docs/dict_review）")
    ap.add_argument("--no-split", action="store_true", help="不按拼音首字母切分片")
    args = ap.parse_args()

    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)
    lines = []          # 说明.md 正文
    def say(s=""):
        print(s)
        lines.append(s)

    common = read_common_chars(COMMON_ASSET)
    tier3_chars = read_common_chars(TIER3_ASSET) if os.path.isfile(TIER3_ASSET) else set()
    loadable_chars = common | tier3_chars
    say("# 字典导出（人工审核用）")
    say()
    say("生成：`python tools/dict_builder/export_dicts.py`｜格式：UTF-8 + LF，"
        "词库 `拼音<TAB>词1|词2|…`，单字表 `音节<TAB>字1,字2,…`")
    say()

    # ── 1. 词库 ────────────────────────────────────────
    base = read_index(INDEX_ASSET)
    base_keys = len(base)
    base_words = sum(len(ws) for _, ws in base)
    uniq_words = len({w for _, ws in base for w in ws})
    write(os.path.join(out_dir, "词库-基础包.txt"), render_keys(base))
    say("## 词库")
    say()
    say("| 文件 | 键 | 词条 | 说明 |")
    say("|---|---|---|---|")
    say("| 词库-基础包.txt | %s | %s | 随 APK，去重后 %s 词 |" % (
        f"{base_keys:,}", f"{base_words:,}", f"{uniq_words:,}"))

    if not args.no_split:
        split_dir = os.path.join(out_dir, "词库-基础包-分片")
        os.makedirs(split_dir, exist_ok=True)
        buckets = {}
        for k, ws in base:
            head = k[0] if "a" <= k[0] <= "z" else "其他"
            buckets.setdefault(head, []).append((k, ws))
        for head in sorted(buckets):
            write(os.path.join(split_dir, "%s.txt" % head), render_keys(buckets[head]))
        say("| 词库-基础包-分片/ | — | — | 按拼音首字母切 %d 片，便于逐片审阅 |"
            % len(buckets))

    hot = read_plain_text_xz(HOT_ASSET) if os.path.isfile(HOT_ASSET) else []
    if hot:
        write(os.path.join(out_dir, "词库-热词子集.txt"), render_keys(hot))
        say("| 词库-热词子集.txt | %s | %s | 随包高频子集（冷启动秒级可输入） |" % (
            f"{len(hot):,}", f"{sum(len(w) for _, w in hot):,}"))

    for name, src in (
        ("词库-长词包.txt", os.path.join(RELEASE, "dict_ext.txt.xz")),
        ("词库-腾讯大词库.txt", os.path.join(RELEASE, "opt_tencent.txt.xz")),
    ):
        if not os.path.isfile(src):
            say("| %s | — | — | 缺少 %s，跳过 |" % (name, os.path.relpath(src, ROOT)))
            continue
        pairs = read_plain_text_xz(src)
        write(os.path.join(out_dir, name), render_keys(pairs))
        say("| %s | %s | %s | 下载包（%s） |" % (
            name, f"{len(pairs):,}", f"{sum(len(w) for _, w in pairs):,}",
            os.path.basename(src)))

    # 被过滤词条（关闭「加更多生僻字」时整条丢弃）
    dropped = [(k, [w for w in ws if any(not is_loadable(c, loadable_chars) for c in w)])
               for k, ws in base]
    dropped = [(k, ws) for k, ws in dropped if ws]
    drop_n = sum(len(ws) for _, ws in dropped)
    write(os.path.join(out_dir, "词库-被过滤词条.txt"), render_keys(dropped))
    say("| 词库-被过滤词条.txt | %s | %s | 关闭生僻字时被整条丢弃，占 %.2f%% |" % (
        f"{len(dropped):,}", f"{drop_n:,}", 100.0 * drop_n / base_words))
    say()

    # ── 2. 单字表 ──────────────────────────────────────
    chars = read_chars_table(CHARS_ASSET)
    write(os.path.join(out_dir, "单字表.txt"), render_chars(chars))
    n8105 = read_table_8105(TABLE_8105) if os.path.isfile(TABLE_8105) else set()
    # 规范表三级字：按规范表档位切（不是按「是否被过滤」）——现行已默认加载，这份只作审阅视图
    tier3 = [(s, [c for c in cs if len(c) == 1 and c in n8105 and c not in common])
             for s, cs in chars]
    tier3 = [(s, cs) for s, cs in tier3 if cs]
    outer = [(s, [c for c in cs if len(c) == 1 and not is_loadable(c, loadable_chars)])
             for s, cs in chars]
    outer = [(s, cs) for s, cs in outer if cs]
    write(os.path.join(out_dir, "单字表-三级字.txt"), render_chars(tier3))
    write(os.path.join(out_dir, "单字表-表外字.txt"), render_chars(outer))

    say("## 单字表（现行判据 = 一二级 6500 字 + 三级 %d 字）" % len(tier3_chars))
    say()
    char_items = sum(len(cs) for _, cs in chars)
    say("| 文件 | 音节 | 字条目 | 说明 |")
    say("|---|---|---|---|")
    say("| 单字表.txt | %d | %s | 完整表（含被过滤字） |" % (len(chars), f"{char_items:,}"))
    say("| 单字表-三级字.txt | %d | %s | 规范表三级字（现行已默认加载） |" % (
        len(tier3), f"{sum(len(cs) for _, cs in tier3):,}"))
    say("| 单字表-表外字.txt | %d | %s | 非规范表字（繁体/异体/日韩/扩展区），现行不加载 |" % (
        len(outer), f"{sum(len(cs) for _, cs in outer):,}"))
    say()

    for name, src, note in (
        ("常用字表.txt", COMMON_ASSET, "一二级 %d 字" % len(common)),
        ("三级字表.txt", TIER3_ASSET, "三级 %d 字（与一二级同为默认档）" % len(tier3_chars)),
        ("音节表.txt", SYLLABLE_ASSET, "合法音节全集"),
    ):
        text = read_asset_text(src).rstrip("\n")
        write(os.path.join(out_dir, name), text)
        say("- `%s`：%s" % (name, note))
    say()

    bad = [(s, c) for s, cs in chars for c in cs if len(c) != 1]
    if bad:
        say("已知数据瑕疵：单字表里唯一的非单字条目是 `%s` 行的「%s」，"
            "运行时按长度过滤丢弃（不进候选）。" % (bad[0][0], "」「".join(c for _, c in bad)))
        say()

    write(os.path.join(out_dir, "说明.md"), "\n".join(lines) + "\n")
    print("\n已写入 %s" % os.path.relpath(out_dir, ROOT))


if __name__ == "__main__":
    main()
