# -*- coding: utf-8 -*-
"""更新日志的滚动维护：把明细里过期的轮次并入索引、正文补进归档，并归位误追加到索引区之后的节。

用法（仓库根执行）：
    python tools/changelog_rollup.py            # 只报告，不写盘
    python tools/changelog_rollup.py --apply    # 执行
    python tools/changelog_rollup.py --as-of 2026-10-10 [--index-days 7]

约定（与 更新日志.md 文件头一致）：
  · 明细区只留 `--as-of` 当天（默认今天）的轮次，一轮一节、正文完整；
  · 索引区只留最近 `--index-days` 天（默认 7）的目录，按日分组；更早的按月写进
    `docs/历史索引-<年-月>.md`，主文件只留一行指向；
  · 每一轮的正文逐字保留在归档文件；
  · 正文一字不改，只做搬移与结构重建。
"""
import collections
import datetime
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding='utf-8')
except AttributeError:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CUR = os.path.join(ROOT, '更新日志.md')
ARCH = os.path.join(ROOT, 'docs', '更新日志-原文-整理前全文.md')
FENCE = '`' * 3


def read(path):
    raw = io.open(path, encoding='utf-8', newline='').read()
    return raw, ('\r\n' if '\r\n' in raw else '\n'), raw.replace('\r\n', '\n').split('\n')


def promote(lines):
    """代码块之外的 `### 日期…` 视作顶层节（归档里存在这种历史写法）。"""
    fence = False
    for i, l in enumerate(lines):
        s = l.lstrip()
        if s.startswith(FENCE):
            fence = not fence
            continue
        if fence:
            continue
        if l.startswith('### ') and re.match(r'\d{4}-\d{2}-\d{2}', l[4:].strip()):
            lines[i] = '## ' + l[4:].strip()
    return lines


def parse_sections(lines, start):
    """从 start 起按 `## ` 切节，返回 [(标题, 起, 止)]。"""
    heads = [i for i in range(start, len(lines)) if lines[i].startswith('## ')]
    out = []
    for k, i in enumerate(heads):
        end = heads[k + 1] if k + 1 < len(heads) else len(lines)
        out.append([lines[i][3:].strip(), i, end])
    return out


def day_of(title):
    m = re.match(r'(\d{4}-\d{2}-\d{2})', title)
    return m.group(1) if m else ''


def check_consistency():
    """四层守恒：归档各日的节数 vs 主文件（明细 + 索引）+ docs 月索引里的条目数。

    目录层做过「同日同题去重」，所以差异只在「归档该日的唯一标题数 == 现存条目数」时才算正常。
    返回 0 = 守恒；1 = 有缺失（缺的日期会列出来）。
    """
    _, _, arch_lines = read(ARCH)
    arch_lines = promote(arch_lines)
    arch_days = collections.OrderedDict()
    for l in arch_lines:
        if l.startswith('## '):
            m = re.match(r'(\d{4}-\d{2}-\d{2})\s*(.*)', l[3:].strip())
            if m:
                arch_days.setdefault(m.group(1), []).append(m.group(2).strip())

    _, _, cur = read(CUR)
    s = next(i for i, l in enumerate(cur) if l.startswith('## 明细'))
    e = next(i for i, l in enumerate(cur) if l.startswith('## 历史索引'))
    live = collections.OrderedDict()

    def add(day, title):
        if day:
            live.setdefault(day, []).append(title)

    for l in cur[s:e]:
        if l.startswith('## '):
            m = re.match(r'(\d{4}-\d{2}-\d{2})\s*(.*)', l[3:].strip())
            if m:
                add(m.group(1), m.group(2).strip())
    day = ''
    for l in cur[e:]:
        m = re.match(r'\*\*(\d{4}-\d{2}-\d{2})\*\*', l)
        if m:
            day = m.group(1)
            live.setdefault(day, [])
        elif l.startswith('- '):
            add(day, l[2:].strip())

    docs = os.path.join(ROOT, 'docs')
    for name in sorted(f for f in os.listdir(docs) if f.startswith('历史索引-') and f.endswith('.md')):
        day = ''
        for l in io.open(os.path.join(docs, name), encoding='utf-8'):
            m = re.match(r'\*\*(\d{4}-\d{2}-\d{2})\*\*', l)
            if m:
                day = m.group(1)
                live.setdefault(day, [])
            elif l.startswith('- '):
                add(day, l[2:].strip())

    missing, merged = [], 0
    for d in sorted(set(list(arch_days) + list(live))):
        a, c = arch_days.get(d, []), live.get(d, [])
        if len(a) == len(c):
            continue
        if len(set(a)) == len(c):
            merged += len(a) - len(c)
            continue
        missing.append((d, len(a), len(c)))

    print('归档 %d 节 / 现存 %d 条；同日重复合并 %d 条' %
          (sum(len(v) for v in arch_days.values()), sum(len(v) for v in live.values()), merged))
    if missing:
        print('缺失（归档有、现存没有）：')
        for d, a, c in missing:
            print('  %s 归档 %d / 现存 %d' % (d, a, c))
        return 1
    print('守恒：每一轮都能在主文件或 docs 里查到。')
    return 0


