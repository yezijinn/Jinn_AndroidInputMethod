# -*- coding: utf-8 -*-
"""为 THUOCL 各分类词表注音，输出输入法拼音词库（每分类独立文件）。"""
import os, re, json
from collections import defaultdict

BASE = r"C:\AI_WORKSPACE\PROJECTS\com.jinn.inputmethod"
RIME = os.path.join(BASE, "docs", "rime-ice", "cn_dicts")
SYLL = os.path.join(BASE, "app", "src", "main", "assets", "pinyin_syllables.txt")
THUOCL_DIR = os.path.join(BASE, "tools", "dict_builder", "THUOCL-master", "data")
OUT_DIR = os.path.join(BASE, "tools", "dict_builder", "THUOCL注音输出")

MAX_WORD_LEN = 8
CN_RE = re.compile(r"^[\u4e00-\u9fff·]+$")

# ---------- 1. 音节表 ----------
with open(SYLL, encoding="utf-8") as f:
    SYLLABLES = set(f.read().split())
SYLL_MAXLEN = max(len(s) for s in SYLLABLES)

def can_segment(key):
    n = len(key)
    reach = [False]*(n+1)
    reach[0] = True
    for i in range(n):
        if not reach[i]:
            continue
        lo = 1
        hi = max(SYLL_MAXLEN, n-i)
        for L in range(1, min(SYLL_MAXLEN, n-i)+1):
            if key[i:i+L] in SYLLABLES:
                reach[i+L] = True
    return reach[n]

# ---------- 2. rime-ice 词库：词 -> set(读音串) ----------
def load_word_dict(*paths):
    d = defaultdict(set)
    for p in paths:
        with open(p, encoding="utf-8") as f:
            for line in f:
                line = line.rstrip("\n")
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) < 2:
                    continue
                word, pinyin = parts[0], parts[1]
                if CN_RE.match(word) and pinyin and not pinyin[0].isupper():
                    d[word].add(pinyin)
    return {k: v for k, v in d.items()}

word_pinyin = load_word_dict(
    os.path.join(RIME, "base.dict.yaml"),
    os.path.join(RIME, "ext.dict.yaml"),
)

# ---------- 3. 8105 字表：字 -> set(读音) ----------
char_pinyin = defaultdict(set)
with open(os.path.join(RIME, "8105.dict.yaml"), encoding="utf-8") as f:
    for line in f:
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        ch, py = parts[0], parts[1]
        if re.match(r"^[\u4e00-\u9fff]$", ch) and py:
            char_pinyin[ch].add(py.strip())

# ---------- 4. 注音核心 ----------
def annotate(word):
    """返回读音串列表（可能多条=多音）。含字母/数字/标点/过长则返回 None。"""
    if len(word) > MAX_WORD_LEN or not CN_RE.match(word):
        return None
    if word in word_pinyin:
        return [r.replace(" ", "") for r in word_pinyin[word]] or None
    # 逐字注音
    readings = []
    for ch in word:
        cps = char_pinyin.get(ch)
        if not cps or len(cps) != 1:
            return None  # 缺字或多音无法判断 -> 丢弃
        readings.append(next(iter(cps)))
    return ["".join(readings)]

def parse_thuocl(path):
    """解析 THUOCL：词 -> 词频（降序保留频次）。"""
    words = {}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line:
            continue
        m = re.match(r"^(.*?)[ \t]+(\d+)\s*$", line)
        if m:
            w, freq = m.group(1), int(m.group(2))
        else:
            w, freq = line, 0
        w = w.strip()
        if w:
            words[w] = max(words.get(w, 0), freq)
    return words

# ---------- 5. 逐分类处理 ----------
def process_category(path):
    words = parse_thuocl(path)
    # 键 -> {词: 频}
    key_words = defaultdict(dict)
    discarded = []      # (word, reason)
    key_parent = {}     # key -> word sample
    for word, freq in words.items():
        reads = annotate(word)
        if reads is None:
            reason = "过长/非纯中文" if (len(word) > MAX_WORD_LEN or not CN_RE.match(word)) else "无法注音(缺字或多音)"
            discarded.append((word, reason))
            continue
        for key in reads:
            key_words[key][word] = freq
    # 输出 & 校验
    out_lines = []
    bad_seg = 0
    dup_bad = 0
    for key, wmap in key_words.items():
        if not can_segment(key):
            bad_seg += 1
            continue
        if not key:
            bad_seg += 1
            continue
        ordered = sorted(wmap.items(), key=lambda kv: (-kv[1], kv[0]))
        words_sorted = [w for w, _ in ordered]
        if len(words_sorted) != len(set(words_sorted)):
            dup_bad += 1
        out_lines.append(key + "\t" + "|".join(words_sorted))
    out_lines.sort()
    return out_lines, {"total": len(words), "valid_keys": len([l for l in out_lines]),
                       "discarded": len(discarded), "bad_seg": bad_seg,
                       "samples": discarded[:10]}

def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    report = {}
    cats = sorted(f for f in os.listdir(THUOCL_DIR) if f.endswith(".txt") and f.startswith("THUOCL_"))
    # 手动指定分类名与展示名
    display = {
        "THUOCL_animal.txt": "动物",
        "THUOCL_caijing.txt": "财经",
        "THUOCL_car.txt": "汽车",
        "THUOCL_chengyu.txt": "成语",
        "THUOCL_diming.txt": "地名",
        "THUOCL_food.txt": "食物",
        "THUOCL_IT.txt": "IT",
        "THUOCL_law.txt": "法律",
        "THUOCL_lishimingren.txt": "历史名人",
        "THUOCL_medical.txt": "医学",
        "THUOCL_poem.txt": "诗词",
    }
    for cat in cats:
        lines, stat = process_category(os.path.join(THUOCL_DIR, cat))
        out_name = cat.replace(".txt", "_注音.txt")
        with open(os.path.join(OUT_DIR, out_name), "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
            if lines:
                f.write("\n")
        report[cat] = {**stat, "display": display.get(cat, cat),
                       "out": os.path.join(OUT_DIR, out_name),
                       "out_lines": len(lines)}
    with open(os.path.join(OUT_DIR, "report_summary.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)

    # 汇总打印
    tot_w = tot_k = tot_d = bad = 0
    print("=== 分类\t总词\t有效键\t丢弃\t切分失败\t输出行 ===")
    for cat, r in report.items():
        tot_w += r["total"]; tot_k += r["valid_keys"]; tot_d += r["discarded"]; bad += r["bad_seg"]
        print(f"{r['display']}\t{r['total']}\t{r['valid_keys']}\t{r['discarded']}\t{r['bad_seg']}\t{r['out_lines']}")
    print(f"TOTAL\t{tot_w}\t{tot_k}\t{tot_d}\t{bad}\t{sum(r['out_lines'] for r in report.values())}")
    print("\n=== 各分类丢弃样例 ===")
    for cat, r in report.items():
        for w, reason in r["samples"]:
            print(f"[{r['display']}] {w} ({reason})")

if __name__ == "__main__":
    main()