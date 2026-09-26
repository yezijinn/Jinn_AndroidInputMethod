#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
生成规范表三级字 asset（`app/src/main/assets/tier3_chars.txt`）。

为什么需要
----------
单字过滤原先只认「一级 + 二级 6500 字」，三级字被整档判成生僻（囧/淼/喆/昇/堃
一类人名地名用字默认打不出）。三级字同样是规范汉字，应与一级二级一起默认加载，
只有表外字（繁体 / 异体 / 日韩 / 扩展区）才随「加更多生僻字」开关。

判据来源
--------
三级字 = 《通用规范汉字表》全表 − 一二级表
    docs/rime-ice/cn_dicts/8105.dict.yaml   规范表全表（rime-ice 收录）
    app/src/main/assets/common_chars.txt    现行一二级表
只导出**基本区**（0x4E00~0x9FFF）的字：位图只覆盖这一段，扩展区的字走
「< 0x4E00 一律放行 / 其余按表外」的老口径，不需要写进表里。

用法
    python tools/dict_builder/gen_tier3_chars.py
"""

import io
import os
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asset_io import read_asset_text, write_asset_text  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TABLE_8105 = os.path.join(ROOT, "docs", "rime-ice", "cn_dicts", "8105.dict.yaml")
# 资产以 .xz 存进 APK（体积余量），读写统一走 asset_io
COMMON_ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "common_chars.txt.xz")
OUT_ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "tier3_chars.txt.xz")

HAN_LO, HAN_HI = 0x4E00, 0x9FFF
PER_LINE = 50


def read_chars(path):
    """读字表：`#` 注释跳过，其余行的汉字全部计入。"""
    chars = set()
    for line in read_asset_text(path).split("\n"):
        s = line.strip()
        if not s or s.startswith("#"):
            continue
        chars.update(s)
    return chars


def read_table_8105(path):
    """读 8105.dict.yaml 的正文（`...` 之后每行首列的单字）。"""
    chars = set()
    started = False
    for line in io.open(path, encoding="utf-8"):
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


def main():
    common = read_chars(COMMON_ASSET)
    full = read_table_8105(TABLE_8105)
    tier3 = sorted(c for c in full - common if HAN_LO <= ord(c) <= HAN_HI)

    header = [
        "# 规范表三级字：《通用规范汉字表》(2013) 三级，取自 8105 全表减去一级+二级（common_chars.txt）",
        "# 与常用字表同为默认加载档；表外字（繁体/异体/日韩/扩展区）仍随 Prefs.showRareChars 开关",
        "# 生成：python tools/dict_builder/gen_tier3_chars.py（只收基本区 0x4E00~0x9FFF，共 %d 字）"
        % len(tier3),
    ]
    body = ["".join(tier3[i:i + PER_LINE]) for i in range(0, len(tier3), PER_LINE)]
    write_asset_text(OUT_ASSET, "\n".join(header + body))

    print("三级字 = %d（全表 %d − 一二级 %d 后取基本区）" % (len(tier3), len(full), len(common)))
    print("已写入 %s" % os.path.relpath(OUT_ASSET, ROOT))


if __name__ == "__main__":
    main()
