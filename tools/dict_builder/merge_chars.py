# -*- coding: utf-8 -*-
"""
合并新旧单字表与音节表（新词库为主体 + 旧词库补齐）。

背景：新词库（雾凇+白霜）的单字表只含 8105 常用字，缺语气词音节
（m/n/ng/hm/hng）且 lue/nue 写作 lve/nve（双拼转换输出标准 ASCII）。
旧词库单字表覆盖更广（含生僻/繁体字）。两者并集得到完整覆盖：

  - 音节表 = 新 ∪ 旧（去重排序）
  - 每个音节的字 = 新字在前（常用优先） + 旧独有字补齐（保序去重）

用法：python merge_chars.py <new_chars> <new_syllables> <old_chars> <old_syllables> <out_dir>
"""
import sys
import io
import os

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')


def load_lines(path):
    with open(path, encoding='utf-8') as f:
        return [l.rstrip() for l in f if l.strip()]


def parse_char_map(lines):
    m = {}
    for l in lines:
        if '\t' not in l:
            continue
        k, v = l.split('\t', 1)
        m[k] = v
    return m


def main():
    if len(sys.argv) != 6:
        print('用法: merge_chars.py <new_chars> <new_syllables> <old_chars> <old_syllables> <out_dir>')
        sys.exit(1)
    new_chars_p, new_syl_p, old_chars_p, old_syl_p, out_dir = sys.argv[1:]

    new_chars = parse_char_map(load_lines(new_chars_p))
    old_chars = parse_char_map(load_lines(old_chars_p))
    new_syl = set(load_lines(new_syl_p))
    old_syl = set(load_lines(old_syl_p))

    all_syl = sorted(new_syl | old_syl)
    print('音节: 新%d 旧%d → 合并%d' % (len(new_syl), len(old_syl), len(all_syl)))

    # 合并每音节字：新字在前，旧独有字补后
    out_chars = []
    total_new, total_old_only = 0, 0
    for s in all_syl:
        nw = new_chars.get(s, '')
        ow = old_chars.get(s, '')
        new_list = [c for c in nw.split(',') if c] if nw else []
        old_list = [c for c in ow.split(',') if c] if ow else []
        merged = list(new_list)
        seen = set(new_list)
        for c in old_list:
            if c not in seen:
                merged.append(c)
                seen.add(c)
        total_new += len(new_list)
        total_old_only += len(merged) - len(new_list)
        out_chars.append('%s\t%s' % (s, ','.join(merged)))

    print('单字: 新%d 旧独有%d → 合计%d' % (
        total_new, total_old_only, total_new + total_old_only))

    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, 'pinyin_chars.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(out_chars) + '\n')
    with open(os.path.join(out_dir, 'pinyin_syllables.txt'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(all_syl) + '\n')
    print('写出 → %s' % out_dir)


if __name__ == '__main__':
    main()
