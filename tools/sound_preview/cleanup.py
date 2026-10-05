import os, shutil

BASE = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview'

# 保留：可复现的管线脚本（下载→筛选→汇总→补变体→落位→清单）与产物目录
KEEP_PY = {
    'get_real.py', 'get_mixkit.py', 'consolidate.py',
    'add_to_20.py', 'relocate.py', 'gen_manifest.py',
    # 通用校验助手（后面几轮还会用）
    'show_failures.py', 'count_tests.py', 'check_apk_assets.py',
    'cleanup.py',
}
KEEP_DIRS = {'masters', 'my', '__pycache__'}

# 一次性诊断 / 探测脚本
DEL_FILES = [
    'analyze_my.py', 'analyze_selected.py', 'analyze_trimmed.py',
    'check05.py', 'diag.py', 'diag2.py', 'diag3.py',
    'gen_20.py', 'gen_mech.py', 'grab.py', 'nettest.py',
    'probe_mixkit.py', 'probe_pixabay.py', 'process_trim.py',
    'remake_from_my.py', 'remake2.py', 'del_old.py', 'del_metal.py',
    '_peek.txt', 'peek_ledger.py', 'peek_idx.py', 'peek_counts.py', 'tmp.wav',
]
# 中间产物目录（都可由管线重新生成）
DEL_DIRS = [
    'real_dl', 'real_dl_mixkit', 'selected_20', 'selected_real',
    'selected_real_maybe', 'selected_mixkit', 'selected_mixkit_maybe',
]

removed, missing = [], []
for name in DEL_FILES:
    p = os.path.join(BASE, name)
    if os.path.isfile(p):
        os.remove(p); removed.append(name)
    else:
        missing.append(name)

for name in DEL_DIRS:
    p = os.path.join(BASE, name)
    if os.path.isdir(p):
        shutil.rmtree(p); removed.append(name + '/')
    else:
        missing.append(name + '/')

print('REMOVED (%d):' % len(removed))
for r in sorted(removed):
    print('  -', r)
if missing:
    print('NOT FOUND (%d): %s' % (len(missing), ', '.join(missing)))

print('\nREMAINING:')
for name in sorted(os.listdir(BASE)):
    p = os.path.join(BASE, name)
    if os.path.isdir(p):
        n = len(os.listdir(p))
        print('  [dir ] %-24s %d files%s' % (name, n, '  (KEEP)' if name in KEEP_DIRS else ''))
    else:
        print('  [file] %-24s %s' % (name, 'KEEP' if name in KEEP_PY else 'ORPHAN'))
