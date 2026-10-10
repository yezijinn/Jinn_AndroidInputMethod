# -*- coding: utf-8 -*-
r"""
把万象拼音 rime-wanxiang 的分类词库转成本项目格式。

输入：docs/rime-wanxiang-wanxiang/dicts/<name>.dict.yaml
      格式：正文（`...` 之后）每行 `词<TAB>带调拼音<TAB>词频`
输出：docs/<name>_ime_dict.txt
      格式：`词 拼音`（拼音无调、无空格，ü→v），与 games_ime_dict.txt 同格式

过滤：
  · 只留纯汉字（CJK 统一表意文字，含扩展 A/B~F）
  · 拼音须为合法音节拼接（对照 docs/dict_review/音节表.txt）
  · 同词去重（保留最高词频）
"""
import io, os, re, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, "docs")
WX = os.path.join(DOCS, "rime-wanxiang-wanxiang", "dicts")
HAN = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff\U00020000-\U0003ffff]+$")

TONE = {
 'ā':'a','á':'a','ǎ':'a','à':'a','ē':'e','é':'e','ě':'e','è':'e',
 'ī':'i','í':'i','ǐ':'i','ì':'i','ō':'o','ó':'o','ǒ':'o','ò':'o',
 'ū':'u','ú':'u','ǔ':'u','ù':'u','ǖ':'v','ǘ':'v','ǚ':'v','ǜ':'v','ü':'v',
 'ń':'n','ň':'n','ǹ':'n','ḿ':'m',
}
def strip_tone(s):
    return "".join(TONE.get(ch, ch) for ch in s)

# 音节表
syls = set()
for l in io.open(os.path.join(DOCS, "dict_review", "音节表.txt"), encoding="utf-8"):
    l = l.strip()
    if l: syls.add(l)

def valid_pinyin(py):
    """拼音串能否切成合法音节（贪心 + 回退）"""
    n = len(py)
    memo = {}
    def dfs(i):
        if i == n: return True
        if i in memo: return memo[i]
        for j in range(min(n, i+6), i, -1):
            if py[i:j] in syls and dfs(j):
                memo[i] = True
                return True
        memo[i] = False
        return False
    return dfs(0)

def convert(name, out_name):
    src = os.path.join(WX, name + ".dict.yaml")
    if not os.path.isfile(src):
        print("缺失", src); return
    started = False
    best = {}   # 词 -> (freq, py)
    tot = bad_han = bad_py = 0
    for line in io.open(src, encoding="utf-8"):
        if not started:
            if line.startswith("..."): started = True
            continue
        c = line.rstrip("\n").split("\t")
        if len(c) < 2: continue
        w = c[0].strip()
        if not w: continue
        tot += 1
        if not HAN.match(w):
            bad_han += 1; continue
        py = strip_tone(c[1].strip()).replace(" ", "").replace("'", "")
        if not py.isalpha() or not valid_pinyin(py):
            bad_py += 1; continue
        f = 0
        if len(c) >= 3:
            try: f = int(float(c[2]))
            except: f = 0
        if w not in best or f > best[w][0]:
            best[w] = (f, py)
    out = os.path.join(DOCS, out_name + "_ime_dict.txt")
    with io.open(out, "w", encoding="utf-8", newline="\n") as f:
        for w in sorted(best):
            f.write("%s %s\n" % (w, best[w][1]))
    print("%-12s 总 %7d -> 保留 %6d （非汉字 %d / 坏拼音 %d）" % (name, tot, len(best), bad_han, bad_py))

JOBS = [
    ("renming", "renming"),
    ("diming", "diming"),
    ("yiren", "yiren"),
    ("mingren", "mingren"),
    ("shici", "shici"),
    ("wuzhong", "wuzhong"),
    ("yixue", "yixue"),
    ("huaxue", "huaxue"),
    ("yaopin", "yaopin"),
    ("lianxiang", "lianxiang"),
]
for n, o in JOBS:
    convert(n, o)
