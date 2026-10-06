"""渲染键盘音效清单（`keyboard_sounds_manifest.md`）的表体。

四张表都在本文件里写死：`SRC`（编号 ← 原始名 + 来源与授权文案）、`DUR`（时长）、`ON`（起音数）、
`ROLE`（建议角色，人工同步，发布对账不看它）。**不联网** —— 原先 fs 行的 id 与直链按 Freesound
搜索页的返回顺序取，断网时静默写成「id ? · 空直链」而退出码还是 0，而这份清单是仓库里唯一的
来源与授权记录，写空就再也补不回来。

默认只对拍：算出的表与清单里那份逐行比较，不一致打印差异并非零退出，**不写盘**；加 `--write` 才写回。
资产目录、三张表的键、清单里的表头都对不上时一律非零退出（都排在写盘之前）。
"""
import io
import os
import sys

base = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview'
ASSETS = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/app/src/main/assets/sounds/keyboard'
mp = os.path.join(base, 'keyboard_sounds_manifest.md')

HEADER = '| 文件 | 原始名 | 来源 | 时长 | onsets | 建议角色 |'

# kbd_NN <- (原始名, 来源与授权文案)
SRC = [
    ('kbd_01', 'mx2541', 'Mixkit · sfx/2541 · https://assets.mixkit.co/active_storage/sfx/2541/2541-preview.mp3'),
    ('kbd_02', 'mixkit-hard-single-key-press-in-a-laptop-2542',
     'Mixkit · sfx/2542 · https://assets.mixkit.co/active_storage/sfx/2542/2542-preview.mp3'),
    ('kbd_03', 'var_d', '由 `mx2541` 派生的轻量变体（音高/EQ 微调，仍为真实音）'),
    ('kbd_04', '015', 'Freesound CC0 · id 631690 · https://cdn.freesound.org/previews/631/631690_151878-lq.ogg'),
    ('kbd_05', 'var_b', '由 `015` 派生的轻量变体（音高/EQ 微调，仍为真实音）'),
    ('kbd_06', 'maybe_012', 'Freesound CC0 · id 631731 · https://cdn.freesound.org/previews/631/631731_151878-lq.ogg'),
    ('kbd_07', 'var_a', '由 `006` 派生的轻量变体（音高/EQ 微调，仍为真实音）'),
    ('kbd_08', '006', 'Freesound CC0 · id 701113 · https://cdn.freesound.org/previews/701/701113_15173053-lq.ogg'),
    ('kbd_09', 'maybe_014', 'Freesound CC0 · id 752745 · https://cdn.freesound.org/previews/752/752745_14222278-lq.ogg'),
    ('kbd_10', 'maybe_010', 'Freesound CC0 · id 253215 · https://cdn.freesound.org/previews/253/253215_789068-lq.ogg'),
    ('kbd_11', 'var_c', '由 `028` 派生的轻量变体（音高/EQ 微调，仍为真实音）'),
    ('kbd_12', '005', 'Freesound CC0 · id 734201 · https://cdn.freesound.org/previews/734/734201_15961219-lq.ogg'),
    ('kbd_13', '028', 'Freesound CC0 · id 506765 · https://cdn.freesound.org/previews/506/506765_3797507-lq.ogg'),
    ('kbd_14', 'mixkit-single-key-type-2533',
     'Mixkit · sfx/2533 · https://assets.mixkit.co/active_storage/sfx/2533/2533-preview.mp3'),
    ('kbd_15', 'maybe_057', 'Freesound CC0 · id 842480 · https://cdn.freesound.org/previews/842/842480_10196790-lq.ogg'),
    ('kbd_16', 'maybe_003', 'Freesound CC0 · id 491920 · https://cdn.freesound.org/previews/491/491920_10630312-lq.ogg'),
]

DUR = {"kbd_01": 60, "kbd_02": 66, "kbd_03": 67, "kbd_04": 73, "kbd_05": 79, "kbd_06": 109, "kbd_07": 114,
       "kbd_08": 122, "kbd_09": 135, "kbd_10": 138, "kbd_11": 152, "kbd_12": 159, "kbd_13": 171,
       "kbd_14": 183, "kbd_15": 205, "kbd_16": 210}

ON = {"kbd_01": 1, "kbd_02": 1, "kbd_03": 1, "kbd_04": 1, "kbd_05": 1, "kbd_06": 1, "kbd_07": 1,
      "kbd_08": 1, "kbd_09": 1, "kbd_10": 2, "kbd_11": 1, "kbd_12": 1, "kbd_13": 1, "kbd_14": 1,
      "kbd_15": 2, "kbd_16": 2}

