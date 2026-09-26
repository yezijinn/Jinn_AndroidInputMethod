# -*- coding: utf-8 -*-
r"""资产文本读写（`.txt` 与 `.txt.xz` 统一入口）。

为什么需要
----------
4 个纯文本资产（单字表 / 常用字表 / 三级字表 / 音节表）自 2026-09-26 起以 `.xz` 存进 APK：
deflate 后合计 63.8KB → xz 42.8KB，省下的 21KB 是 5MB 体积上限的余量。

生成端（写资产）与审阅端（读资产）必须走同一口径：一边写 `.txt`、另一边读 `.txt.xz`
会留下「脚本跑完看似成功、运行时用的还是旧数据」的静默不一致。

压缩参数与 APK 内一致：`preset=6` + `dict_size=1MB`。字典按输入规模给 1MB ——
解压端按流头分配字典，小文件不该按大资产那档的 8MB 吃内存。
"""
import lzma
import os

ASSET_XZ_FILTERS = [{"id": lzma.FILTER_LZMA2, "preset": 6, "dict_size": 1 << 20, "lc": 4, "pb": 0}]


def read_asset_text(path):
    """读资产文本；`.xz` 自动解压。换行归一为 LF。"""
    if path.endswith(".xz"):
        raw = lzma.decompress(open(path, "rb").read()).decode("utf-8")
    else:
        raw = open(path, encoding="utf-8").read()
    return raw.replace("\r\n", "\n")


def write_asset_text(path, text):
    """写资产文本；`.xz` 后缀自动压缩（参数与 APK 内一致）。"""
    if not text.endswith("\n"):
        text += "\n"
    if path.endswith(".xz"):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as fh:
            fh.write(lzma.compress(text.encode("utf-8"), filters=ASSET_XZ_FILTERS))
    else:
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)


def asset_name(stem):
    """资产文件名：`xxx` → `xxx.txt.xz`（当前统一格式）。"""
    return stem + ".txt.xz"
