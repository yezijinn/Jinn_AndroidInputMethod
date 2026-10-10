#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""生成网络热词库（docs/net_hot_ime_dict.txt）。

来源：
  1. 官方榜单（2025 年度，国家语言资源监测中心 / 咬文嚼字 / 语言文字周报 / 汉语盘点 / B站弹幕）
  2. 历年网络流行语整理（2020-2025 + 经典老词）
  3. 早期种子词

格式：词<空格>拼音；只留纯汉字 2-8 字（pypinyin 注音）。
用法：python tools/dict_builder/gen_net_hot.py
"""
import io, os, re, sys
from pypinyin import lazy_pinyin
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = os.path.join(ROOT, "docs")
HAN = re.compile(r"^[\u4e00-\u9fff]+$")

# ── 1. 官方 2025 年度热词 ──
OFFICIAL = """十五五规划 九三阅兵 全球治理倡议 深度求索 人形机器人 苏超 票根经济 育儿补贴 科学素养
网络生态治理 杭州六小龙 现代化人民城市 跨境支付通 对等关税 新大众文艺 轻体 拉布布 敬自己一杯
助我破鼎 村咖 来财 浪浪山小妖怪 赛博对账 数字游民 活人感 主理人 邪修 魔丸 灵珠 高雅人士
误闯天家 当个事儿办 地缘政治 致敬"""

# ── 2. 历年网络流行语 ──
POP = """绝绝子 破防 内卷 躺平 社死 显眼包 遥遥领先 搭子 多巴胺 情绪价值 新质生产力 具身智能
低空经济 谷子经济 干饭 干饭人 打工人 尾款人 凡尔赛 柠檬精 奥利给 集美 摆烂 发疯文学 电子榨菜
公主请 听劝 偷感 班味 松弛感 银发力量 小孩哥 小孩姐 摸鱼 内耗 美拉德 薄荷曼波 特种兵旅游
大冤种 退退退 老六 吃瓜 嘴替 尊嘟假嘟 格局打开 蚌埠住了 拿捏 乐子 乐子人 顶流 出圈 破圈
种草 拔草 踩雷 避雷 安利 真香 打脸 社牛 社恐 画大饼 躺赢 卷王 鸡娃 佛系 爷青回 爷青结
回忆杀 名场面 有生之年 早八人 芜湖起飞 栓扣 老铁 家人们 无语子 针不戳 爱了爱了 蹲后续 刷屏
霸屏 拉黑 取关 点赞 弹幕 催更 弃坑 入坑 翻车 塌房 空降 上新 补货 断货 爆款 单品 联名 平替
山寨 拼单 团购 秒杀 砍价 拼团 满减 优惠券 红包 集赞 集五福 抢红包 转发抽奖 中奖 白嫖 薅羊毛
买它 上车 下车 给力 雷人 打酱油 神马都是浮云 羡慕嫉妒恨 你懂的 伤不起 坑爹 悲催 蛋疼 吐槽
灌水 潜水 冒泡 沙发 路过 马克 留名 前排 围观 淡定 浮云 杯具 鸭梨 香菇 蓝瘦 难受 友谊的小船
套路 城市套路深 我要回农村 皮皮虾我们走 扎心了老铁 为所欲为 了解一下 安排 硬核 干货 姿势
涨姿势 打call 真香警告 酸了 慕了 裂开 破大防 醍醐灌顶 一整个 绝了 封神 天花板 名场面 翻红
翻唱 魔改 鬼畜 二创 同人 爬墙 流量 上热搜 霸榜 屠榜 登顶 断层 顶配 满配 低配 高配 画饼 背锅
甩锅 接盘 兜底 赋能 抓手 闭环 落地 对齐 复盘 拉通 雨女无瓜 小丑竟是我自己 我看不懂但我大受震撼
重要的事情说三遍 我裂开了 我真的会谢 原地封神 麻了 纯纯 绷不住了 爆火 话痨 摸鱼大师 上班摸鱼
下班跑路 干饭魂 原地起飞 破防了 家人们谁懂啊 谁懂啊 我哭死 我没了 蹲一个 顶上去 上分 掉分
带飞 上号 冲浪 打卡 蹲点 关注 转发 评论 爆红 首发 现货 高仿 拼手气 手气最佳 点赞抽奖 开奖
空欢喜 冲了 秒了 抢了 蹲守 躺平主义"""

def main():
    words = (OFFICIAL + " " + POP).split()
    seen, kept = set(), []
    for w in words:
        if not w or w in seen:
            continue
        seen.add(w)
        if HAN.match(w) and 2 <= len(w) <= 8:
            kept.append(w)
    kept.sort()
    out = os.path.join(DOCS, "net_hot_ime_dict.txt")
    with io.open(out, "w", encoding="utf-8", newline="\n") as f:
        for w in kept:
            f.write("%s %s\n" % (w, "".join(lazy_pinyin(w))))
    print("网络热词：%d 条 -> %s" % (len(kept), os.path.basename(out)))

if __name__ == "__main__":
    main()
