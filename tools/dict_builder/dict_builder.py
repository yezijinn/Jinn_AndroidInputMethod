#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CapsWriterIME 词库构建工具（dict_builder）。

从 Rime dict.yaml / TXT / CSV 解析词库，经清洗/去重/拼音标准化/自然码转换，
生成 app/src/main/assets/ 下的文本 asset。

统一词库结构（参考 docs/DATA_SOURCES.md）：
    word      词语
    pinyin    全拼（音节空格分隔，多音字按词保存）
    ziranma   自然码双拼编码（本项目唯一方案）
    frequency 词频
    source    数据源标识
    category  分类

输出格式（与 PinyinEngine 现有加载器兼容）：
    pinyin_phrases.txt : 去空格全拼<TAB>词语1|词语2|...（按词频降序）
    pinyin_chars.txt   : 音节<TAB>单字1,单字2,...（按词频降序）
    pinyin_syllables.txt : 合法音节全集（每行一个）

用法：
    python dict_builder.py rime-ice/cn_dicts/base.dict.yaml -s rime-ice -o out/
    python dict_builder.py *.dict.yaml -s rime-ice -o out/

许可证提示：Rime 词库（雾凇/白霜）为 GPL-3.0，使用前确认与项目合规（见 docs/DATA_SOURCES.md）。
"""
import argparse
import re
import sys
from collections import OrderedDict

# ── 自然码双拼（与 app 内 Shuangpin.kt 保持一致）────────────

# 双字母声母 → 单键
INITIAL_KEY = {"zh": "v", "ch": "i", "sh": "u"}

# 韵母 → 键（自然码）。多值按声母消歧。
FINAL_KEY = {
    "a": "a", "ai": "l", "an": "j", "ang": "h", "ao": "k",
    "e": "e", "ei": "z", "en": "f", "eng": "g", "er": "r",
    "i": "i", "ia": "w", "ian": "m", "iang": "d", "iao": "c",
    "ie": "x", "in": "n", "ing": "y", "iong": "s", "iu": "q",
    "o": "o", "ong": "s", "ou": "b",
    "u": "u", "ua": "w", "uai": "y", "uan": "r", "uang": "d",
    "ue": "t", "ui": "v", "un": "p", "uo": "o",
    "v": "v", "ve": "t",
}

JQX = set("jqx")
JQX_Y = set("jqxy")
L_N = set("ln")
UAI_INITIALS = set("gkh") | {"zh", "ch", "sh"}
UA_INITIALS = set("gkh") | {"zh", "ch", "sh"}

SINGLE_FINALS = {
    "a": "a", "l": "ai", "j": "an", "h": "ang", "k": "ao",
    "e": "e", "z": "ei", "f": "en", "g": "eng",
    "i": "i", "m": "ian", "c": "iao", "x": "ie",
    "n": "in", "q": "iu", "b": "ou", "u": "u",
}

ZERO_TABLE = {
    "aa": "a", "ee": "e", "oo": "o",
    "an": "an", "ai": "ai", "ao": "ao", "ei": "ei",
    "en": "en", "er": "er", "ou": "ou",
    "ah": "ang", "eg": "eng",
    "aj": "an", "al": "ai", "ak": "ao", "ez": "ei",
    "ef": "en", "or": "er",
    "oj": "an", "ol": "ai", "ok": "ao", "oh": "ang",
    "oz": "ei", "of": "en", "og": "eng", "ob": "ou",
    "oa": "a", "oe": "e",
}


def initial_for(key):
    return {"v": "zh", "i": "ch", "u": "sh"}.get(key, key if "a" <= key <= "z" else "")


def final_for(initial, key):
    if key == "s":
        return "iong" if initial in JQX else "ong"
    if key == "r":
        return "uan"
    if key == "t":
        return "ue"
    if key == "p":
        return "un"
    if key == "v":
        if initial in L_N:
            return "v"
        if initial in JQX_Y:
            return "u"
        return "ui"
    if key == "o":
        return "o" if initial in "bpmfw" else "uo"
    if key == "y":
        return "uai" if initial in UAI_INITIALS else "ing"
    if key == "w":
        return "ua" if initial in UA_INITIALS else "ia"
    if key == "d":
        return "uang" if initial in UA_INITIALS else "iang"
    return SINGLE_FINALS.get(key)


def syllable_to_ziranma(syllable):
    """单个全拼音节 → 自然码双拼两键。返回 None 表示无法编码。"""
    syllable = syllable.lower()
    if syllable in ("ng", "n", "m"):  # 特殊音节（嗯/呣）不编码
        return None
    if syllable in ZERO_TABLE.values():
        # 零声母：反查 ZERO_TABLE
        for k, v in ZERO_TABLE.items():
            if v == syllable and len(k) == 2:
                return k
        return None
    # 声母 + 韵母
    initial = ""
    rest = syllable
    for zhin in ("zh", "ch", "sh"):
        if rest.startswith(zhin):
            initial = zhin
            rest = rest[len(zhin):]
            break
    if not initial and len(rest) > 0 and rest[0] in "bpmfdtnlgkhjqxrzcsyw":
        initial = rest[0]
        rest = rest[1:]
    if not initial:
        return None
    final_key = FINAL_KEY.get(rest)
    if final_key is None:
        return None
    k1 = INITIAL_KEY.get(initial, initial)
    return k1 + final_key


def pinyin_to_ziranma(pinyin):
    """全拼音节串（空格分隔）→ 自然码编码串。无法编码的音节用原音节占位。"""
    codes = []
    for syl in pinyin.split():
        code = syllable_to_ziranma(syl)
        codes.append(code if code else syl)
    return " ".join(codes)


# ── Rime dict.yaml 解析 ─────────────────────────────────

def parse_rime_dict(path):
    """解析 Rime dict.yaml → {pinyin: [(word, weight)]}（pinyin 保留空格分隔）"""
    entries = OrderedDict()
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#") or line.startswith("---"):
                continue
            if line.startswith(("name:", "version:", "sort:")) or line == "...":
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            word, pinyin = parts[0], parts[1]
            weight = float(parts[2]) if len(parts) > 2 else 0.0
            # 拼音规范化：小写、压缩多余空格
            pinyin = " ".join(pinyin.strip().lower().split())
            if not pinyin:
                continue
            entries.setdefault(pinyin, []).append((word, weight))
    return entries


def merge_entries(all_entries):
    """合并多文件词条：同拼音同词保留最高权重；拼音保留空格分隔。"""
    merged = {}
    for entries in all_entries:
        for pinyin, items in entries.items():
            bucket = merged.setdefault(pinyin, {})
            for word, weight in items:
                if word not in bucket or weight > bucket[word]:
                    bucket[word] = weight
    result = {}
    for pinyin, word_map in merged.items():
        sorted_words = sorted(word_map.items(), key=lambda kv: -kv[1])
        result[pinyin] = sorted_words  # [(word, weight)]
    return result


def write_outputs(result, out_dir, source, category="common"):
    """生成三个 asset 文件。"""
    import os
    os.makedirs(out_dir, exist_ok=True)

    phrases_path = os.path.join(out_dir, "pinyin_phrases.txt")
    chars_path = os.path.join(out_dir, "pinyin_chars.txt")
    syllables_path = os.path.join(out_dir, "pinyin_syllables.txt")

    phrase_lines = []
    char_buckets = OrderedDict()  # 音节 -> [(字, weight)]
    all_syllables = set()

    # 关键：先去空格聚合成「compact 键 → {词: 权重}」，再统一输出。
    #
    # 不能像早期实现那样对每个带空格拼音各写一行（compact = pinyin.replace(" ", "")）：
    # 不同音节切分可能连写成同一个键——「企鹅」(qi e) 与「切」(qie) 都是 qie，
    # 各写一行会产生重复键，而 app 侧加载是
    #     phrasesByPinyin[key] = phrases.toTypedArray()
    # 后写的那行会把前一行**整体覆盖**，词条静默消失。
    # 表现为：输入 qie（或双拼 qiee）只有「切/且/窃」，打不出「企鹅」；
    # 同类还有 西安(xi an→xian)、饥饿(ji e→jie)、提案(ti an→tian) 等一整类词。
    compact_buckets = OrderedDict()  # compact -> {word: weight}
    for pinyin, items in result.items():
        compact = pinyin.replace(" ", "")
        bucket = compact_buckets.setdefault(compact, {})
        for w, wt in items:
            if w not in bucket or wt > bucket[w]:
                bucket[w] = wt

    for compact, word_weights in compact_buckets.items():
        # 同键内按权重降序，保证高频词排前面（候选顺序）
        words = [w for w, _ in sorted(word_weights.items(), key=lambda kv: -kv[1])]
        phrase_lines.append(f"{compact}\t{'|'.join(words)}")

    # 单字表：仍以「带空格拼音只有一个音节」为准，避免把 2 音节词的字混进单字表
    for pinyin, items in result.items():
        syls = pinyin.split()
        all_syllables.update(syls)
        if len(syls) == 1:
            syl = syls[0]
            char_buckets.setdefault(syl, {})
            for w, wt in items:
                if len(w) == 1:
                    if w not in char_buckets[syl] or wt > char_buckets[syl][w]:
                        char_buckets[syl][w] = wt

    # 写短语
    with open(phrases_path, "w", encoding="utf-8") as f:
        for line in phrase_lines:
            f.write(line + "\n")

    # 写单字
    with open(chars_path, "w", encoding="utf-8") as f:
        for syl in sorted(char_buckets):
            chars = sorted(char_buckets[syl].items(), key=lambda kv: -kv[1])
            f.write(f"{syl}\t{','.join(w for w, _ in chars)}\n")

    # 写音节全集
    with open(syllables_path, "w", encoding="utf-8") as f:
        for syl in sorted(all_syllables):
            f.write(syl + "\n")

    print(f"输出完成: {phrases_path} ({len(phrase_lines)} 键)")
    print(f"         {chars_path} ({len(char_buckets)} 音节)")
    print(f"         {syllables_path} ({len(all_syllables)} 音节)")


def main():
    ap = argparse.ArgumentParser(description="Rime 词库 → CapsWriterIME asset")
    ap.add_argument("inputs", nargs="+", help="Rime dict.yaml 文件")
    ap.add_argument("-o", "--output", default="out", help="输出目录")
    ap.add_argument("-s", "--source", default="unknown", help="数据源标识（如 rime-ice）")
    ap.add_argument("-c", "--category", default="common", help="分类")
    args = ap.parse_args()

    all_entries = [parse_rime_dict(p) for p in args.inputs]
    total = sum(len(e) for e in all_entries)
    merged = merge_entries(all_entries)
    print(f"输入 {len(args.inputs)} 个文件，{total} 键 → 合并后 {len(merged)} 键")
    write_outputs(merged, args.output, args.source, args.category)
    # 自然码转换示例
    print("自然码示例：")
    for pinyin in ["ni hao", "zhong guo", "ren gong zhi neng", "xing zou"]:
        print(f"  {pinyin} → {pinyin_to_ziranma(pinyin)}")


if __name__ == "__main__":
    main()
