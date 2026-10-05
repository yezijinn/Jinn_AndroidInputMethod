"""把台账各处声明的计数，按各分册的实际块数机械重算并写回。

守卫 `DocsReferenceTest.台账计数须与正文一致` 跨「入口 + 七个分册」对拍这些数字：
索引行数 / 详情块数 / X 块数 / B 块数，以及 front-matter 的四个字段与 `0.5` 的最大编号。
手改容易漏，这里统一按实际内容重算。

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

index_rows = len(re.findall(r'(?m)^\| L-\d+ \|', texts['index']))
detail_blocks = len(re.findall(r'(?m)^#{3,4} L-\d+ ·', all_text))
x_blocks = len(re.findall(r'(?m)^#{3,4} X-\d+ ·', all_text))
b_blocks = len(re.findall(r'(?m)^#{3,4} B-\d+ ·', all_text))
max_l = max([int(m) for m in re.findall(r'(?m)^#{3,4} L-(\d+) ·', all_text)] or [0])

print('实际：索引行 %d / 详情块 %d / X 块 %d / B 块 %d / 最大编号 L-%d'
      % (index_rows, detail_blocks, x_blocks, b_blocks, max_l))

# 分册页眉 / 节标题（`blocks:` 与「低风险（N 条」两种声明各自独立核对）
for part, blocks in (
        ('excluded', x_blocks),
        ('history', b_blocks),
):
    t = texts[part]
    t = re.sub(r'(?m)^(blocks: )\d+(（)', r'\g<1>%d\g<2>' % blocks, t)
    write('.wwlia-handoff/ledger/%s.md' % part, t)
    print('%s：blocks → %d' % (part, blocks))

t = texts['excluded']
t = re.sub(r'(## 4\. 已排除（复核判定不是缺陷，共 )\d+( 条)', r'\g<1>%d\g<2>' % x_blocks, t)
write('.wwlia-handoff/ledger/excluded.md', t)

t = texts['history']
t = re.sub(r'(## 6\. 附录 B · 历史回执（)\d+( 块)', r'\g<1>%d\g<2>' % b_blocks, t)
write('.wwlia-handoff/ledger/history.md', t)

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