def main():
    argv = sys.argv[1:]
    if '--check' in argv:
        sys.exit(check_consistency())
    apply = '--apply' in argv
    as_of = datetime.date.today().isoformat()
    if '--as-of' in argv:
        as_of = argv[argv.index('--as-of') + 1]
    index_days = 7
    if '--index-days' in argv:
        index_days = int(argv[argv.index('--index-days') + 1])
    detail_max = 4
    if '--detail-max' in argv:
        detail_max = int(argv[argv.index('--detail-max') + 1])
    cutoff = (datetime.date.fromisoformat(as_of) - datetime.timedelta(days=index_days)).isoformat()

    raw, nl, lines = read(CUR)
    s = next(i for i, l in enumerate(lines) if l.startswith('## 明细'))
    e = next(i for i, l in enumerate(lines) if l.startswith('## 历史索引'))
    head, detail = lines[:s], lines[s:e]
    while detail and detail[-1].strip() == '':
        detail.pop()

    # 索引区里被误追加的顶层节 → 归位到明细
    tail = lines[e + 1:]
    groups = collections.OrderedDict()
    stray = []
    in_stray = False
    cur_day = None
    for l in tail:
        # `### 2026-10` 是索引的月份分组，不是误置的顶层节
        if l.startswith('## ') and not l.startswith('### ') and not l.startswith('## 历史索引'):
            in_stray = True
        if in_stray:
            stray.append(l)
            continue
        m = re.match(r'\*\*(\d{4}-\d{2}-\d{2})\*\*', l)
        if m:
            cur_day = m.group(1)
            groups.setdefault(cur_day, [])
            continue
        if l.startswith('- ') and cur_day:
            groups[cur_day].append(l[2:].strip())
    while stray and stray[0].strip() in ('', '---'):
        stray.pop(0)
    while stray and stray[-1].strip() == '':
        stray.pop()
    detail = detail + ([''] + stray if stray else [])

    # 明细里过期的节 → 并入索引，正文补进归档
    arch_raw, arch_nl, arch_lines = read(ARCH)
    arch_lines = promote(arch_lines)
    arch_titles = {l.strip('# ').strip() for l in arch_lines if l.startswith('## ')}
    arch_sections = parse_sections(arch_lines, 0)

    keep_head, dated = [], []
    for sec in parse_sections(detail, 0):
        # `## 明细（日期）` 是区标题，不参与滚动、也不占明细上限的名额
        (keep_head if not day_of(sec[0]) else dated).append(sec)
    keep, roll_out = [], []
    for sec in dated:
        (roll_out if day_of(sec[0]) < as_of else keep).append(sec)

    # 明细上限：当天记太多时，最早的超额部分也并进索引（正文同样补进归档）
    if detail_max and len(keep) > detail_max:
        extra = len(keep) - detail_max
        roll_out = keep[:extra] + roll_out
        keep = keep[extra:]
    keep = keep_head + keep

    body_new = []
    for sec in keep:
        body_new.extend(detail[sec[1]:sec[2]])
    while body_new and body_new[-1].strip() == '':
        body_new.pop()

    for title, a, b in roll_out:
        d = day_of(title)
        groups.setdefault(d, []).append(re.sub(r'^\d{4}-\d{2}-\d{2}\s*', '', title))

    # 归档必须覆盖明细里的每一节（含当天保留的）：否则下次滚动就会丢正文
    archived = []
    for title, a, b in keep + roll_out:
        if not day_of(title) or title in arch_titles:
            continue
        block = detail[a:b]
        while block and block[-1].strip() == '':
            block.pop()
        arch_raw = arch_raw.rstrip('\n') + arch_nl + arch_nl.join(block) + arch_nl
        arch_titles.add(title)
        archived.append(title)

    rolled = sorted(((day_of(t), t) for t, _, _ in roll_out))
    total = sum(len(v) for v in groups.values())

    print('as-of %s  明细 %d 节 → 保留 %d 节 / 并出 %d 节；索引 %d 条 / %d 组'
          % (as_of, len(keep) + len(roll_out), len(keep), len(roll_out), total, len(groups)))
    for d, t in rolled:
        print('  并出: %s' % t[:80])
    if stray:
        print('  归位: %s' % stray[0][:80])
    for t in archived:
        print('  补归档: %s' % t[:80])

    # 索引窗口：窗外的按月搬到 docs，主文件只留一行指向
    out_window = [(d, t) for d, t in groups.items() if d < cutoff]
    moved = collections.OrderedDict()
    for d, topics in out_window:
        moved.setdefault(d[:7], []).append((d, topics))
    for m, days in moved.items():
        print('  索引外移: docs/历史索引-%s.md（+%d 轮）' % (m, sum(len(t) for _, t in days)))

    if not apply:
        print('\n（dry-run：未写盘。加 --apply 执行）')
        return

    actual = {}
    for m, days in moved.items():
        path = os.path.join(ROOT, 'docs', '历史索引-%s.md' % m)
        merged = collections.OrderedDict()
        if os.path.exists(path):
            cur_d = None
            for l in io.open(path, encoding='utf-8', newline='').read().replace('\r\n', '\n').split('\n'):
                mm = re.match(r'\*\*(\d{4}-\d{2}-\d{2})\*\*', l)
                if mm:
                    cur_d = mm.group(1)
                    merged.setdefault(cur_d, [])
                elif l.startswith('- ') and cur_d:
                    merged[cur_d].append(l[2:].strip())
        for d, topics in days:
            merged.setdefault(d, [])
            for t in topics:
                if t not in merged[d]:
                    merged[d].append(t)
        body = ['# 历史索引 %s（%d 轮）' % (m, sum(len(v) for v in merged.values())),
                '',
                '> 由 `tools/changelog_rollup.py` 生成；各轮正文见 `更新日志-原文-整理前全文.md`。',
                '']
        for d in sorted(merged):
            body.append('**%s**（%d 轮）' % (d, len(merged[d])))
            body.append('')
            for t in merged[d]:
                body.append('- %s' % t)
            body.append('')
        io.open(path, 'w', encoding='utf-8', newline='').write('\n'.join(body).rstrip() + '\n')
        actual[m] = sum(len(v) for v in merged.values())

    if not apply:
        print('\n（dry-run：未写盘。加 --apply 执行）')
        return

    out = []
    out.extend(head)
    out.extend(body_new)
    out.append('')
    out.append('## 历史索引（%s ~ %s）' % (min(groups), max(groups)))
    out.append('')
    out.append('按主题关键字 grep 归档文件即可跳到对应小节。')
    out.append('')
    months = collections.OrderedDict()
    for d, topics in groups.items():
        if d < cutoff:
            continue
        months.setdefault(d[:7], []).append((d, topics))
    for m, days in months.items():
        out.append('### %s（%d 轮）' % (m, sum(len(t) for _, t in days)))
        out.append('')
        for d, topics in days:
            out.append('**%s**（%d 轮）' % (d, len(topics)))
            out.append('')
            for t in topics:
                out.append('- %s' % t)
            out.append('')
    # 指向行按 docs 里现存的文件生成：窗外分组不会重复经过这里，靠文件本身兜住
    docs = os.path.join(ROOT, 'docs')
    for name in sorted(f for f in os.listdir(docs) if f.startswith('历史索引-') and f.endswith('.md')):
        n = sum(1 for l in io.open(os.path.join(docs, name), encoding='utf-8') if l.startswith('- '))
        out.append('更早的目录见 `docs/%s`（%d 轮）。' % (name, n))
        out.append('')
    text = nl.join(out).rstrip() + nl
    io.open(CUR, 'w', encoding='utf-8', newline='').write(text)
    if len(arch_raw) != len(io.open(ARCH, encoding='utf-8', newline='').read()):
        io.open(ARCH, 'w', encoding='utf-8', newline='').write(arch_raw)
    print('\n已写盘：%d 字节 / %d 行' % (len(text.encode('utf-8')), text.count('\n') + 1))


if __name__ == '__main__':
    main()
