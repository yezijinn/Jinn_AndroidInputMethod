# -*- coding: utf-8 -*-
r"""
处理外部/本地词源，生成分类补充词库：
  成语库（wainshine ChengYu_Corpus 5W）-> docs/chengyu_ime_dict.txt
  网络黑话（chinese-internet-jargon）-> docs/jargon_ime_dict.txt
  中文人名（本地 120W）-> docs/name_corpus_ime_dict.txt
  古代人名（本地 25W）-> docs/ancient_name_ime_dict.txt

规则：只留纯汉字，长度 2-8，pypinyin 注音，与主库去重由后续合并脚本负责。
"""
import io, os, re, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
from pypinyin import lazy_pinyin

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, "docs")
HAN = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff]+$")
MAXLEN = 8

def note(words):
    """words: list[str] -> list[(词,拼音)]"""
    out = []
    for w in words:
        if not HAN.match(w): continue
        if len(w) < 2 or len(w) > MAXLEN: continue
        py = "".join(lazy_pinyin(w))
        if not py.isalpha(): continue
        out.append((w, py))
    return out

def write_dict(words, out_name, label):
    uniq = sorted(set(words))
    pairs = note(uniq)
    out = os.path.join(DOCS, out_name + "_ime_dict.txt")
    with io.open(out, "w", encoding="utf-8", newline="\n") as f:
        for w, py in pairs:
            f.write("%s %s\n" % (w, py))
    print("%-14s 输入 %7d  唯一 %7d  产出 %6d" % (label, len(words), len(uniq), len(pairs)))

# 1. 成语库
cy = []
for l in io.open("tools/_chengyu.txt", encoding="utf-8").read().splitlines():
    l = l.strip().lstrip("\ufeff")
    if not l or l.startswith("By@") or re.match(r"^\d{4}\.", l): continue
    cy.append(l)
write_dict(cy, "chengyu", "成语库")

# 2. 网络黑话
txt = io.open("tools/_jargon.md", encoding="utf-8").read()
blocks = re.findall(r"```[a-z]*\n(.*?)```", txt, re.S)
jg = []
for b in blocks:
    for p in re.split(r"[,\n，、]+", b):
        p = p.strip()
        if p and HAN.fullmatch(p):
            jg.append(p)
write_dict(jg, "jargon", "网络黑话")

# 3. 中文人名（跳过头部 3 行）
nm = []
for l in io.open(os.path.join(DOCS, "freq", "Chinese_Names_Corpus（120W）.txt"), encoding="utf-8").read().splitlines():
    l = l.strip().lstrip("\ufeff")
    if not l or l.startswith("By@") or re.match(r"^\d{4}\.", l): continue
    nm.append(l)
write_dict(nm, "name_corpus", "中文人名")

# 4. 古代人名
an = []
for l in io.open(os.path.join(DOCS, "freq", "Ancient_Names_Corpus（25W）.txt"), encoding="utf-8").read().splitlines():
    l = l.strip().lstrip("\ufeff")
    if not l or l.startswith("By@") or re.match(r"^\d{4}\.", l): continue
    an.append(l)
write_dict(an, "ancient_name", "古代人名")
