#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
生成「单音节候选混合序」资产 app/src/main/assets/pinyin_mix.txt.xz。

用途
----
单音节键（如 qie / xian / zuan）下同时有**单字**候选与**连写双音词**候选
（企鹅 = qi+e、西安 = xi+an、祖安 = zu+an 连写后与单音节同键）。运行时原先一律
「词先于字」（PinyinEngine.query 里 words.addAll(chars)），于是「企鹅」这类词被硬塞到
所有单字之前。本资产给出按**合理词频**交错后的次序，运行时就按它排。

词频口径
--------
字与词必须**同尺度**才可比，故都用 rime-ice 的 corpus 权重：
  - 单字：docs/rime-ice/cn_dicts/8105.dict.yaml 的 `字<TAB>读音<TAB>词频`，**按读音**取
    （「见」的 jiàn 与 xiàn 是两条，各归各的音节，不能拿主读音抬到别的音节上）；
  - 词：  docs/rime-ice/cn_dicts/{base,ext}.dict.yaml 的 `词<TAB>拼音<TAB>词频`。
缺权重者记 0（并保持其在原表里的相对次序）。

输入（均为当前已生成资产，与 APK 内一致）
------------------------------
  app/src/main/assets/pinyin_chars.txt.xz     音节 → 单字（`音节<TAB>字1,字2,...`）
  app/src/main/assets/pinyin_index.bin.xz     拼音键 → 词表（v2 二进制，见 build_dict_index.py）

输出
----
  app/src/main/assets/pinyin_mix.txt.xz       `音节<TAB>候选1|候选2|...`（按词频降序，
                                              仅含**既有单字又有词**的音节）

用法
----
    python tools/dict_builder/build_mix_order.py
"""
import io
import lzma
import os
import struct
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asset_io import write_bytes_atomically  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RIME = os.path.join(ROOT, "docs", "rime-ice", "cn_dicts")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
OUT = os.path.join(ASSETS, "pinyin_mix.txt.xz")


def parse_rime_weights(path):
    """`词<TAB>拼音<TAB>词频` → {(词, 无空格拼音): 词频}（同键取较大值）。"""
    out = {}
    started = False
    with io.open(path, encoding="utf-8", errors="replace") as f:
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
            word = parts[0].strip()
            pinyin = parts[1].strip().replace(" ", "")
            if not word or not pinyin:
                continue
            freq = int(parts[2]) if len(parts) >= 3 and parts[2].strip().isdigit() else 0
            key = (word, pinyin)
            if freq > out.get(key, -1):
                out[key] = freq
    return out


def load_char_table():
    """pinyin_chars.txt.xz → {音节: [字, ...]}（保持表内次序）。"""
    text = lzma.decompress(open(os.path.join(ASSETS, "pinyin_chars.txt.xz"), "rb").read()).decode("utf-8")
    table = {}
    for line in text.split("\n"):
        line = line.strip()
        if not line or line.startswith("#") or "\t" not in line:
            continue
        key, chars = line.split("\t", 1)
        table[key] = [c for c in chars.split(",") if c]
    return table


def load_word_index():
    """pinyin_index.bin.xz（v2）→ {键: [词, ...]}（保持词频序）。"""
    data = lzma.decompress(open(os.path.join(ASSETS, "pinyin_index.bin.xz"), "rb").read())
    if data[:4] != b"JNIH":
        raise SystemExit("索引 magic 不是 JNIH，格式变了？")
    version = struct.unpack_from("<H", data, 4)[0]
    if version != 2:
        raise SystemExit("索引版本不是 2（当前 %d），读取端需同步" % version)
    n = struct.unpack_from("<I", data, 6)[0]
    keys_len = struct.unpack_from("<I", data, 10)[0]
    words_len = struct.unpack_from("<I", data, 14)[0]
    off = 30  # magic(4)+ver(2)+count(4)+keysLen(4)+wordsLen(4)+reserved(4)+digest(8)
    keys_blob = data[off:off + keys_len]; off += keys_len
    key_lens = data[off:off + n]; off += n
    words_blob = data[off:off + words_len]; off += words_len
    word_lens = data[off:off + 2 * n]
    table = {}
    ko = 0
    wo = 0
    for i in range(n):
        k = keys_blob[ko:ko + key_lens[i]].decode("utf-8"); ko += key_lens[i]
        ln = struct.unpack_from("<H", word_lens, i * 2)[0]
        words = words_blob[wo:wo + ln].decode("utf-8").split("|") if ln else []
        wo += ln
        table[k] = words
    return table


def main():
    char_w = parse_rime_weights(os.path.join(RIME, "8105.dict.yaml"))
    word_w = parse_rime_weights(os.path.join(RIME, "base.dict.yaml"))
    for k, v in parse_rime_weights(os.path.join(RIME, "ext.dict.yaml")).items():
        if v > word_w.get(k, -1):
            word_w[k] = v

    chars = load_char_table()
    index = load_word_index()

    lines = []
    keys = 0
    for syl in sorted(chars):
        words = index.get(syl)
        if not words:
            continue
        # 单字在前、词在后（缺权重者权重 0，稳定排序时保持这个相对次序）
        seq = [("c", c, char_w.get((c, syl), 0)) for c in chars[syl]]
        seq += [("w", w, word_w.get((w, syl), 0)) for w in words]
        seq.sort(key=lambda t: -t[2])  # Python sort 稳定：同权重保持原序
        lines.append(syl + "\t" + "|".join(t[1] for t in seq))
        keys += 1

    text = ("# 单音节候选混合序（字频+词频按 rime 同尺度权重交错），"
            "由 tools/dict_builder/build_mix_order.py 生成；共 %d 键\n" % keys) + "\n".join(lines) + "\n"
    raw = text.encode("utf-8")
    xz = lzma.compress(raw, filters=[{"id": lzma.FILTER_LZMA2, "preset": 7, "lc": 4, "pb": 0}])
    # 原子写（BUG.md L-122）：这是进 APK 的资产，裸写中断会留下半截文件
    write_bytes_atomically(OUT, xz)
    print("混合序键数: %d" % keys)
    print("资产: %s（原始 %d B → xz %d B）" % (os.path.relpath(OUT, ROOT), len(raw), len(xz)))


if __name__ == "__main__":
    main()
