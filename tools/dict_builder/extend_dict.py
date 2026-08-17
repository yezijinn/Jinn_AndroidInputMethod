#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CapsWriterIME 词库扩充：THUOCL → 自动注音 → 合并进现有词库。

用法：
    python extend_dict.py <现有 pinyin_phrases.txt> <THUOCL 目录> -o <输出目录>

流程：
    1. 读现有 pinyin_phrases.txt（compact拼音<TAB>词1|词2）
    2. 读 THUOCL 各分类 txt（词<TAB>频率，UTF-8）
    3. 用 pypinyin 自动注音（词组模式，多音字按词）
    4. 合并：同键追加新词（按频率排序），已有词不重复
    5. 输出新 pinyin_phrases.txt（保留原格式）

许可证：THUOCL = MIT，pypinyin = MIT，均可并入。
"""
import os
import sys
from collections import OrderedDict

from pypinyin import lazy_pinyin, Style

def load_existing(path):
    """现有词库: compact_pinyin -> [words]（顺序即频率降序）"""
    existing = OrderedDict()
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not line or '\t' not in line:
                continue
            key, words = line.split('\t', 1)
            existing[key] = words.split('|')
    return existing

def pinyin_of(word):
    """词组自动注音：pypinyin 词组模式（多音字按词），返回紧凑拼音（无空格）"""
    syls = lazy_pinyin(word, style=Style.NORMAL)
    return ''.join(syls)

def load_thuocl(dir_path):
    """THUOCL: word -> freq（多文件，同词取最高频）"""
    words = {}
    for fn in os.listdir(dir_path):
        if not fn.startswith('THUOCL_'):
            continue
        path = os.path.join(dir_path, fn)
        with open(path, encoding='utf-8-sig') as f:
            for line in f:
                line = line.rstrip('\r\n')
                if not line or '\t' not in line:
                    continue
                parts = line.split('\t')
                word = parts[0].strip()
                # 频率提取数字（THUOCL 部分条目带异常字符）
                freq = 0.0
                if len(parts) > 1:
                    digits = ''.join(ch for ch in parts[1] if ch.isdigit())
                    freq = float(digits) if digits else 0.0
                if not word:
                    continue
                # 只保留中文词（含字母/数字的跳过——注音不准且可能污染）
                if not all('\u4e00' <= ch <= '\u9fff' for ch in word):
                    continue
                if word not in words or freq > words[word]:
                    words[word] = freq
    return words

def main():
    if len(sys.argv) < 3:
        print('用法: extend_dict.py <现有phrases.txt> <THUOCL目录> -o <输出>')
        sys.exit(1)
    src = sys.argv[1]
    thuocl_dir = sys.argv[2]
    out_dir = sys.argv[sys.argv.index('-o') + 1] if '-o' in sys.argv else 'out'
    os.makedirs(out_dir, exist_ok=True)

    print('加载现有词库...')
    existing = load_existing(src)
    print(f'  现有 {len(existing)} 键')

    print('加载 THUOCL...')
    thuocl = load_thuocl(thuocl_dir)
    print(f'  THUOCL {len(thuocl)} 词（中文）')

    print('自动注音 + 合并...')
    added_keys = 0
    added_words = 0
    for word, freq in thuocl.items():
        key = pinyin_of(word)
        if not key:
            continue
        bucket = existing.setdefault(key, [])
        if word not in bucket:
            bucket.append(word)
            added_keys += 1
            added_words += 1

    print(f'  新增词条 {added_words}（{added_keys} 键）')

    out_path = os.path.join(out_dir, 'pinyin_phrases.txt')
    with open(out_path, 'w', encoding='utf-8') as f:
        for key, words in existing.items():
            if words:
                f.write(f'{key}\t{"|".join(words)}\n')
    print(f'输出: {out_path}（{len(existing)} 键）')

if __name__ == '__main__':
    main()
