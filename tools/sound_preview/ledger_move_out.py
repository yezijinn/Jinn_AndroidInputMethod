"""把指定编号的条目块与索引行从台账里移出（修好后移出正文，改法写 更新日志.md）。

用法：python ledger_move_out.py 904 905 …
只做「删块 + 删行」，计数与页眉交给 ledger_counts.py。
"""
import io
import re
import sys

ROOT = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/'
IDS = [int(a) for a in sys.argv[1:]]
if not IDS:
    raise SystemExit('未给编号：python ledger_move_out.py 904 905 …')


def read(rel):
    return io.open(ROOT + rel, encoding='utf-8').read()


def write(rel, text):
    with io.open(ROOT + rel, 'w', encoding='utf-8', newline='') as f:
        f.write(text)


# ── 块：从 `### L-NNN ·` 到下一个同级标题（或文件末尾）──
low = read('.wwlia-handoff/ledger/low.md')
removed = []
for n in IDS:
    pat = re.compile(r'(?ms)^#{3,4} L-%d · .*?(?=^#{2,4} (?:L|X|B)-\d+ ·|\Z)' % n)
    m = pat.search(low)
    if m:
        # 按**下标**切片而不是 `replace(原文)`：文件末尾那一块的尾随空白与构造出的
        # sheet 不可能逐字相同，`replace` 会静默不生效（末块删不掉的坑就出在这里）
        low = low[:m.start()] + low[m.end():]
        removed.append(n)
    else:
        print('⚠ 低风险册里没有 L-%d 的块' % n)
low = re.sub(r'\n{4,}', '\n\n\n', low)
write('.wwlia-handoff/ledger/low.md', low)
print('块移出：%s' % ', '.join('L-%d' % n for n in removed))

# ── 索引行 ──
idx = read('.wwlia-handoff/ledger/index.md')
for n in removed:
    idx = re.sub(r'(?m)^\| L-%d \|.*\n' % n, '', idx)
write('.wwlia-handoff/ledger/index.md', idx)
print('索引行移出：%d 行' % len(removed))
