# -*- coding: utf-8 -*-
"""把万象拼音 shijian.lua 的 LUNAR_DATA 抽成 2000–2050 的紧凑 Int 表（LunarDate.kt 的数据源）。

用法（仓库根目录）：python tools/dict_builder/gen_lunar_table.py
  · 标准输出 = 可直接贴进 `LunarDate.kt` 的 Kotlin 数组字面量
  · 标准错误 = 锚点校验过程（春节 / 中秋 / 闰月 / 跨年月首），任一不符即 assert 失败

要扩范围（例如 1950–2050）：改 YEARS 即可，源表本身覆盖 1899–2113。

万象的数据格式（每项 7 个十六进制字符）：
  [1..3] 12 个月大小位（正月在最高位；1 = 30 天，0 = 29 天）
  [4]    闰月（0 = 无）
  [5]    闰月大小（1 = 30 天）
  [6..7] 春节的 MMDD（十进制打包，如 217 → 02 月 17 日）
索引：LUNAR_DATA[year - 1898]（第 1 项是 1899 农历年）。

本脚本把 2000–2050 重新编码为 26 位 Int（闰月 4 位 + 12 位月大小 + 1 位闰月大小 + 春节月 4 位 + 春节日 5 位），
并打印 Kotlin 数组字面量 + 锚点自校验结果。
"""
import re
import sys

SRC = "docs/rime-wanxiang-wanxiang/lua/wanxiang/shijian.lua"
YEARS = range(2000, 2051)

text = open(SRC, encoding="utf-8").read()
block = re.search(r"local LUNAR_DATA = \{(.*?)\}", text, re.S).group(1)
data = re.findall(r'"([0-9A-Fa-f]{7})"', block)
print("诊断: 匹配数=%d, 前5=%s, 后3=%s" % (len(data), data[:5], data[-3:]), file=sys.stderr)
# 索引基准校验：index = year - 1898，打印 2018–2030 的春节 MMDD
known = {2018: 216, 2019: 205, 2020: 125, 2021: 212, 2022: 201, 2023: 122, 2024: 210,
         2025: 129, 2026: 217, 2027: 206, 2028: 126, 2029: 213, 2030: 203}
for y in sorted(known):
    entry = data[y - 1899]
    mmdd = int(entry[5:7], 16)
    flag = "√" if mmdd == known[y] else "✗ 期望 %d" % known[y]
    print("  %d %s 春节=%04d 闰月=%s" % (y, flag, mmdd, entry[3]), file=sys.stderr)
    assert mmdd == known[y], y
assert len(data) >= 203, len(data)


def decode(entry):
    """→ (month_bits, leap_month, leap30, mmdd)

    注意第 4/5 个字符的含义与直觉相反（对齐万象 Analyze + get_lunar_year_info 的用法）：
      [4] 闰月大小位（1 = 30 天）
      [5] 闰月月份（0 = 无闰月）
    """
    month_bits = int(entry[0:3], 16)
    leap30 = int(entry[3], 16)
    leap_month = int(entry[4], 16)
    mmdd = int(entry[5:7], 16)
    return month_bits, leap_month, leap30, mmdd


def encode(month_bits, leap_month, leap30, mmdd):
    assert 0 <= leap_month <= 15 and leap30 in (0, 1)
    month, day = mmdd // 100, mmdd % 100
    assert 1 <= month <= 12 and 1 <= day <= 31, mmdd
    return (leap_month << 22) | (month_bits << 10) | (leap30 << 9) | (month << 5) | day


# 锚点：公历春节（公历年份 → 月日）
SPRING = {2000: (2, 5), 2020: (1, 25), 2023: (1, 22), 2024: (2, 10), 2025: (1, 29), 2026: (2, 17), 2050: (1, 23)}
# 锚点：闰月（有闰月的年份 → 闰几月）
LEAPS = {2001: 4, 2004: 2, 2006: 7, 2009: 5, 2012: 4, 2017: 6, 2020: 4, 2023: 2, 2025: 6, 2028: 5, 2033: 11, 2036: 6, 2047: 5, 2050: 3}

