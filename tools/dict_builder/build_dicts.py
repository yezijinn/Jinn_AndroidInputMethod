#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""一体化词库生成：分片短语库 + 三档单字表 + 简繁映射。

输入（`docs/所有词库/`，本地资料，不入库）
    单字/单字注音_一级简体.txt   `拼音<TAB>字`（默认档，5,613 字）
    单字/单字注音_二级简体.txt   （可选档 2）
    单字/单字注音_三级简体.txt   （可选档 3，依赖档 2）
    单字/简繁对照.txt            `简体<TAB>繁体<TAB>拼音` —— 人工**覆盖层**（见下「本次改动」）
    （字级简→繁主表取 docs/rime-wanxiang-wanxiang/opencc/wanxiang/STCharacters.txt，繁体优先）
    （词级消歧取 docs/rime-wanxiang-wanxiang/opencc/wanxiang/STPhrases.txt，OpenCC 简→繁词组表）
    短语/词库_第1部分.txt … 第4部分.txt   `词<TAB>拼音(空格分隔)<TAB>词频`

输出
    app/src/main/assets/pinyin_chars.txt.xz   三档合并单字表（引擎按档位开关过滤）
    app/src/main/assets/common_chars.txt.xz   档 1 字表（默认加载）
    app/src/main/assets/tier2_chars.txt.xz    档 2 字表（可选）
    app/src/main/assets/tier3_chars.txt.xz    档 3 字表（可选，依赖档 2）
    app/src/main/assets/pinyin_index.bin.xz   内置短语索引（第 1 部分，40 万条）
    app/src/main/assets/simp_trad.txt.xz      简繁映射（字级，OpenCC STCharacters + 人工覆盖）
    app/src/main/assets/simp_trad_words.txt.xz 简繁词级消歧（整词优先于逐字）
    app/src/main/assets/simplify.txt.xz       繁→简单字映射（折简体用，源 OpenCC TSCharacters）
    release/dict_part2.txt.xz / _part3 / _part4   分类词库下载包（40 / 50 / 60 万条）

用法
    python tools/dict_builder/build_dicts.py                 # 全量重建
    python tools/dict_builder/build_dicts.py --only chars    # 只重建单字表与档位表
    python tools/dict_builder/build_dicts.py --only parts    # 只重建索引与下载包
    python tools/dict_builder/build_dicts.py --only simp     # 只重建简繁映射

口径
  - 单字表顺序：以现有资产为基准（8105 字频序），档 2/3 新字按「档位 + 码点」追加到所属音节末尾；
  - 短语库：`拼音<TAB>词1|词2|…`，同键按词频降序、去重；下载包为文本格式（运行期建 `.idx` 缓存）；
  - 内置索引与下载包同源（第 1 部分 vs 第 2/3/4 部分），互不重叠。
