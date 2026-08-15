# -*- coding: utf-8 -*-
"""对比新旧词库覆盖情况"""
import io, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')

out = r'C:\Users\ADMINI~1\AppData\Local\Temp\opencode\rime-ice\out2'
cur = r'E:\OpenCode\CapsWriterIME\app\src\main\assets'

def load_keys(path):
    s = set()
    with open(path, encoding='utf-8') as f:
        for line in f:
            if line.strip():
                tab = line.find('\t')
                if tab > 0:
                    s.add(line[:tab])
    return s

new_k = load_keys(out + r'\pinyin_phrases.txt')
old_k = load_keys(cur + r'\pinyin_phrases.txt')
print('新词库键:', len(new_k), ' 旧词库键:', len(old_k))
print('新增键:', len(new_k - old_k), ' 旧有但新无:', len(old_k - new_k))
print('新增示例:', list(new_k - old_k)[:8])

with open(out + r'\pinyin_phrases.txt', encoding='utf-8') as f:
    data = {}
    for line in f:
        tab = line.find('\t')
        if tab > 0:
            data[line[:tab]] = line[tab + 1:]

for k in ['nihao', 'zhongguo', 'yixinyiyi', 'houlaijushang',
          'bushisanqiershiyi', 'sangechoupijiang', 'qianliyiti', 'baibuting']:
    v = data.get(k, 'MISSING')
    print(k, ':', v[:40] if v != 'MISSING' else v)
