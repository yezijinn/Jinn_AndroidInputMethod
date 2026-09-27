#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""一体化词库生成：分片短语库 + 三档单字表 + 简繁映射。

输入（`docs/所有词库/`，本地资料，不入库）
    单字/单字注音_一级简体.txt   `拼音<TAB>字`（默认档，5,613 字）
    单字/单字注音_二级简体.txt   （可选档 2）
    单字/单字注音_三级简体.txt   （可选档 3，依赖档 2）
    单字/简繁对照.txt            `简体<TAB>繁体<TAB>拼音`
    （词级消歧取 docs/rime-wanxiang-wanxiang/opencc/wanxiang/STPhrases.txt，OpenCC 简→繁词组表）
    短语/词库_第1部分.txt … 第4部分.txt   `词<TAB>拼音(空格分隔)<TAB>词频`

输出
    app/src/main/assets/pinyin_chars.txt.xz   三档合并单字表（引擎按档位开关过滤）
    app/src/main/assets/common_chars.txt.xz   档 1 字表（默认加载）
    app/src/main/assets/tier2_chars.txt.xz    档 2 字表（可选）
    app/src/main/assets/tier3_chars.txt.xz    档 3 字表（可选，依赖档 2）
    app/src/main/assets/pinyin_index.bin.xz   内置短语索引（第 1 部分，40 万条）
    app/src/main/assets/simp_trad.txt.xz      简繁映射（1,948 对，逐字）
    app/src/main/assets/simp_trad_words.txt.xz 简繁词级消歧（8,965 条，整词优先于逐字）
    app/src/main/assets/simplify.txt.xz       繁→简单字映射（2,965 项；折简体用，源 OpenCC TSCharacters）
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
from asset_io import ASSET_XZ_FILTERS, read_asset_text, write_asset_text  # noqa: E402
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
            assert not inter, "档位重叠: %s" % sorted(inter)[:10]
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


def build_parts(all_chars):
    for part in (1, 2, 3, 4):
        t0 = time.time()
        byk = read_phrases(part)
        text = render(byk)
        n_keys = len(byk)
        n_words = sum(len(v) for v in byk.values())
        # 字集外词条统计（引擎查询期会整条过滤，数据层保留）
        out_set = collections.Counter()
        for k, ws in byk.items():
            for w in ws:
                if any(c not in all_chars for c in w):
                    out_set[len(w)] += 1
        n_out = sum(out_set.values())
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
    open(path, "wb").write(data)
    return len(data)


# ── 简繁映射 ──────────────────────────────────────────────
def _bmp(ch):
    """是否在 BMP 内。

    字级表要按**加载端的判据**收字：Kotlin 侧用 `String.length == 1`（UTF-16），
    非 BMP 字符是代理对（长度 2）⇒ 收了也会在加载时被静默丢弃。这里用码点判据对齐，
    免得资产里白躺着永远用不到的项（BUG.md L-52）。
    """
    return ord(ch) <= 0xFFFF


def build_simp_trad():
    src = os.path.join(D_CHARS, "简繁对照.txt")
    pairs = []
    pair_nonbmp = 0
    for l in io.open(src, encoding="utf-8").read().splitlines():
        c = l.split("\t")
        if len(c) == 3 and len(c[0]) == 1 and len(c[1]) == 1 and c[0] != c[1]:
            if not _bmp(c[0]) or not _bmp(c[1]):
                pair_nonbmp += 1
                continue
            pairs.append((c[0], c[1]))
    assert len(set(j for j, _ in pairs)) == len(pairs), "简体侧重复"
    char_map = dict(pairs)

    # 繁→简单字表（先建：下面规范化词级表的简体侧也要用它）。
    # 反查**不能**只用字级正向表反推 —— 它只有 1,948 对，而词级消歧表引入的繁体字里
    # 有 2,100 个不在其中（髮 / 乾 / 淨 / 鬚…）：整词未命中词级反查时逐字折返会得
    # 半简半繁的「头髮 / 擦干淨」，繁体模式下学过的词词频键就此跑偏
    # （简体模式、消费区间、预测全都认不出）。改取 OpenCC TSCharacters（过滤后 2,965 项，
    # 右侧多候选取首项 —— OpenCC 的优先序）。
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
    log("繁→简映射：%d 项（丢弃非 BMP / 多字符 %d 条）；xz %d 字节"
        % (len(back_pairs), nonbmp, os.path.getsize(os.path.join(A, "simplify.txt.xz"))))

    text = ("# 简繁映射：docs/所有词库/单字/简繁对照.txt（%d 对）\n"
            "# `简体<TAB>繁体`；「只使用繁体字」开启时，候选里的简体字按本表替换\n" % len(pairs)
            + "\n".join("%s\t%s" % p for p in pairs) + "\n")
    write_asset_text(os.path.join(A, "simp_trad.txt.xz"), text)
    log("简繁映射：%d 对（丢弃非 BMP %d 条）；xz %d 字节"
        % (len(pairs), pair_nonbmp, os.path.getsize(os.path.join(A, "simp_trad.txt.xz"))))

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
        if s == t or "".join(char_map.get(ch, ch) for ch in s) == t:
            continue                    # 逐字路径已正确 → 不必进表
        words.append((s, t))
    words_text = ("# 简繁词级消歧：OpenCC STPhrases 中「逐字映射 != 词级结果」的 %d 条\n"
                  "# （如 头发→頭髮、干净→乾淨、台风→颱風）；整词命中优先于逐字映射\n" % len(words)
                  + "\n".join("%s\t%s" % p for p in words) + "\n")
    write_asset_text(os.path.join(A, "simp_trad_words.txt.xz"), words_text)
    log("简繁词级消歧：%d 条（丢弃简体侧不干净 %d 条；多候选取首项 %d 条）；xz %d 字节"
        % (len(words), dirty, multi, os.path.getsize(os.path.join(A, "simp_trad_words.txt.xz"))))


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
