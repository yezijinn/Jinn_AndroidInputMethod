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


def write_bytes_atomically(path, data):
    r"""二进制原子落盘：同目录临时件 → 长度校验 → `os.replace` 覆盖（BUG.md L-107）。

    直接 `open(path, "wb").write(data)` 在中断 / 磁盘满 / 进程被杀时会留下**半截资产**：
    内置索引与下载包要么进 APK、要么上传 Release 附件（checksum 与文档对不上），
    文本资产则是「脚本跑完看似成功、运行时用的还是半截数据」——与 Kotlin 侧
    `PinyinEngine.writeIndexCacheAtomically` / `UserFrequency.writeAtomically` 是同一条纪律。

    临时件放**同目录**（`path + ".tmp"`）：`os.replace` 只有同文件系统内才是原子替换。
    失败路径不留残件（`finally` 清 tmp）。
    """
    tmp = path + ".tmp"
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    try:
        with open(tmp, "wb") as fh:
            fh.write(data)
        size = os.path.getsize(tmp)
        if size != len(data):
            raise SystemExit("落盘长度不符：%s（写入 %d / 实际 %d）" % (path, len(data), size))
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.remove(tmp)
    return len(data)


def write_asset_text(path, text):
    """写资产文本；`.xz` 后缀自动压缩（参数与 APK 内一致）。落盘走 [write_bytes_atomically]。"""
    if not text.endswith("\n"):
        text += "\n"
    if path.endswith(".xz"):
        write_bytes_atomically(path, lzma.compress(text.encode("utf-8"), filters=ASSET_XZ_FILTERS))
    else:
        write_bytes_atomically(path, text.encode("utf-8"))


def asset_name(stem):
    """资产文件名：`xxx` → `xxx.txt.xz`（当前统一格式）。"""
    return stem + ".txt.xz"
