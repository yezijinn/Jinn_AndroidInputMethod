#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""诗词库重建（第一步）：从原始诗词库（32.9 万条）中筛出「常用」子集。

判据（按选集命中，去重）：
  · 唐诗三百首 / 宋词三百首 / 千家诗 / 古诗十九首 / 花间集 / 南唐二主词 / 三字经 / 唐诗三百首·蒙学：全量收录命中句
  · 教科书选诗：只取「古诗」文件（剔除 3 个文言文文件）
  · old.教科书：跳过（与教科书选诗同名的旧版）
  · 诗经：只保留家喻户晓的名句（内联清单）
  · 全部经繁→简转换（OpenCC TSCharacters）后与库精确匹配

输入：`docs/new_weighted/shici.txt.bak`（原始 32.9 万条，**不是**瘦身后的产出物）
选集：`docs/诗词选集/huajianji/data/`（chinese-poetry 组织，需先下载，见交接文档第九节）
输出：`docs/诗词选集/_shici_hits.txt`（默认）或 `_shici_hits_loose.txt`（`--loose` 放宽口径）
      + 落盘由 `build_shici_lib.py` 负责（本脚本**不再**写 `new_weighted/shici.txt`）
用法：python tools/dict_builder/filter_shici.py [--loose]
"""
import io
import json
import os
import re
import sys
from collections import OrderedDict, Counter

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SEL = os.path.join(ROOT, "docs", "诗词选集", "huajianji", "data")
SHICI = os.path.join(ROOT, "docs", "new_weighted", "shici.txt.bak")
TSCHARS = os.path.join(ROOT, "docs", "freq", "OpenCC-TSCharacters.txt")

HAN = re.compile(r"^[\u3400-\u4dbf\u4e00-\u9fff\U00020000-\U0003ffff]{2,10}$")
SPLIT = re.compile(r"[，。！？；：、,.;:!?…—「」『』（）()《》\[\]\"'“”‘’·\s]+")

# 繁→简（OpenCC TSCharacters，多候选取首项）
T2S = {}
for l in io.open(TSCHARS, encoding="utf-8"):
    if l.startswith("#") or not l.strip():
        continue
    c = l.rstrip("\n").split("\t")
    if len(c) == 2 and len(c[0]) == 1:
        T2S[c[0]] = c[1].split(" ")[0]


def to_simp(s):
    return "".join(T2S.get(ch, ch) for ch in s)


# 诗经：只留家喻户晓的名句（约 230 词，库中不存在的自动丢弃）
SHIJING = """
关关雎鸠 在河之洲 窈窕淑女 君子好逑 求之不得 寤寐思服 辗转反侧 参差荇菜 琴瑟友之 钟鼓乐之
桃之夭夭 灼灼其华 之子于归 宜其室家 南有乔木 不可休思 汉之广矣 不可泳思 江之永矣 不可方思
我心匪石 不可转也 我心匪席 不可卷也 燕燕于飞 差池其羽 瞻望弗及 泣涕如雨
死生契阔 与子成说 执子之手 与子偕老 凯风自南 吹彼棘心 棘心夭夭 母氏劬劳
静女其姝 俟我于城隅 爱而不见 搔首踟蹰 相鼠有皮 人而无仪
投我以木瓜 报之以琼琚 投我以木桃 报之以琼瑶 投我以木李 报之以琼玖
知我者谓我心忧 不知我者谓我何求 悠悠苍天 此何人哉
君子于役 不知其期 日之夕矣 羊牛下来 如之何勿思
硕鼠硕鼠 无食我黍 三岁贯女 莫我肯顾 逝将去汝 适彼乐土 乐土乐土 爰得我所
绸缪束薪 三星在天 今夕何夕 见此良人
蒹葭苍苍 白露为霜 所谓伊人 在水一方 溯洄从之 道阻且长 溯游从之 宛在水中央
蒹葭萋萋 白露未晞 所谓伊人 在水之湄 道阻且跻 宛在水中坻
蒹葭采采 白露未已 所谓伊人 在水之涘 道阻且右 宛在水中沚
岂曰无衣 与子同袍 王于兴师 修我戈矛 与子同仇 与子同泽 与子偕作 与子同裳 修我甲兵 与子偕行
月出皎兮 佼人僚兮 舒窈纠兮 劳心悄兮 七月流火 九月授衣
呦呦鹿鸣 食野之苹 我有嘉宾 鼓瑟吹笙 吹笙鼓簧 承筐是将 人之好我 示我周行 食野之蒿 德音孔昭 视民不恌
常棣之华 鄂不韡韡 凡今之人 莫如兄弟 死丧之威 兄弟孔怀 兄弟阋于墙 外御其务 妻子好合 如鼓瑟琴 宜尔室家 乐尔妻帑
伐木丁丁 鸟鸣嘤嘤 出自幽谷 迁于乔木 嘤其鸣矣 求其友声 相彼鸟矣 犹求友声
如月之恒 如日之升 如南山之寿 不骞不崩 如松柏之茂
采薇采薇 薇亦作止 曰归曰归 岁亦莫止 靡室靡家 猃狁之故 不遑启居 忧心烈烈 载饥载渴
昔我往矣 杨柳依依 今我来思 雨雪霏霏 行道迟迟 载渴载饥 我心伤悲 莫知我哀
喓喓草虫 趯趯阜螽 未见君子 忧心忡忡 既见君子 我心则降
春日迟迟 卉木萋萋 仓庚喈喈 采蘩祁祁 鸿雁于飞 肃肃其羽 之子于征 劬劳于野 爰及矜人 哀此鳏寡
夜如何其 夜未央 庭燎之光 君子至止 鸾声将将
鹤鸣于九皋 声闻于野 鱼潜在渊 或在于渚 他山之石 可以为错 声闻于天 他山之石 可以攻玉
皎皎白驹 食我场苗 絷之维之 以永今朝 於焉逍遥 在彼空谷 生刍一束 其人如玉
秩秩斯干 幽幽南山 如竹苞矣 如松茂矣 兄及弟矣 式相好矣
如跂斯翼 如矢斯棘 如鸟斯革 如翚斯飞 乃生男子 载寝之床 载弄之璋 乃生女子 载寝之地 载弄之瓦
高山仰止 景行行止 战战兢兢 如临深渊 如履薄冰 靡不有初 鲜克有终 一日不见 如三秋兮
风雨如晦 鸡鸣不已 既见君子 云胡不喜 巧笑倩兮 美目盼兮
手如柔荑 肤如凝脂 领如蝤蛴 齿如瓠犀 如切如磋 如琢如磨 中心藏之 何日忘之
言之者无罪 闻之者足以戒
"""

SHIJING_SET = set(SHIJING.split())


def clean_words(text):
    out = []
    for seg in SPLIT.split(text):
        seg = seg.strip()
        if HAN.match(seg):
            out.append(seg)
    return out


# 库
shici = {}
for ln in io.open(SHICI, encoding="utf-8"):
    c = ln.rstrip("\n").split("\t")
    if c:
        shici[c[0]] = ln.rstrip("\n")

LOOSE = "--loose" in sys.argv      # 放宽：诗经全收 + 教材含文言文
SKIP = {"old.教科书"}
TEXTBOOK_DROP = () if LOOSE else ("文言文",)

hits = OrderedDict()
stats = {}
for sub in sorted(os.listdir(SEL)):
    d = os.path.join(SEL, sub)
    if not os.path.isdir(d) or sub in SKIP:
        continue
    got = set()

    def walk(o):
        if isinstance(o, list):
            for x in o:
                walk(x)
        elif isinstance(o, dict):
            for k in ("title", "paragraphs"):
                v = o.get(k)
                if isinstance(v, str):
                    for w in clean_words(to_simp(v)):
                        if w in shici:
                            got.add(w)
                elif isinstance(v, list):
                    for p in v:
                        if isinstance(p, str):
                            for w in clean_words(to_simp(p)):
                                if w in shici:
                                    got.add(w)
                        else:
                            walk(p)

    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".json"):
            continue
        if sub == "教科书选诗" and any(k in fn for k in TEXTBOOK_DROP):
            continue
        walk(json.load(io.open(os.path.join(d, fn), encoding="utf-8")))
    if sub == "诗经" and not LOOSE:
        got = {w for w in got if w in SHIJING_SET}
    stats[sub] = len(got)
    for w in got:
        hits.setdefault(w, sub)

print("== 选集命中（繁简转换后） ==")
for k, v in stats.items():
    print("  %-16s %6d" % (k, v))
print("合计（去重）：%d" % len(hits))
print("词长分布：", dict(sorted(Counter(len(w) for w in hits).items())))

# 导出命中集（供 build_shici_lib.py 使用）。
# ⚠ 本脚本**不写** docs/new_weighted/shici.txt：那里是重建后的成品库，误覆盖会把 2.16 万条
# 退回成筛选口径的中间产物（历史遗留的 --write 分支已删除）。
hit_file = os.path.join(ROOT, "docs", "诗词选集",
                        "_shici_hits_loose.txt" if LOOSE else "_shici_hits.txt")
with io.open(hit_file, "w", encoding="utf-8", newline="\n") as f:
    for w in sorted(hits):
        f.write(w + "\n")
print("命中集 → %s（落盘由 build_shici_lib.py 负责）" % hit_file)

# 诗经保留清单也随本脚本产出：build_shici_lib.py 的输入之一（此前靠一次性命令导出，
# 重跑本脚本时不会刷新 —— 会与内联的 SHIJING 清单脱节，属复现性缺口，2026-10-10 补齐）。
mingju = os.path.join(ROOT, "docs", "诗词选集", "_shijing_mingju.txt")
with io.open(mingju, "w", encoding="utf-8", newline="\n") as f:
    for w in sorted(SHIJING_SET):
        f.write(w + "\n")
print("诗经保留清单 → %s（%d 词）" % (mingju, len(SHIJING_SET)))