enc = []
for y in YEARS:
    month_bits, leap_month, leap30, mmdd = decode(data[y - 1899])
    if y in SPRING:
        m, d = SPRING[y]
        assert mmdd == m * 100 + d, "春节锚点不符 %d: %d != %d" % (y, mmdd, m * 100 + d)
    if y in LEAPS:
        assert leap_month == LEAPS[y], "闰月锚点不符 %d: %d != %d" % (y, leap_month, LEAPS[y])
    enc.append(encode(month_bits, leap_month, leap30, mmdd))

print("// 校验通过：%d 年春节锚点、%d 年闰月锚点" % (len(SPRING), len(LEAPS)), file=sys.stderr)
print("内部函数校验：", file=sys.stderr)
for y in (2026, 2024, 2025):
    mb, lm, l30, mmdd = decode(data[y - 1898])
    print("  %d: 12位=%03X 闰月=%d 闰月30天=%d 春节=%04d" % (y, mb, lm, l30, mmdd), file=sys.stderr)


def month_days(v, month):
    return 29 + ((v >> (22 - month)) & 1)   # 月 m 的位：正月在 bit21，逐月往低位走


def to_gregorian_ordinal(y, m, d):
    days = [31, 29 if (y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)) else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
    return sum(days[:m - 1]) + d


def lunar_of(y, m, d):
    """→ (农历年, 月, 日, 是否闰月)，与 Kotlin 实现同算法（用于交叉验证）"""
    def info(year):
        v = enc[year - 2000]
        lm = (v >> 22) & 0xF
        mm, dd = (v >> 5) & 0xF, v & 0x1F
        months = []
        for mo in range(1, 13):
            months.append((mo, False, month_days(v, mo)))
            if lm == mo:
                months.append((mo, True, 29 + ((v >> 9) & 1)))
        return (mm, dd), months
    ly = y
    (ny_m, ny_d), months = info(ly)
    if to_gregorian_ordinal(y, m, d) < to_gregorian_ordinal(y, ny_m, ny_d):
        ly -= 1
        if ly < 2000:
            return None
        (ny_m, ny_d), months = info(ly)
    offset = to_gregorian_ordinal(y, m, d) - to_gregorian_ordinal(ly, ny_m, ny_d)
    for mo, is_leap, days in months:
        if offset < days:
            return ly, mo, offset + 1, is_leap
        offset -= days
    return None


# 交叉验证：几个公开已知的农历日期（公历 → 农历）
CASES = {
    (2026, 2, 17): (2026, 1, 1, False),    # 2026 春节
    (2026, 9, 25): (2026, 8, 15, False),   # 2026 中秋（八月十五）
    (2025, 10, 6): (2025, 8, 15, False),   # 2025 中秋
    (2024, 9, 17): (2024, 8, 15, False),   # 2024 中秋
    (2020, 5, 23): (2020, 4, 1, True),     # 2020 闰四月初一
    (2023, 3, 22): (2023, 2, 1, True),     # 2023 闰二月初一
    (2026, 9, 24): None,                   # 今天（只打印，不断言）
}
for (gy, gm, gd), expect in CASES.items():
    got = lunar_of(gy, gm, gd)
    tag = "√" if expect is None or got == expect else "✗"
    print("  %s %d-%02d-%02d → %s%s" % (tag, gy, gm, gd, got, "" if expect is None or got == expect else " 期望 %s" % (expect,), ), file=sys.stderr)
    if expect is not None:
        assert got == expect, (gy, gm, gd, got, expect)

lines = []
for i in range(0, len(enc), 6):
    chunk = enc[i:i + 6]
    lines.append("        " + ", ".join("0x%07X" % v for v in chunk) + ",")
print("\n".join(lines))
