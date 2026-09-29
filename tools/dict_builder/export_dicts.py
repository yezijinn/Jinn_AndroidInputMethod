#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
把随包与发布物里的字典还原成本项目标准导入格式文本，供人工审核。

为什么需要
----------
APK 内的基础包是二进制索引（pinyin_index.bin.xz），人不可读；审核「常用字 / 生僻字
如何分配」必须回到文本。本脚本是 `build_dict_index.py` 的逆操作（索引 → 文本），
单字表按**档位**拆成档 1 / 档 2 / 档 3 / 表外四份，并导出简繁映射（字级 + 词级）——
档 2 / 档 3 由设置页「加更多生僻字」页的两个开关放行，表外字始终不加载。

标准格式（与 `convert_rime_ice.render` 一致，可直接被 `loadPhrasesText` 导入）
    词库    : `拼音<TAB>词1|词2|…`   同键内按词频降序
    单字表  : `音节<TAB>字1,字2,…`   同音节内按字频降序
    常用字表: `#` 开头为注释，其余行的汉字全部计入常用字
    音节表  : 每行一个合法音节
文件一律 UTF-8、LF 换行。

输出（默认 `docs/dict_review/`，docs 不入库）—— **2026-09-27 三档方案**
    词库-第1部分.txt           随 APK 的短语索引（索引还原）
    词库-第2/3/4部分.txt       三个下载包（release/dict_part*.txt.xz）
    词库-默认被过滤词条.txt    档 2 / 档 3 都关着时被整条丢弃的词
    词库-开档2后被过滤词条.txt 只开档 2 时仍被丢弃的词（含档 3 字）
    词库-第1部分-分片/*.txt    按拼音首字母切分，便于逐片审阅
    单字表.txt                 三档并集（完整表）
    单字表-档1字.txt           默认档（5,613 字）
    单字表-档2字.txt           可选档 2（837 字）
    单字表-档3字.txt           可选档 3（2,923 字，依赖档 2）
    单字表-表外字.txt          三档之外的字（异体 / 日韩 / 扩展区等，不加载）
    档1字表.txt / 档2字表.txt / 档3字表.txt   三张档位表（asset 原样导出）
    简繁对照-字级.txt          逐字映射（2,714 对，OpenCC STCharacters + 人工覆盖）
    简繁对照-词级.txt          词级消歧 + 往返一致补收（9,139 条，整词优先于逐字）
    音节表.txt
    说明.md                    格式、行数、体积与统计

用法
    python tools/dict_builder/export_dicts.py
    python tools/dict_builder/export_dicts.py --no-split    # 不切首字母分片
"""

import argparse
import lzma
import os
import shutil
import struct
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asset_io import read_asset_text  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
RELEASE = os.path.join(ROOT, "release")
OUT_DIR = os.path.join(ROOT, "docs", "dict_review")

INDEX_ASSET = os.path.join(ASSETS, "pinyin_index.bin.xz")
CHARS_ASSET = os.path.join(ASSETS, "pinyin_chars.txt.xz")
COMMON_ASSET = os.path.join(ASSETS, "common_chars.txt.xz")
TIER2_ASSET = os.path.join(ASSETS, "tier2_chars.txt.xz")
TIER3_ASSET = os.path.join(ASSETS, "tier3_chars.txt.xz")
SIMP_TRAD_ASSET = os.path.join(ASSETS, "simp_trad.txt.xz")
SIMP_TRAD_WORDS_ASSET = os.path.join(ASSETS, "simp_trad_words.txt.xz")
SYLLABLE_ASSET = os.path.join(ASSETS, "pinyin_syllables.txt.xz")
# 「补充短语词库」页的三个下载包（第 2/3/4 部分）
PART_XZ = {n: os.path.join(RELEASE, "dict_part%d.txt.xz" % n) for n in (2, 3, 4)}

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


MANIFEST = ".export_dicts_manifest.txt"
PRODUCED = []          # 本轮写出的相对路径（含子目录），供下次运行精确清理
PRODUCED_ROOT = "."


def record_produced(path):
    """记下本轮产物的相对路径（文件或自己建的目录），供 manifest 精确清理。

    ⚠ 名字别叫 `note`：`main()` 里 `note` 是**循环变量**（档位说明文字），会把它变成局部名，
    `note(split_dir)` 直接 `UnboundLocalError`（实跑踩到过）。
    """
    PRODUCED.append(os.path.relpath(path, PRODUCED_ROOT))


def write(path, text):
    # 原子落盘（BUG.md L-122）：这条 write() 写的就是进 APK 的资产，半截文件会让运行期解压失败
    write_bytes_atomically(path, text.encode("utf-8"))
    record_produced(path)
    return os.path.getsize(path) / 1024 / 1024


def cleanup_previous(out_dir, force):
    """清掉上一轮的产物 —— **只删 manifest 记过的**，不再无条件清目录。

    原实现无条件删掉目录下所有 `*.txt`（外加 `*分片` 子目录），前提是「本目录专属于本脚本」：
    误指到 `docs/` 这类目录就会删掉手写资料（BUG.md L-49）。现在：
      - 有 manifest ⇒ 按清单逐项删（文件 / 目录），别的文件一律不碰；
      - 没 manifest 且目录非空 ⇒ **中止**，提示换空目录或确认后加 `--force`；
      - `--force` ⇒ 维持旧的「清空重写」语义（显式选择才生效）。
    """
    mf = os.path.join(out_dir, MANIFEST)
    if os.path.isfile(mf):
        for rel in open(mf, encoding="utf-8").read().split("\n"):
            rel = rel.strip()
            if not rel:
                continue
            p = os.path.join(out_dir, rel)
            if os.path.isdir(p):
                shutil.rmtree(p, ignore_errors=True)
            elif os.path.isfile(p):
                os.remove(p)
        os.remove(mf)
        return
    existing = sorted(n for n in os.listdir(out_dir) if not n.startswith("."))
    if not existing:
        return
    if not force:
        raise SystemExit(
            "输出目录 %s 非空，且不是本脚本的产物目录（缺少 %s）：\n  %s\n"
            "换个空目录，或确认可以清空后加 --force。" % (out_dir, MANIFEST, existing[:10]))
    for n in existing:
        p = os.path.join(out_dir, n)
        if os.path.isdir(p):
            shutil.rmtree(p, ignore_errors=True)
        else:
            os.remove(p)


def main():
    ap = argparse.ArgumentParser(description="导出字典文本供人工审核")
    ap.add_argument("--out", default=OUT_DIR, help="输出目录（默认 docs/dict_review）")
    ap.add_argument("--no-split", action="store_true", help="不按拼音首字母切分片")
    ap.add_argument("--force", action="store_true",
                    help="目标目录非本脚本产物时也清空重写（危险，默认拒绝）")
    args = ap.parse_args()

    global PRODUCED_ROOT
    out_dir = args.out
    os.makedirs(out_dir, exist_ok=True)
    PRODUCED_ROOT = out_dir
    # 先清掉上一次的产物：导出视图会随方案演进（如 2026-09-27 的三档改造），旧文件留着会与新文件
    # 混在一起，审核者分不清哪份是现行的 —— 按上一轮的 manifest 精确清理（见 cleanup_previous）。
    cleanup_previous(out_dir, args.force)
    lines = []          # 说明.md 正文
    def say(s=""):
        print(s)
        lines.append(s)

    # 三档字表（默认档 1；档 2 / 档 3 由「加更多生僻字」页的两个开关放行）
    d1 = read_common_chars(COMMON_ASSET)
    d2 = read_common_chars(TIER2_ASSET) if os.path.isfile(TIER2_ASSET) else set()
    d3 = read_common_chars(TIER3_ASSET) if os.path.isfile(TIER3_ASSET) else set()
    say("# 字典导出（人工审核用）")
    say()
    say("生成：`python tools/dict_builder/export_dicts.py`｜格式：UTF-8 + LF，"
        "词库 `拼音<TAB>词1|词2|…`，单字表 `音节<TAB>字1,字2,…`")
    say()
    say("档位：档 1（默认，%d 字）→ 档 2（%d 字）→ 档 3（%d 字，依赖档 2），候选按当前开关放行"
        % (len(d1), len(d2), len(d3)))
    say()

    # ── 1. 词库 ────────────────────────────────────────
    base = read_index(INDEX_ASSET)
    base_keys = len(base)
    base_words = sum(len(ws) for _, ws in base)
    uniq_words = len({w for _, ws in base for w in ws})
    write(os.path.join(out_dir, "词库-第1部分.txt"), render_keys(base))
    say("## 词库")
    say()
    say("| 文件 | 键 | 词条 | 说明 |")
    say("|---|---|---|---|")
    say("| 词库-第1部分.txt | %s | %s | 随 APK（内置索引），去重后 %s 词 |" % (
        f"{base_keys:,}", f"{base_words:,}", f"{uniq_words:,}"))

    if not args.no_split:
        split_dir = os.path.join(out_dir, "词库-第1部分-分片")
        os.makedirs(split_dir, exist_ok=True)
        record_produced(split_dir)   # 记目录本身：下次清理时整目录删掉，不留空壳
        buckets = {}
        for k, ws in base:
            head = k[0] if "a" <= k[0] <= "z" else "其他"
            buckets.setdefault(head, []).append((k, ws))
        for head in sorted(buckets):
            write(os.path.join(split_dir, "%s.txt" % head), render_keys(buckets[head]))
        say("| 词库-第1部分-分片/ | — | — | 按拼音首字母切 %d 片，便于逐片审阅 |"
            % len(buckets))

    for part, src in PART_XZ.items():
        name = "词库-第%d部分.txt" % part
        if not os.path.isfile(src):
            say("| %s | — | — | 缺少 %s，跳过 |" % (name, os.path.relpath(src, ROOT)))
            continue
        pairs = read_plain_text_xz(src)
        write(os.path.join(out_dir, name), render_keys(pairs))
        say("| %s | %s | %s | 下载包「%d级词库+%s万」（%s） |" % (
            name, f"{len(pairs):,}", f"{sum(len(w) for _, w in pairs):,}",
            part, {2: "40", 3: "50", 4: "60"}[part], os.path.basename(src)))

    # 被过滤词条：两级口径 —— 默认（档 2 / 档 3 都关）与只开档 2
    def dropped_by(allow):
        rows = [(k, [w for w in ws if any(not is_loadable(c, allow) for c in w)]) for k, ws in base]
        return [(k, ws) for k, ws in rows if ws]

    for name, allow, note in (
        ("词库-默认被过滤词条.txt", d1, "档 2 / 档 3 都关着时被整条丢弃"),
        ("词库-开档2后被过滤词条.txt", d1 | d2, "只开档 2 时仍被丢弃（含档 3 字）"),
    ):
        rows = dropped_by(allow)
        n = sum(len(ws) for _, ws in rows)
        write(os.path.join(out_dir, name), render_keys(rows))
        say("| %s | %s | %s | %s，占 %.2f%% |" % (
            name, f"{len(rows):,}", f"{n:,}", note, 100.0 * n / base_words))
    say()

    # ── 2. 单字表（三档并集 + 按档拆分视图）──────────────
    chars = read_chars_table(CHARS_ASSET)
    write(os.path.join(out_dir, "单字表.txt"), render_chars(chars))

    def by_tier(allow):
        rows = [(s, [c for c in cs if c in allow]) for s, cs in chars]
        return [(s, cs) for s, cs in rows if cs]

    tiers = [(1, d1), (2, d2), (3, d3)]
    rows_by_tier = {}
    for i, allow in tiers:
        rows = by_tier(allow)
        rows_by_tier[i] = rows
        write(os.path.join(out_dir, "单字表-档%d字.txt" % i), render_chars(rows))

    covered = d1 | d2 | d3
    outer = [(s, [c for c in cs if c not in covered]) for s, cs in chars]
    outer = [(s, cs) for s, cs in outer if cs]
    write(os.path.join(out_dir, "单字表-表外字.txt"), render_chars(outer))

    say("## 单字表")
    say()
    char_items = sum(len(cs) for _, cs in chars)
    say("| 文件 | 音节 | 字条目 | 说明 |")
    say("|---|---|---|---|")
    say("| 单字表.txt | %d | %s | 三档并集（完整表，含不加载的表外字） |" % (
        len(chars), f"{char_items:,}"))
    for i, _ in tiers:
        note = {1: "默认档", 2: "可选档 2", 3: "可选档 3（依赖档 2）"}[i]
        rows = rows_by_tier[i]
        say("| 单字表-档%d字.txt | %d | %s | %s |" % (
            i, len(rows), f"{sum(len(cs) for _, cs in rows):,}", note))
    say("| 单字表-表外字.txt | %d | %s | 三档之外（异体 / 日韩 / 扩展区等），不加载 |" % (
        len(outer), f"{sum(len(cs) for _, cs in outer):,}"))
    say()

    for name, src, note in (
        ("档1字表.txt", COMMON_ASSET, "默认档 %d 字" % len(d1)),
        ("档2字表.txt", TIER2_ASSET, "可选档 2：%d 字" % len(d2)),
        ("档3字表.txt", TIER3_ASSET, "可选档 3：%d 字" % len(d3)),
        ("音节表.txt", SYLLABLE_ASSET, "合法音节全集"),
    ):
        text = read_asset_text(src).rstrip("\n")
        write(os.path.join(out_dir, name), text)
        say("- `%s`：%s" % (name, note))
    say()

    # ── 3. 简繁映射（「只使用繁体字」）────────────────────
    simp_trad = read_asset_text(SIMP_TRAD_ASSET).rstrip("\n")
    write(os.path.join(out_dir, "简繁对照-字级.txt"), simp_trad)
    n_pair = sum(1 for l in simp_trad.split("\n") if l and not l.startswith("#"))
    words_text = read_asset_text(SIMP_TRAD_WORDS_ASSET).rstrip("\n") \
        if os.path.isfile(SIMP_TRAD_WORDS_ASSET) else ""
    write(os.path.join(out_dir, "简繁对照-词级.txt"), words_text)
    n_word = sum(1 for l in words_text.split("\n") if l and not l.startswith("#"))
    say("## 简繁映射（「只使用繁体字」）")
    say()
    say("- `简繁对照-字级.txt`：逐字映射 **%s** 对（`简体<TAB>繁体`）" % f"{n_pair:,}")
    say("- `简繁对照-词级.txt`：词级消歧 **%s** 条（整词优先于逐字，如 头发→頭髮 而非 頭發）"
        % f"{n_word:,}")
    say()

    bad = [(s, c) for s, cs in chars for c in cs if len(c) != 1]
    if bad:
        say("已知数据瑕疵：单字表里唯一的非单字条目是 `%s` 行的「%s」，"
            "运行时按长度过滤丢弃（不进候选）。" % (bad[0][0], "」「".join(c for _, c in bad)))
        say()

    write(os.path.join(out_dir, "说明.md"), "\n".join(lines) + "\n")
    # 落 manifest（最后一步）：下次运行按它精确清理本目录里**本脚本写出**的东西；
    # 没走完全程就中断时不会留下 manifest ⇒ 下次会因「目录非空且非本脚本产物」而拒绝动手（保守）
    with open(os.path.join(out_dir, MANIFEST), "w", encoding="utf-8", newline="\n") as mf:
        mf.write("\n".join(PRODUCED) + "\n")
    print("\n已写入 %s（%d 项）" % (os.path.relpath(out_dir, ROOT), len(PRODUCED)))


if __name__ == "__main__":
    main()
