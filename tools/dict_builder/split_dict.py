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
import os
from pathlib import Path

# 输出与报错统一 utf-8：Windows 默认 cp936 会把中文汇总 / 报错写成乱码（另两支脚本同款）
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# 失败信息走 stderr：Windows 默认 cp936 会把中文报错写成乱码，这里统一成 utf-8
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def check_before_write(bad_lines, empty_lines, src_words, base_words, ext_words):
    """写盘前的真判据（`BUG.md` L-140）：坏行 / 空词条一律中止，且词条必须守恒。

    为什么要它：本脚本写的是 **Release 分片**（扩展包按需下载）。静默丢一行、丢一个词条时
    产物本身看不出异常 —— 用户只会觉得「某些词打不出来」。判据不通过时**一条产物都不写**
    （与 L-108 立的分片核查同一口径；不是 `assert`，`python -O` 摘不掉，见 L-106）。
    """
    if bad_lines:
        raise SystemExit("输入有 %d 行没有制表符（前 3 例：%s）—— 先清洗输入再切分"
                         % (len(bad_lines), bad_lines[:3]))
    if empty_lines:
        raise SystemExit("输入有 %d 行含空词条（前 3 例：%s）—— `a||b` 会把空词写进包"
                         % (len(empty_lines), empty_lines[:3]))
    if base_words + ext_words != src_words:
        raise SystemExit("词条不守恒：源 %d / 产出 %d（base %d + ext %d）"
                         % (src_words, base_words + ext_words, base_words, ext_words))


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

    lim = args.base_max_len
    base_lines, ext_lines = [], []
    base_words = ext_words = src_words = 0
    bad_lines, empty_lines = [], []
    for line in src.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        if "\t" not in line:
            bad_lines.append(line[:40])
            continue
        key, words = line.split("\t", 1)
        parts = words.split("|")
        if any(not w for w in parts):
            empty_lines.append(key)
        parts = [w for w in parts if w]
        src_words += len(parts)
        kept_base = [w for w in parts if len(w) <= lim]
        kept_ext = [w for w in parts if len(w) > lim]
        if kept_base:
            base_lines.append(f"{key}\t{'|'.join(kept_base)}")
            base_words += len(kept_base)
        if kept_ext:
            ext_lines.append(f"{key}\t{'|'.join(kept_ext)}")
            ext_words += len(kept_ext)

    # 写盘前的真判据（BUG.md L-140）：不通过时一条产物都不写
    check_before_write(bad_lines, empty_lines, src_words, base_words, ext_words)
    # 判据通过后才建输出目录：不通过时连目录都不留（「一条产物都不写」）
    base_dir.mkdir(parents=True, exist_ok=True)
    ext_dir.mkdir(parents=True, exist_ok=True)

    def write_xz(path, lines):
        # 原子落盘（BUG.md L-122）：保留 `lzma.open` 的压缩参数（字节不变），
        # 只把目标换成**同目录**临时件再 `os.replace` —— 跨盘替换不原子，临时件必须同目录；
        # 名字带进程号：两进程并发跑同一支脚本时会互相截断同一个固定名临时件（BUG.md L-135）。
        data = ("\n".join(lines) + "\n").encode("utf-8")
        tmp = "%s.tmp.%d" % (path, os.getpid())
        try:
            with lzma.open(tmp, "wb", preset=6) as f:
                f.write(data)
            os.replace(tmp, path)
        finally:
            # 无竞态清理：见 asset_io.write_bytes_atomically 的同款说明
            try:
                os.remove(tmp)
            except FileNotFoundError:
                pass
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
