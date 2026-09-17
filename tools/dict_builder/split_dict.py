#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
词库切分：把完整词库拆成「基础包」与「扩展包」。

背景
----
完整词库 xz 后仍有 8MB，占 APK 体积的 95%。而词库里 85.9% 的词条是 3 字及以上的
长尾专有名词（动植物名、地名、人名、作品名、游戏道具名…），日常输入几乎用不到。
把它们移出 APK、改成按需下载的扩展包，APK 可以显著瘦身。

用法
----
    python split_dict.py <源词库.txt> -o <输出目录> [--base-max-len 3]

产出
----
    <输出>/base/pinyin_phrases.txt.xz   基础包（≤ base-max-len 字的词）
                                        ⚠ 现行流程里**不进 APK**：基础包文本只作留档，
                                        进 APK 的是 build_dict_index.py 产出的二进制索引
                                        （assets/pinyin_index.bin.xz）
    <输出>/ext/dict_ext.txt.xz          扩展包（其余长词），按需下载

两个包格式完全相同（`拼音<TAB>词1|词2`），运行时用同一套解析器加载、合并即可。
切分依据只有「词长」——因为词库格式里没有词频字段，无法按频率切。
因此 base-max-len 的选择直接决定"丢掉哪些词"：

    base-max-len=2  基础包 0.64MB  丢 3 字词（含「天安门」「计算机」）——过于激进
    base-max-len=3  基础包 3.16MB  丢 4 字及以上（含四字成语、专有名词）
    base-max-len=4  基础包 5.74MB  只丢 5 字及以上（含「中华人民共和国」）

默认 3：三字词覆盖日常表达，四字及以上交给扩展包。
"""
import argparse
import lzma
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser(description="把词库切成基础包与扩展包")
    ap.add_argument("source", help="完整词库 txt（拼音<TAB>词1|词2）")
    ap.add_argument("-o", "--output", default="out", help="输出目录")
    ap.add_argument("--base-max-len", type=int, default=3,
                    help="基础包保留的最大词长（默认 3）")
    args = ap.parse_args()

    src = Path(args.source)
    if not src.is_file():
        print(f"[错误] 词库文件不存在: {src}", file=sys.stderr)
        sys.exit(1)

    out = Path(args.output)
    base_dir = out / "base"
    ext_dir = out / "ext"
    base_dir.mkdir(parents=True, exist_ok=True)
    ext_dir.mkdir(parents=True, exist_ok=True)

    lim = args.base_max_len
    base_lines, ext_lines = [], []
    base_words = ext_words = 0
    for line in src.read_text(encoding="utf-8").splitlines():
        if "\t" not in line:
            continue
        key, words = line.split("\t", 1)
        kept_base = [w for w in words.split("|") if len(w) <= lim]
        kept_ext = [w for w in words.split("|") if len(w) > lim]
        if kept_base:
            base_lines.append(f"{key}\t{'|'.join(kept_base)}")
            base_words += len(kept_base)
        if kept_ext:
            ext_lines.append(f"{key}\t{'|'.join(kept_ext)}")
            ext_words += len(kept_ext)

    def write_xz(path, lines):
        data = ("\n".join(lines) + "\n").encode("utf-8")
        with lzma.open(path, "wb", preset=6) as f:
            f.write(data)
        return len(data), path.stat().st_size

    base_raw, base_xz = write_xz(base_dir / "pinyin_phrases.txt.xz", base_lines)
    ext_raw, ext_xz = write_xz(ext_dir / "dict_ext.txt.xz", ext_lines)

    print(f"基础包（≤{lim} 字）: {len(base_lines)} 键 / {base_words} 词条")
    print(f"  {base_raw/1048576:.2f}MB → xz {base_xz/1048576:.2f}MB   ← 进 APK")
    print(f"扩展包（>{lim} 字）: {len(ext_lines)} 键 / {ext_words} 词条")
    print(f"  {ext_raw/1048576:.2f}MB → xz {ext_xz/1048576:.2f}MB   ← 按需下载")
    print(f"总计词条校验: {base_words + ext_words}（源词库应与此一致）")


if __name__ == "__main__":
    main()