"""
import argparse
import collections
import io
import lzma
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asset_io import (  # noqa: E402
    ASSET_XZ_FILTERS,
    read_asset_text,
    write_asset_text,
    write_bytes_atomically,
)
from build_dict_index import build_index_bytes  # noqa: E402
from convert_rime_ice import XZ_FILTERS  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, "docs", "所有词库")
D_CHARS = os.path.join(DOCS, "单字")
D_PHRASES = os.path.join(DOCS, "短语")
A = os.path.join(ROOT, "app", "src", "main", "assets")
RELEASE = os.path.join(ROOT, "release")

CHAR_FILES = ["单字注音_一级简体.txt", "单字注音_二级简体.txt", "单字注音_三级简体.txt"]
PARTS = {1: "词库_第1部分.txt", 2: "词库_第2部分.txt", 3: "词库_第3部分.txt", 4: "词库_第4部分.txt"}
# OpenCC 简→繁**单字**表（万象拼音随包数据，本机 docs/ 内）：字级映射主表，繁体优先
ST_CHARS = os.path.join(ROOT, "docs", "rime-wanxiang-wanxiang", "opencc", "wanxiang", "STCharacters.txt")
# OpenCC 简→繁**词组**表（万象拼音随包数据，本机 docs/ 内）：用于词级消歧
ST_PHRASES = os.path.join(ROOT, "docs", "rime-wanxiang-wanxiang", "opencc", "wanxiang", "STPhrases.txt")
# OpenCC 繁→简**单字**表：用于把繁体候选折回简体（反查）
SIMPLIFY_SRC = os.path.join(ROOT, "docs", "freq", "OpenCC-TSCharacters.txt")


def log(*a):
    print(*a)
    sys.stdout.flush()


def read_pairs(path):
    """`拼音<TAB>字` → [(拼音, 字)]（保序）"""
    out = []
    for l in io.open(path, encoding="utf-8").read().splitlines():
        if l.count("\t") == 1:
            p, c = l.split("\t")
            if p and len(c) == 1:
                out.append((p, c))
    return out


# ── 单字表与档位表 ────────────────────────────────────────
def build_chars():
    tiers = [read_pairs(os.path.join(D_CHARS, f)) for f in CHAR_FILES]
    tier_sets = [set(c for _, c in t) for t in tiers]
    for i in range(3):
        for j in range(i + 1, 3):
            inter = tier_sets[i] & tier_sets[j]
            # 真判据（**不用 `assert`**：`python -O` 会整体摘除，等于没有闸门 —— BUG.md L-49 / L-106）
            if inter:
                raise SystemExit("档位重叠（档 %d ∩ 档 %d）：%s" % (i + 1, j + 1, sorted(inter)[:10]))
    all_chars = set().union(*tier_sets)
    log("档位规模：一级 %d 字 / 二级 %d / 三级 %d；并集 %d" % (
        len(tier_sets[0]), len(tier_sets[1]), len(tier_sets[2]), len(all_chars)))

    # 读音真源：三档合并后的 (字 → [音节]）
    py_of = collections.defaultdict(list)
    for t in tiers:
        for p, c in t:
            py_of[c].append(p)
    syl_of = collections.defaultdict(list)
    for c, ps in py_of.items():
        for p in ps:
            syl_of[p].append(c)

    # 顺序基准：现有资产的块顺序与块内顺序（保持既有观感 —— 词频序）
    base = []
    try:
        for l in read_asset_text(os.path.join(A, "pinyin_chars.txt.xz")).split("\n"):
            if l.count("\t") == 1:
                s, cs = l.split("\t")
                base.append((s, [c for c in cs.split(",") if c]))
    except FileNotFoundError:
        base = []

    tier_of = {}
    for i, s in enumerate(tier_sets, start=1):
        for c in s:
            tier_of[c] = i

    seen = set()
    out = []
    appended = collections.Counter()
    for s, cs in base:
        kept = [c for c in cs if c in all_chars and s in py_of.get(c, ())]
        seen.update((s, c) for c in kept)
        extra = [c for c in syl_of.get(s, []) if (s, c) not in seen]
        extra.sort(key=lambda c: (tier_of.get(c, 9), ord(c)))
        for c in extra:
            seen.add((s, c))
        if kept or extra:
            out.append((s, kept + extra))
            appended[len(extra)] += 1
    known_syl = {s for s, _ in out}
    for s in sorted(syl_of):
        if s not in known_syl:
            cs = sorted(syl_of[s], key=lambda c: (tier_of.get(c, 9), ord(c)))
            out.append((s, cs))
            seen.update((s, c) for c in cs)

    missing = {(s, c) for s, cs in syl_of.items() for c in cs} - seen
    n = sum(len(cs) for _, cs in out)
    src_total = sum(len(t) for t in tiers)
    log("单字表：%d 音节 / %d 条（读盘 %d 条）" % (len(out), n, src_total))
    # 真判据（**不用 `assert`**：`python -O` 会把 assert 整体摘除，等于没有闸门 —— BUG.md L-49）：
    # ① 条目数必须与三档源表总数一致（归并里任何一处丢字/重复都会让两个数不等）；
    # ② 字集必须与三档并集一致（重复或漏收都会在这里暴露）。
    if missing:
        raise SystemExit("单字表漏字：%s" % sorted(missing)[:10])
    if n != src_total:
        raise SystemExit("单字表条目数与源表不一致（产出 %d / 源 %d）：归并逻辑丢字或重复" % (n, src_total))
    got_chars = {c for _, cs in out for c in cs}
    if got_chars != all_chars:
        raise SystemExit("单字表字集与三档并集不一致：多 %s / 少 %s"
                         % (sorted(got_chars - all_chars)[:10], sorted(all_chars - got_chars)[:10]))

    write_asset_text(os.path.join(A, "pinyin_chars.txt.xz"),
                     "\n".join("%s\t%s" % (s, ",".join(cs)) for s, cs in out))
    counts = []
    for i, (fn, label) in enumerate(zip(
            ("common_chars.txt.xz", "tier2_chars.txt.xz", "tier3_chars.txt.xz"),
            ("档 1（默认）", "档 2（可选）", "档 3（可选，依赖档 2）")), start=1):
        chars = sorted(tier_sets[i - 1], key=ord)
        text = ("# 内置字表 %s：docs/所有词库/单字/%s（%d 字）\n"
                "# 引擎按设置页开关决定是否放行本档；档 3 需先开档 2\n" % (label, CHAR_FILES[i - 1], len(chars))
                + "".join(chars) + "\n")
        write_asset_text(os.path.join(A, fn), text)
        counts.append(len(chars))
    log("档位表：common %d / tier2 %d / tier3 %d" % tuple(counts))
    return all_chars


# ── 短语库（索引 + 下载包） ───────────────────────────────
def read_phrases(part):
    """分片源 → {拼音键: [(词, 词频)]}（同键同词取最大词频）"""
    path = os.path.join(D_PHRASES, PARTS[part])
    byk = collections.defaultdict(dict)
    bad = 0
    for l in io.open(path, encoding="utf-8"):
        c = l.rstrip("\n").split("\t")
        if len(c) < 3:
            bad += 1
            continue
        w, py, f = c[0], c[1].replace(" ", "").lower(), c[2]
        if not w or not re.fullmatch(r"[a-z]+", py):
            bad += 1
            continue
        f = int(f) if f.isdigit() else 0
        prev = byk[py].get(w)
        if prev is None or f > prev:
            byk[py][w] = f
    if bad:
        log("   ⚠ %s 跳过异常行 %d" % (PARTS[part], bad))
    return byk


def render(byk):
    lines = []
    for k in sorted(byk):
        items = sorted(byk[k].items(), key=lambda kv: (-kv[1], kv[0]))
        lines.append("%s\t%s" % (k, "|".join(w for w, _ in items)))
    return "\n".join(lines)


def check_parts_partition():
    r"""「四片两两不重叠、各片自身不重复」的真判据（BUG.md L-108）。

    文档曾写「基础包（词长 ≤4）随 APK / 扩展包（>4 字）放附件」，实测两者都不成立
    （内置片里有 251 条长词；三片下载包里各有 38~49 万条 ≤4 字词）⇒ 真正的口径是**按量分片**。
    这里把「不重叠」钉成闸门：分片规则被改坏时构建期就红，而不是等运行时出现重复候选或缺词。
    **放在写盘之前**：宁可一条产物都不写，也别写出重叠的包。

    代价：比原来多读一遍四份源文件（≈2 秒、峰值内存多 ≈2 亿字节级——只在这个构建脚本里，可接受）。
    """
    seen = set()
    for part in (1, 2, 3, 4):
        byk = read_phrases(part)
        n = sum(len(ws) for ws in byk.values())
        words = {w for ws in byk.values() for w in ws}
        if len(words) != n:
            raise SystemExit("第 %d 部分内部有重复词条（%d 条里只有 %d 个不同的词）" % (part, n, len(words)))
        inter = words & seen
        if inter:
            raise SystemExit("第 %d 部分与前 %d 片重叠 %d 条：%s"
                             % (part, part - 1, len(inter), sorted(inter)[:5]))
        seen |= words
    log("分片完备性：四片合计 %d 个不同词，两两不重叠、各片无重复" % len(seen))


def build_parts(all_chars):
    # 写盘前先验分片（BUG.md L-108）：重叠 / 重复时一条产物都不写
    check_parts_partition()
    for part in (1, 2, 3, 4):
        t0 = time.time()
        byk = read_phrases(part)
        text = render(byk)
        n_keys = len(byk)
        n_words = sum(len(v) for v in byk.values())
        # 字集外词条统计（引擎查询期会整条过滤，数据层保留）
        out_set = collections.Counter()
        ext_a = set()          # 正文里出现的扩展 A 字（0x3400–0x4DBF）
        ext_a_out = set()      # 其中不在三档并集里的（引擎按档位过滤 ⇒ 这些词条只在对应档开启时可见）
        ext_a_words = 0
        ext_a_out_words = 0   # 其中含**档外**扩展 A 字的（KDoc 里那个 37 的口径）
        for k, ws in byk.items():
            for w in ws:
                if any(c not in all_chars for c in w):
                    out_set[len(w)] += 1
                e = {c for c in w if 0x3400 <= ord(c) <= 0x4DBF}
                if e:
                    ext_a_words += 1
                    ext_a |= e
                    ext_a_out |= (e - all_chars)
                    if e - all_chars:
                        ext_a_out_words += 1
        n_out = sum(out_set.values())
        if ext_a_out:
            # 别让「词库不含扩展 A 字」这种未经实测的结论再出现（BUG.md L-70）：实测有，
            # 且判据改成 `< 0x3400` 后档外的会被过滤 —— 这里如实打印，供审核对账。
            # 两个口径一起打（BUG.md L-82）：「含任意扩展 A 字」与「含档外扩展 A 字」
            # 是不同的数（真实测量例：74 vs 37），且都是**本部分**口径（合计需自行取并集）——
            # 以前只打前者，而 KDoc 引用的是后者，审核时必定打架。
            log("⚠ 第 %d 部分正文含扩展 A 字 %d 个（档外 %d 个）；牵涉词条：含任意扩展 A %d 条，其中含档外 %d 条：%s"
                % (part, len(ext_a), len(ext_a_out), ext_a_words, ext_a_out_words, "".join(sorted(ext_a_out))[:32]))
        # 产出闸门：源文件列序变了 / 整批行被判异常时，这里必须**响亮地失败**而不是写出空索引或空包
        # （空索引对引擎是「加载成功但无词」，症状是「词语候选全没了」而构建期一声不响，BUG.md L-49）
        if n_keys <= 0 or n_words <= 0:
            raise SystemExit("第 %d 部分产出为空（键 %d / 条 %d）：源文件异常，已中止，未写出任何产物"
                             % (part, n_keys, n_words))
        if part == 1:
            xz = lzma.compress(build_index_bytes(text), filters=XZ_FILTERS)
            write_bytes(os.path.join(A, "pinyin_index.bin.xz"), xz)
            log("第 1 部分（内置索引）：%d 键 / %d 条；xz %.3f MB；字集外 %d 条；%.1fs" % (
                n_keys, n_words, len(xz) / 1048576, n_out, time.time() - t0))
        else:
            xz = lzma.compress(text.encode("utf-8"), filters=XZ_FILTERS)
            os.makedirs(RELEASE, exist_ok=True)
            write_bytes(os.path.join(RELEASE, "dict_part%d.txt.xz" % part), xz)
            log("第 %d 部分（下载包）：%d 键 / %d 条；xz %.3f MB；字集外 %d 条；%.1fs" % (
                part, n_keys, n_words, len(xz) / 1048576, n_out, time.time() - t0))


def write_bytes(path, data):
    """原子写（BUG.md L-107）：实现收敛到 `asset_io.write_bytes_atomically`，两处写入口一份口径。"""
    return write_bytes_atomically(path, data)


# ── 简繁映射 ──────────────────────────────────────────────
def _bmp(ch):
    """是否在 BMP 内。

    字级表要按**加载端的判据**收字：Kotlin 侧用 `String.length == 1`（UTF-16），
    非 BMP 字符是代理对（长度 2）⇒ 收了也会在加载时被静默丢弃。这里用码点判据对齐，
    免得资产里白躺着永远用不到的项（BUG.md L-52）。
    """
    return ord(ch) <= 0xFFFF


def phrase_max_freq():
    """`词 → 四片里的最大词频`（词级表「同繁体多简体」定胜者用）。

    为什么需要它：词级反查表在**运行期**由 `buildWordBackMap` 的「首次写入者胜」建起来
    ⇒ **组内顺序就是语义**。而这个顺序原先等于 STPhrases 的文件顺序（偶然量）——
    实测 6 组同繁体多简体里有 2 组的胜者与词典口径相悖（BUG.md L-114）。
    规范形判据取「该简体词在短语词库里的最大词频」：本机没有 OpenCC TSPhrases（繁→简词表），
    词频是本地唯一能离线拿到的权威量。

    代价：读四片源文件（≈190 万词、数秒）—— 只在 `build_simp_trad` 里调一次，且只在该脚本里。
    """
    t0 = time.time()
    freq = {}
    for part in PARTS.values():
        for l in io.open(os.path.join(D_PHRASES, part), encoding="utf-8"):
            c = l.split("\t")
            if len(c) < 3:
                continue
            w = c[0].strip()
            if not w:
                continue
            try:
                v = int(float(c[2]))
            except ValueError:
                continue
            if v > freq.get(w, 0):
                freq[w] = v
    log("词频表：%d 个词（读四片源文件 %.1fs）" % (len(freq), time.time() - t0))
    return freq


def build_simp_trad():
    """字级映射 = OpenCC STCharacters（繁体优先，右侧多候选取首项）+ 人工表覆盖。

    ⚠ 2026-09-27（BUG.md L-37）换的口径：此前字级表**直接等于**人工表 `简繁对照.txt`（1,948 对），
    而按 OpenCC 口径「有繁体形的字」在并集内有 2,517 个 —— 缺的那批在繁体模式下**原地不动**
    （于→於 / 征→徵 / 托→託 / 佥→僉 / 扩展 A 的 䲢→鰧 一类）。
    人工表里已有的 1,948 对与 STCharacters 首候选**逐条一致（零冲突）**⇒ 本次是纯补齐：
    旧对一条不变、只多收。人工表仍作覆盖层（有分歧时以它为准，并把差异打进日志）。
    """
    base = {}
    pair_nonbmp = 0
    for l in io.open(ST_CHARS, encoding="utf-8", errors="replace").read().splitlines():
        c = l.split("\t")
        if len(c) != 2 or len(c[0]) != 1:
            continue
        cands = [x for x in c[1].split(" ") if x and len(x) == 1]
        if not cands or cands[0] == c[0]:
            continue
        # 非 BMP（扩展 B 及以后）一律不收：Kotlin 侧 `length == 1` 是 UTF-16 语义，见 _bmp
        if not _bmp(c[0]) or not _bmp(cands[0]):
            pair_nonbmp += 1
            continue
        base[c[0]] = cands[0]

    override = {}
    for l in io.open(os.path.join(D_CHARS, "简繁对照.txt"), encoding="utf-8").read().splitlines():
        c = l.split("\t")
        if len(c) == 3 and len(c[0]) == 1 and len(c[1]) == 1 and c[0] != c[1]:
            if not _bmp(c[0]) or not _bmp(c[1]):
                pair_nonbmp += 1
                continue
            override[c[0]] = c[1]
    diff = [(s, base.get(s), t) for s, t in override.items() if base.get(s) != t]
    if diff:
        log("⚠ 人工表与 OpenCC 首候选不一致 %d 条（以人工表为准）：%s" % (len(diff), diff[:8]))
    base.update(override)

    pairs = sorted(base.items())
    # 真判据（同上，不用 `assert` —— BUG.md L-106）：简体侧必须一对一，重复会让字级映射后写覆盖前写
    seen_simp, dup_simp = set(), []
    for j, _ in pairs:
        if j in seen_simp and j not in dup_simp:
            dup_simp.append(j)
        seen_simp.add(j)
    if dup_simp:
        raise SystemExit("简繁字级表简体侧重复：%s" % dup_simp[:5])
    char_map = dict(pairs)

    # 繁→简单字表（先建：下面规范化词级表的简体侧也要用它）。
    # 反查**不能**只用字级正向表反推 —— 它覆盖不了词级消歧表引入的那些繁体字
    # （髮 / 乾 / 淨 / 鬚…，实测仍有 1,700+ 个看不到）：整词未命中词级反查时逐字折返会得
    # 半简半繁的「头髮 / 擦干淨」，繁体模式下学过的词词频键就此跑偏
    # （简体模式、消费区间、预测全都认不出）。改取 OpenCC TSCharacters（右侧多候选取首项）。
    back_pairs = []
    nonbmp = 0
    for l in io.open(SIMPLIFY_SRC, encoding="utf-8"):
        if l.startswith("#") or "\t" not in l:
            continue
        k, v = l.rstrip("\n").split("\t")
        cands = [c for c in v.split(" ") if c]
        if len(k) != 1 or not cands or not _bmp(k) or not _bmp(cands[0]):
            # 非 BMP（扩展区）项一律不收：Kotlin 侧 `f.length == 1` 是 UTF-16 语义，
            # 代理对长度为 2 ⇒ 收了也会在加载时被静默丢弃（白占资产体积、口径不一致，BUG.md L-52）。
            nonbmp += 1
            continue
        if cands[0] != k:
            back_pairs.append((k, cands[0]))
    back_char = {f: j for j, f in pairs}   # 字级反推打底（口径与引擎的合并顺序一致）
    back_char.update(back_pairs)           # TSCharacters 覆盖 / 补充
    back_text = ("# 繁→简单字映射：OpenCC TSCharacters.txt（%d 项；右侧多候选取首项）\n"
                 "# 「只使用繁体字」下把繁体候选折回简体（词频 / 消费区间 / 排序的键都按简体）\n"
                 % len(back_pairs) + "\n".join("%s\t%s" % p for p in back_pairs) + "\n")
    write_asset_text(os.path.join(A, "simplify.txt.xz"), back_text)
    log("繁→简映射：%d 项（丢弃非 BMP / 多字符 %d 条）；字级反推另有 %d 项只在正向表里；xz %d 字节"
        % (len(back_pairs), nonbmp, len(set(f for f, _ in pairs) - set(f for f, _ in back_pairs)),
           os.path.getsize(os.path.join(A, "simplify.txt.xz"))))

    text = ("# 简繁映射：OpenCC STCharacters.txt（%d 对；右侧多候选取首项）+ 人工覆盖层\n"
            "# `简体<TAB>繁体`；「只使用繁体字」开启时，候选里的简体字按本表替换（整词优先，见 words 表）\n"
            % len(pairs)
            + "\n".join("%s\t%s" % p for p in pairs) + "\n")
    write_asset_text(os.path.join(A, "simp_trad.txt.xz"), text)
    log("简繁映射：%d 对（人工覆盖 %d 条；丢弃非 BMP %d 条）；xz %d 字节"
        % (len(pairs), len(override), pair_nonbmp,
           os.path.getsize(os.path.join(A, "simp_trad.txt.xz"))))

    # 词级消歧表：字级映射是 1:1 的（发→發），但「头发」应作「頭髮」——
    # 取 OpenCC STPhrases 里**逐字映射结果与词级结果不同**的词条，引擎侧整词优先命中、
    # 未命中再逐字兜底（「发财」这类逐字已正确的词不必进表）。
    #
    # ⚠ 先把**简体侧规范化**（用上面的反查逐字折简体）并丢弃折不动的条目：STPhrases 里
    # 混着简体侧本身带繁体字的脏条目（`干熱→乾熱`、`擦乾淨→擦乾淨` 之类），收进来会让
    # `toSimplified` 的整词路径与逐字路径给出**不同的键**（简模式「擦干净」、繁模式「擦干淨」），
    # 一次学习在切换开关后失效。丢弃即可 —— 这类词用逐字路径本来就是对的。
    words = []
    dirty = 0
    multi = 0
    redundant = 0
    kept_rev = 0
    for l in io.open(ST_PHRASES, encoding="utf-8").read().splitlines():
        c = l.split("\t")
        if len(c) != 2 or not c[0] or c[0] == c[1]:
            continue
        s, t = c
        # OpenCC 右侧允许**多候选**（空格分隔、按优先序，如 `不准<TAB>不準 不准`）。
        # 必须**先取首候选再判等长**：整串当目标词会让 `len(s) != len(t)` 成立、整条被丢，
        # 其中 22 条是真缺口 ⇒ 繁体模式下这些词退化成逐字而**转出别字**
        # （反复→「反復」应作「反覆」、发卡→「發卡」应作「髮卡」，BUG.md L-58）。
        if " " in t:
            multi += 1
            t = t.split(" ")[0]
        if not t or len(s) != len(t):
            continue
        s2 = "".join(back_char.get(ch, ch) for ch in s)
        if s2 != s:                     # 简体侧不干净 → 丢弃
            dirty += 1
            continue
        # 判据要**同时看两个方向**（2026-09-27，BUG.md L-71 第二轮）：
        #   正推：逐字（字级表）已等于词级结果 ⇒ 不需要覆盖；
        #   反查：`toSimplified` 的逐字兜底已能折回原简体 ⇒ 不需要词表兜底。
        # ⚠ 不能再用 `s == t` 提前跳过（同日 L-37 连带修）：字级表补齐后，「词级结果 == 简体原形」
        # 正是**需要覆盖**的一类（天台 / 天后 / 海里 / 丰度 / 出戏 / 占城 / 小丑 共 7 条）——
        # 逐字会把「天台」转成「天臺」，而 OpenCC 词级结果是「天台」。
        # ⚠ 只判正推也不够：异体字词条的反查要靠词表（`万锺→萬鍾` 逐字反查得「万钟」，
        # 词频键会落到**另一个词**上；实测 24 条，见下面 log 的「为反查保留」）。
        fwd_ok = "".join(char_map.get(ch, ch) for ch in s) == t
        rev_ok = "".join(back_char.get(ch, ch) for ch in t) == s
        if fwd_ok and rev_ok:
            redundant += 1
            continue
        if fwd_ok:
            kept_rev += 1
        words.append((s, t))
    # 「同繁体多简体」的胜者由**运行期 `buildWordBackMap` 的「首次写入者胜」**决定 ⇒ 组内顺序即语义。
    # 这个顺序原先等于 STPhrases 的文件顺序（偶然量）：实测 6 组里有 2 组的胜者与词典口径相悖 ——
    # `六鬚鮎→六须鲇`（词频 0）、`傢俱→家俱`（17,050 < 家具 18,970）⇒ 繁体模式下选中该候选后，
    # `toSimplified` 折回得到**另一个简体词**，词频键与简体模式同词不一致（学习不迁移，BUG.md L-114）。
    # 判据：组内「短语词库最大词频」最高者**先出**（其余条目保持原有相对顺序）；并列或整组都
    # 不在词库时不猜、保留原序，并把该组打进日志（⚠ 口径）。
    freq = phrase_max_freq()
    by_trad = collections.OrderedDict()
    for i, (s, t) in enumerate(words):
        by_trad.setdefault(t, []).append(i)
    groups = [(t, v) for t, v in by_trad.items() if len(v) > 1]
    flipped, no_authority, moves = [], [], []
    for t, idxs in groups:
        cands = [words[i][0] for i in idxs]
        best = max(cands, key=lambda s: freq.get(s, 0))
        top = [s for s in cands if freq.get(s, 0) == freq.get(best, 0)]
        if freq.get(best, 0) <= 0 or len(top) > 1:
            no_authority.append((t, cands))
            continue
        if cands[0] == best:
            continue                       # 胜者已在组首：一动不动（避免整块位移污染 diff）
        k = next(i for i in idxs if words[i][0] == best)
        moves.append((idxs[0], k))         # (目标位置, 当前位置)
        flipped.append((t, cands[0], best))
    # **从后往前**搬：只在同一条列表上做 pop/insert，后面的位置先动，前面的下标才不受影响
    for dst, src in sorted(moves, key=lambda m: -m[0]):
        words.insert(dst, words.pop(src))
    log("简繁词级消歧：同繁体多简体 %d 组；按词频调序 %d 组（%s）"
        % (len(groups), len(flipped),
           "；".join("%s：%s→%s" % (t, old, new) for t, old, new in flipped) or "无"))
    if no_authority:
        log("⚠ 简繁词级消歧：%d 组没有词频判据（整组不在词库或最高频并列），保留原序：%s"
            % (len(no_authority), no_authority[:3]))

    words_text = ("# 简繁词级消歧：OpenCC STPhrases 中「逐字映射 != 词级结果」的 %d 条\n"
                  "# （如 头发→頭髮、干净→乾淨、台风→颱風）；整词命中优先于逐字映射\n" % len(words)
                  + "\n".join("%s\t%s" % p for p in words) + "\n")
    write_asset_text(os.path.join(A, "simp_trad_words.txt.xz"), words_text)
    log("简繁词级消歧：%d 条（丢弃简体侧不干净 %d 条；两个方向都能靠字级表还原 %d 条；为反查保留 %d 条；多候选取首项 %d 条）；xz %d 字节"
        % (len(words), dirty, redundant, kept_rev, multi,
           os.path.getsize(os.path.join(A, "simp_trad_words.txt.xz"))))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", choices=["chars", "parts", "simp"], default=None)
    args = ap.parse_args()
    do = lambda what: args.only in (None, what)  # noqa: E731
    if do("chars"):
        all_chars = build_chars()
    else:
        tier_sets = [set(c for _, c in read_pairs(os.path.join(D_CHARS, f))) for f in CHAR_FILES]
        all_chars = set().union(*tier_sets)
    if do("parts"):
        build_parts(all_chars)
    if do("simp"):
        build_simp_trad()
    log("assets 合计：%d 字节" % sum(
        os.path.getsize(os.path.join(A, f)) for f in os.listdir(A)))


if __name__ == "__main__":
    main()
