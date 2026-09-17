#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
构建词库二进制索引（P1 Stage 1）。

为什么需要
----------
现状是「xz 文本 + HashMap」：每次进程启动都要解压 4.3MB → 10MB 文本、逐行解析、建 60 万级哈希表
（真机 6~10.7s，内存 290MB）。索引把这些一次性成本挪到**构建期**：运行时只需解压索引 +
顺序读入几个数组，查询用二分查找，不再解析、不再建哈希表。

格式（小端，**v2 = 长度数组版**）
--------------------------------
    magic      : "JNIH"（4B）
    version    : u16 = 2
    keyCount   : u32
    keysLen    : u32        # keysBlob 字节数
    wordsLen   : u32        # wordsBlob 字节数
    reserved   : u32
    srcDigest  : u64        # 源文本摘要（APK 索引）／源文件 length:mtime（设备端索引）
    keysBlob   : UTF-8，全部键按字典序串联
    keyLengths : u8  × keyCount        # 每键字节数（键都是拼音，≤255）
    wordsBlob  : UTF-8，每个键的词表（`词1|词2|...`，顺序即词频序）
    wordLengths: u16 × keyCount        # 每键词表字节数（≤65535）

> v1 用的是 u32 偏移表（keyCount+1 条 × 2 张表 = 4.83MB）；改为长度数组后只需 0.60MB + 1.21MB，
> **原始体积 17.3MB → 13.6MB（−3.7MB）**，xz 后与解压耗时同步下降。
> 读取端在内存里把长度数组还原成前缀和（IntArray），运行时结构与 v1 一致。

词表顺序与 `build_dict` 完全一致（词频降序、同频保持源序）。
「索引 vs 文本逐键等价」由 JVM 测试守住：`IndexParityTest`（拿 `--fixture` 产出的一对同源
文本/索引逐条对拍）与 `PhraseDictIntegrityTest`（查 APK 内的真实索引）。本脚本不再自带比对
——那份文本资产已不进 APK。

用法
----
    python tools/dict_builder/build_dict_index.py            # 生成 assets/pinyin_index.bin.xz
    python tools/dict_builder/build_dict_index.py --fixture  # 额外产出测试用小型 fixture 对
