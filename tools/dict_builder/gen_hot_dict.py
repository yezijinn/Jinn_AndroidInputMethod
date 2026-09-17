#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「高频子集」词库（冷启动秒级可用的应急词库）。

背景（真机实测）
----------------
基础词库 60.4 万键、解析+建表要 6~10.7 秒（装了可选包还要再加 21~34 秒）；
在这段窗口内 `PinyinEngine.query()` 因 `loaded == false` 直接返回空 ——
用户看到的就是「键盘能弹、但一个字都打不出来」。

做法
----
从**与全量资产完全相同的来源与顺序**（`base.dict.yaml` → `ext.dict.yaml`，与
`convert_rime_ice.py` 一致）按**词频**取权重最高的一批词，生成同格式的
`assets/hot_phrases.txt.xz`：引擎先加载它并立即置 `loaded = true`（秒级可打字），
随后后台把全量基础包 merge 进来。

为什么必须「同源同序 + 按权重阈值取词」
--------------------------------------
引擎的 merge 语义是「已有在前 + 去重追加」，所以**只有当子集在每个键下都是全量词表的
前缀时**，并入后的候选顺序才与「一次性全量加载」完全一致。本脚本据此实现：
  · 解析顺序与全量资产一致（先 base 后 ext）；
  · 同键同词取最大词频（与 `build_dict` 一致）；
  · 只按词长 ≤4 过滤（与基础包一致）；
  · 取「权重 ≥ 阈值 W」的**全部**词（含并列）——若按条数硬切，并列词可能只进一半，
    该键的前缀性质就会被破坏。
脚本末尾自带校验：抽样比对全量资产，确认子集确是每个键的前缀。

用法
----
    python tools/dict_builder/gen_hot_dict.py            # 默认 N=40000
    python tools/dict_builder/gen_hot_dict.py -n 20000   # 换规模（体积/覆盖更小）
    python tools/dict_builder/gen_hot_dict.py --dry-run  # 只报告不写文件
"""

import argparse
import io
import lzma
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from convert_rime_ice import BASE_MAX_LEN, XZ_FILTERS, parse_rime_dict  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC_DIR = os.path.join(ROOT, "docs", "rime-ice", "cn_dicts")
OUT_XZ = os.path.join(ROOT, "app", "src", "main", "assets", "hot_phrases.txt.xz")
FULL_XZ = os.path.join(ROOT, "app", "src", "main", "assets", "pinyin_phrases.txt.xz")

# 冷启动必须能打出来的词（缺一即失败）
MUST_HAVE = ["你好", "我们", "什么", "这个", "可以", "没有", "今天", "谢谢", "对不起", "因为"]

# 只报告、不失败的词：源里权重偏低，会随「全量基础包」在数秒后 merge 进来；
# 期间输入这些词也不会「什么都出不来」——单字表已先加载，任何合法音节都有单字候选兜底。
INFO_WORDS = ["词库", "双拼", "剪贴板", "键盘", "输入法", "外卖", "群聊", "朋友圈"]


def collect_entries():
    """与全量资产同源同序地收集词条，返回 (插入顺序, 同键同词的最大词频)。"""
    order, best = [], {}
    for name in ("base", "ext"):
        path = os.path.join(SRC_DIR, f"{name}.dict.yaml")
        for word, key, freq in parse_rime_dict(path):
            if len(word) > BASE_MAX_LEN or freq <= 0:
                continue
            k = (key, word)
            if k not in best:
                order.append(k)
                best[k] = freq
            elif freq > best[k]:
                best[k] = freq
    return order, best


def main():
    ap = argparse.ArgumentParser(description="生成高频子集词库")
    ap.add_argument("-n", "--top", type=int, default=40000,
                    help="按词频取前 N 条（默认 40000：xz 约 224KB、真机加载约 0.4~0.6s）")
    ap.add_argument("--dry-run", action="store_true", help="只报告，不写文件")
    args = ap.parse_args()

    order, best = collect_entries()
    first_seen = {k: i for i, k in enumerate(order)}
    # 与 build_dict 相同的排序：词频降序，同频保持首次出现顺序（稳定）
    ordered = sorted(order, key=lambda k: (-best[k], first_seen[k]))
    cutoff = best[ordered[min(args.top, len(ordered)) - 1]]
    picked = [k for k in ordered if best[k] >= cutoff]      # 含并列，保证前缀性质

    by_key = {}
    for key, word in picked:
        by_key.setdefault(key, []).append(word)
    text = "\n".join(f"{k}\t{'|'.join(by_key[k])}" for k in sorted(by_key))
    raw = text.encode("utf-8")
    xz = lzma.compress(raw, filters=XZ_FILTERS)

    words = {w for _, w in picked}
    missing = [w for w in MUST_HAVE if w not in words]
    deferred = [w for w in INFO_WORDS if w not in words]

    print(f"候选词条（≤{BASE_MAX_LEN} 字、有词频）: {len(order)}")
    print(f"取权重 ≥ {cutoff} 的词条: {len(picked)} 条 → {len(by_key)} 键 / "
          f"{sum(len(v) for v in by_key.values())} 词")
    print(f"体积：原始 {len(raw) / 1024:.0f} KB → xz {len(xz) / 1024:.0f} KB")
    print(f"必含词检查：{'全部命中 ✓' if not missing else '缺失 ' + str(missing)}")
    if deferred:
        print(f"（随全量包补上、不在子集内：{'、'.join(deferred)}）")

    if args.dry_run:
        return 0
    if missing:
        print("✗ 有必含词缺失，请调大 --top 后重试", file=sys.stderr)
        return 1

    io.open(OUT_XZ, "wb").write(xz)
    print(f"已写入 {OUT_XZ}")
    return verify_prefix(by_key)


def verify_prefix(by_key):
    """自校验：抽样确认「子集 = 全量每个键的前缀」。"""
    sample = [k for i, k in enumerate(by_key) if i % 100 == 0]
    want = set(sample)
    problems, checked = [], 0
    with lzma.open(FULL_XZ, "rt", encoding="utf-8") as f:
        for line in f:
            if checked >= len(want):
                break
            key, _, value = line.rstrip("\n").partition("\t")
            if key in want:
                sub = by_key[key]
                if value.split("|")[: len(sub)] != sub:
                    problems.append(key)
                checked += 1
    status = "全部为前缀 ✓" if not problems else f"异常 {problems[:5]}"
    print(f"前缀自校验：抽样 {len(want)} 键、命中 {checked} 键 → {status}")
    return 0 if (checked == len(want) and not problems) else 1


if __name__ == "__main__":
    sys.exit(main())