# 建议角色：与 `TapSound.DEFAULT_MAP` 人工同步，发布对账不读它（见 TapSoundTest 的说明）
ROLE = {"kbd_10": "六组默认（文字/数字/符号/删除/确认/功能）"}


def rendered_rows():
    out = []
    for key, orig, src in SRC:
        out.append('| `%s.ogg` | `%s` | %s | %dms | %d | %s |'
                   % (key, orig, src, DUR[key], ON[key], ROLE.get(key, '待定')))
    return out


# ── 写盘之前把所有能失败的事做完 ───────────────────────────────────────────
keys = [k for k, _, _ in SRC]
if len(set(keys)) != len(keys) or set(keys) != set(DUR) or set(keys) != set(ON):
    raise SystemExit('表的键对不上：SRC %d 条（去重后 %d），DUR 多 %s，ON 多 %s'
                     % (len(keys), len(set(keys)), sorted(set(DUR) - set(keys)),
                        sorted(set(ON) - set(keys))))
if not set(ROLE) <= set(keys):
    raise SystemExit('角色表的键不在 SRC 里：%s' % sorted(set(ROLE) - set(keys)))

if not os.path.isdir(ASSETS):
    raise SystemExit('资产目录不存在: %s' % ASSETS)
ogg = sorted(f for f in os.listdir(ASSETS) if f.endswith('.ogg'))
want = sorted(k + '.ogg' for k in keys)
if ogg != want:
    raise SystemExit('资产与 SRC 不一致：缺 %s，多 %s'
                     % ([f for f in want if f not in ogg], [f for f in ogg if f not in want]))

if not os.path.isfile(mp):
    raise SystemExit('清单不存在: %s' % mp)
# `newline=''`：换行风格自己判，别让通用换行把 \r\n 归一掉 —— 那样 eol 分支恒取 \n，
# 在 CRLF 检出上会把整份文件改写成 LF。
text = io.open(mp, encoding='utf-8', newline='').read()
eol = '\r\n' if '\r\n' in text else '\n'
lines = text.split(eol)
if HEADER not in lines:
    raise SystemExit('清单里找不到表头（改过版式？）：%s' % HEADER)

head_at = lines.index(HEADER)
if head_at + 1 >= len(lines) or not lines[head_at + 1].startswith('|---'):
    raise SystemExit('表头下面那行不是分隔行（版式变了？）：%r'
                     % (lines[head_at + 1] if head_at + 1 < len(lines) else None))
head = head_at + 2
end = head
while end < len(lines) and lines[end].startswith('| '):
    end += 1
old = lines[head:end]
if not old:
    raise SystemExit('清单表体一行都没读到（表头下面是空的？）')
new = rendered_rows()
same = old == new
print('清单表体 %d 行 / 生成 %d 行：%s' % (len(old), len(new), '一致' if same else '不一致'))
for i in range(max(len(old), len(new))):
    a = old[i] if i < len(old) else '(缺)'
    b = new[i] if i < len(new) else '(缺)'
    if a != b:
        print('  第 %d 行\n    清单: %s\n    生成: %s' % (head + i + 1, a, b))

if '--write' in sys.argv[1:]:
    if same:
        print('清单无变化')
        sys.exit(0)
    lines[head:end] = new
    # 替换后表体后面不该再跟着表格行 —— 表体中间夹了空行时扫描会提前停住、旧行留在原地。
    if head + len(new) < len(lines) and lines[head + len(new)].startswith('| '):
        raise SystemExit('替换后表体后面还跟着表格行（表体中间有空行？）—— 已中止，未写盘')
    out = eol.join(lines)
    tmp = mp + '.tmp.' + str(os.getpid())
    try:
        io.open(tmp, 'w', encoding='utf-8', newline='').write(out)
        os.replace(tmp, mp)
    except Exception as e:
        if os.path.exists(tmp):
            os.remove(tmp)
        raise SystemExit('写回失败：%s' % e)
    if io.open(mp, encoding='utf-8', newline='').read() != out:
        raise SystemExit('写回后重读与内容不一致，请检查磁盘')
    print('清单已写回 ->', mp)
    sys.exit(0)

print('资产 %d 个 ogg / %d KB；要写回加 --write'
      % (len(ogg), sum(os.path.getsize(os.path.join(ASSETS, f)) for f in ogg) // 1024))
sys.exit(0 if same else 1)