"""

import argparse
import io
import lzma
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from convert_rime_ice import BASE_MAX_LEN, XZ_FILTERS, build_dict, parse_rime_dict, render  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC_DIR = os.path.join(ROOT, "docs", "rime-ice", "cn_dicts")
OUT_XZ = os.path.join(ROOT, "app", "src", "main", "assets", "pinyin_index.bin.xz")
OUT_TXT = os.path.join(ROOT, "tools", "dict_builder", "out", "rime_ice", "pinyin_phrases.txt")
FIX_DIR = os.path.join(ROOT, "app", "src", "test", "resources")

MAGIC = b"JNIH"
VERSION = 2


def fnv1a64(data: bytes) -> int:
    h = 0xCBF29CE484222325
    for b in data:
        h = ((h ^ b) * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return h


def build_index_bytes(text: str) -> bytes:
    """把「键<TAB>词1|词2」文本转成索引字节。"""
    pairs = []
    for line in text.split("\n"):
        if not line:
            continue
        tab = line.find("\t")
        if tab <= 0:
            continue
        pairs.append((line[:tab], line[tab + 1:]))
    pairs.sort(key=lambda p: p[0])                 # 按键字典序，供二分查找
    keys = [k.encode("utf-8") for k, _ in pairs]
    words = [w.encode("utf-8") for _, w in pairs]

    keys_blob = b"".join(keys)
    words_blob = b"".join(words)

    # 边界自检：长度数组用 u8/u16，超界必须立刻失败（否则读取端会算出错误的切片）
    for raw in keys:
        if len(raw) > 255:
            raise SystemExit(f"键过长（>255B），长度数组无法表示: {raw[:32]!r}")
    too_long = [w for w in words if len(w) > 65535]
    if too_long:
        raise SystemExit(f"词表过长（>64KB），长度数组无法表示: {too_long[0][:32]!r}")

    key_len = bytes(len(k) for k in keys)
    word_len = b"".join(struct.pack("<H", len(w)) for w in words)

    digest = fnv1a64(text.encode("utf-8"))
    head = struct.pack("<4sHIIIIQ", MAGIC, VERSION, len(pairs),
                       len(keys_blob), len(words_blob), 0, digest)
    return head + keys_blob + key_len + words_blob + word_len


def collect_text() -> str:
    """与全量文本资产完全一致的内容：base + ext，按词长拆分后取基础包。"""
    by_key = build_dict(parse_rime_dict(os.path.join(SRC_DIR, "base.dict.yaml"))
                        + parse_rime_dict(os.path.join(SRC_DIR, "ext.dict.yaml")))
    # 基础包 = 词长 ≤4（与 convert_rime_ice.py 的切分一致）
    base = {k: [(w, f) for w, f in v if len(w) <= BASE_MAX_LEN] for k, v in by_key.items()}
    base = {k: v for k, v in base.items() if v}
    return render(base)


def main():
    ap = argparse.ArgumentParser(description="构建词库二进制索引")
    ap.add_argument("--fixture", action="store_true", help="额外产出测试用小型 fixture 对")
    args = ap.parse_args()

    text = collect_text()
    raw = build_index_bytes(text)
    xz = lzma.compress(raw, filters=XZ_FILTERS)
    keys = text.count("\n") + 1
    print(f"键数: {keys}")
    print(f"索引：原始 {len(raw) / 1024 / 1024:.1f} MB → xz {len(xz) / 1024 / 1024:.2f} MB")

    os.makedirs(os.path.dirname(OUT_TXT), exist_ok=True)
    io.open(OUT_TXT, "w", encoding="utf-8", newline="\n").write(text)     # 文本留档（不入库、不进 APK）
    io.open(OUT_XZ, "wb").write(xz)
    print(f"已写入 {OUT_XZ}")
    print(f"文本留档 {OUT_TXT}（供比对/测试，不进 APK）")

    if args.fixture:
        os.makedirs(FIX_DIR, exist_ok=True)
        # 取一批键做小型 fixture：均匀抽样 + **为抽样键补上「以它开头的更长键」**。
        # 原因：fixture 里若没有「某个键是另一个键的前缀」这种组合，智能预测的对拍会退化成
        # 「两边都为空」——测试看着通过却毫无意义（真实词库里这种组合极常见：ni → nihao/nihaoma）。
        by_key = {}
        for line in text.split("\n"):
            tab = line.find("\t")
            if tab > 0:
                by_key[line[:tab]] = line
        ordered_keys = list(by_key)
        step = max(1, len(ordered_keys) // 300)
        picked_keys = ordered_keys[::step][:300]
        for base in list(picked_keys[:30]):                 # 给前 30 个抽样键补上它的「扩展键」
            for ext in ordered_keys:
                if len(ext) > len(base) and ext.startswith(base):
                    picked_keys.append(ext)
                    if len(picked_keys) > 900:
                        break
            if len(picked_keys) > 900:
                break
        # **必须按 key 排序输出**：设备端构建器是单趟流式实现（靠键序做二分查找，乱序即报错），
        # 而索引内部的键区总是有序的 —— 文本 fixture 若乱序，两端就会看到不同顺序，对拍失去意义。
        picked = [by_key[k] for k in sorted(dict.fromkeys(picked_keys))]
        fix_text = "\n".join(picked)
        io.open(os.path.join(FIX_DIR, "dict_index_fixture.txt"), "w",
                encoding="utf-8", newline="\n").write(fix_text)
        io.open(os.path.join(FIX_DIR, "dict_index_fixture.bin.xz"), "wb").write(
            lzma.compress(build_index_bytes(fix_text), filters=XZ_FILTERS))
        print(f"已写入 fixture（{len(picked)} 键）到 {FIX_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
