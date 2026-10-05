"""把台账各处声明的计数，按各分册的实际块数机械重算并写回。

守卫 `DocsReferenceTest.台账计数须与正文一致` 跨「入口 + 七个分册」对拍这些数字：
索引行数 / 详情块数 / X 块数 / B 块数，以及 front-matter 的四个字段与 `0.5` 的最大编号。
手改容易漏，这里统一按实际内容重算。

两处曾漏掉、由守卫当场报红后补上：
  - 索引册的「N 条在册」与中/低风险册的「N 条」节标题（原先只改 excluded / history 两册的页眉）；
  - 编号上界取「在册最大」会在条目修好后**回落**，下一轮于是重新分配同一个号（违反 0.5 的
    「编号不复用」）。上界改为历史水位：在册最大与 front-matter 现有上界取较大者。

⚠ UTF-8 无 BOM 写入（台账 0.5 的纪律），不走 PowerShell 编码管道。
"""
import io
import re

ROOT = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/'
PARTS = ['index', 'medium', 'low', 'excluded', 'fixed', 'history', 'verify']


def read(rel):
    return io.open(ROOT + rel, encoding='utf-8').read()


def write(rel, text):
    with io.open(ROOT + rel, 'w', encoding='utf-8', newline='') as f:
        f.write(text)


texts = {p: read('.wwlia-handoff/ledger/%s.md' % p) for p in PARTS}
entry = read('BUG.md')
all_text = '\n'.join(list(texts.values()))

# ⚠ 每册只写一次：同一册有两处声明（`blocks:` 页眉 + 节标题）时，若各自基于**原始文本**
# 改写再写回，后一次会把前一次的改动覆盖掉（history 就这么被写回 144 而真值 145）。
part_edits = {p: texts[p] for p in PARTS}

index_rows = len(re.findall(r'(?m)^\| L-\d+ \|', texts['index']))
detail_blocks = len(re.findall(r'(?m)^#{3,4} L-\d+ ·', all_text))
x_blocks = len(re.findall(r'(?m)^#{3,4} X-\d+ ·', all_text))
b_blocks = len(re.findall(r'(?m)^#{3,4} B-\d+ ·', all_text))

in_book_max = max([int(m) for m in re.findall(r'(?m)^#{3,4} L-(\d+) ·', all_text)] or [0])
prev = re.search(r'(?m)^id_range: L-01\.\.L-(\d+)$', entry)
prev_max = int(prev.group(1)) if prev else 0
max_l = max(in_book_max, prev_max)

print('实际：索引行 %d / 详情块 %d / X 块 %d / B 块 %d / 在册最大 L-%d / 历史水位 L-%d'
      % (index_rows, detail_blocks, x_blocks, b_blocks, in_book_max, max_l))

# 各册自己的节标题 / 页眉（`blocks:` 与「（N 条」两种声明各自独立核对）
for part in ('medium', 'low'):
    own = len(re.findall(r'(?m)^#{3,4} L-\d+ ·', part_edits[part]))
    part_edits[part] = re.sub(
        r'(?m)^(## \d+\. (?:中风险|低风险)（)\d+( 条)', r'\g<1>%d\g<2>' % own, part_edits[part])
    print('%s：节标题 → %d' % (part, own))

for part, blocks in (
        ('excluded', x_blocks),
        ('history', b_blocks),
):
    part_edits[part] = re.sub(r'(?m)^(blocks: )\d+(（)', r'\g<1>%d\g<2>' % blocks, part_edits[part])
    print('%s：blocks → %d' % (part, blocks))

part_edits['index'] = re.sub(
    r'(## 1\. 索引（)\d+( 条在册)', r'\g<1>%d\g<2>' % index_rows, part_edits['index'])

part_edits['excluded'] = re.sub(
    r'(## 4\. 已排除（复核判定不是缺陷，共 )\d+( 条)', r'\g<1>%d\g<2>' % x_blocks, part_edits['excluded'])

part_edits['history'] = re.sub(
    r'(## 6\. 附录 B · 历史回执（)\d+( 块)', r'\g<1>%d\g<2>' % b_blocks, part_edits['history'])

for part in PARTS:
    write('.wwlia-handoff/ledger/%s.md' % part, part_edits[part])

# 入口 front-matter
entry = re.sub(r'(?m)^active_entries: \d+$', 'active_entries: %d' % index_rows, entry)
entry = re.sub(r'(?m)^entries_with_detail: \d+$', 'entries_with_detail: %d' % detail_blocks, entry)
entry = re.sub(r'(?m)^excluded_items: \d+$', 'excluded_items: %d' % x_blocks, entry)
entry = re.sub(r'(?m)^history_records: \d+$', 'history_records: %d' % b_blocks, entry)
entry = re.sub(r'(?m)^id_range: L-01\.\.L-\d+$', 'id_range: L-01..L-%d' % max_l, entry)
entry = re.sub(r'最大编号（当前 L-\d+', '最大编号（当前 L-%d' % max_l, entry)
entry = re.sub(r'(## 1\. 索引（)\d+( 条在册)', r'\g<1>%d\g<2>' % index_rows, entry)
write('BUG.md', entry)

print('入口：active_entries=%d entries_with_detail=%d excluded_items=%d history_records=%d id_range=L-01..L-%d'
      % (index_rows, detail_blocks, x_blocks, b_blocks, max_l))
print('入口非空行 =', len([l for l in entry.split('\n') if l.strip()]), '（上限 160）')
