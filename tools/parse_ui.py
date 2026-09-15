# -*- coding: utf-8 -*-
"""解析 uiautomator dump 的 UI XML，打印 edit_ime 输入框与关键节点坐标"""
import re
import sys
import io

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

def parse(path):
    with open(path, encoding='utf-8', errors='replace') as f:
        data = f.read()
    print('== edit_ime / btn_ime 节点 ==')
    for m in re.finditer(
        r'<node[^>]*resource-id="([^"]*(?:edit_ime|btn_ime|text_ime)[^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        data,
    ):
        print(f"  {m.group(1)} bounds=[{m.group(2)},{m.group(3)}][{m.group(4)},{m.group(5)}]")
    print('== 可见 text 节点 ==')
    seen = set()
    for m in re.finditer(
        r'<node[^>]*text="([^"]{1,30})"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
        data,
    ):
        t = m.group(1)
        if t not in seen:
            seen.add(t)
            print(f"  '{t}' bounds=[{m.group(2)},{m.group(3)}][{m.group(4)},{m.group(5)}]")

if __name__ == '__main__':
    parse(sys.argv[1])
