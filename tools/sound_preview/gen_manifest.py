import os, re, time, urllib.request

base = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview'

# kbd_NN <- (original name, kind, extra)  kind: 'fs' idx | 'mixkit' id | 'var' base
MAP = [
    ("kbd_01", "mx2541", "mixkit", "2541"),
    ("kbd_02", "mixkit-hard-single-key-press-in-a-laptop-2542", "mixkit", "2542"),
    ("kbd_03", "var_d", "var", "mx2541"),
    ("kbd_04", "015", "fs", 15),
    ("kbd_05", "var_b", "var", "015"),
    ("kbd_06", "maybe_012", "fs", 12),
    ("kbd_07", "var_a", "var", "006"),
    ("kbd_08", "006", "fs", 6),
    ("kbd_09", "maybe_014", "fs", 14),
    ("kbd_10", "maybe_010", "fs", 10),
    ("kbd_11", "var_c", "var", "028"),
    ("kbd_12", "005", "fs", 5),
    ("kbd_13", "028", "fs", 28),
    ("kbd_14", "mixkit-single-key-type-2533", "mixkit", "2533"),
    ("kbd_15", "maybe_057", "fs", 57),
    ("kbd_16", "maybe_003", "fs", 3),
]
DUR = {"kbd_01":60,"kbd_02":66,"kbd_03":67,"kbd_04":73,"kbd_05":79,"kbd_06":109,"kbd_07":114,
       "kbd_08":122,"kbd_09":135,"kbd_10":138,"kbd_11":152,"kbd_12":159,"kbd_13":171,
       "kbd_14":183,"kbd_15":205,"kbd_16":210}
ON = {"kbd_01":1,"kbd_02":1,"kbd_03":1,"kbd_04":1,"kbd_05":1,"kbd_06":1,"kbd_07":1,
      "kbd_08":1,"kbd_09":1,"kbd_10":2,"kbd_11":1,"kbd_12":1,"kbd_13":1,"kbd_14":1,
      "kbd_15":2,"kbd_16":2}

# rebuild freesound index -> preview url
queries = ["keyboard+single+key","mechanical+keyboard+keystroke","single+key+press",
           "key+press+mechanical","computer+key+click","mechanical+switch+click","keystroke+single"]
seen = {}
for q in queries:
    for page in range(1, 4):
        url = f"https://freesound.org/search/?q={q}&f=license+%22CC0+1.0%22&f=duration:[0+TO+1]&s=downloads+desc&advanced=1&g=1&page={page}"
        try:
            req = urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0'})
            html = urllib.request.urlopen(req, timeout=30).read().decode('utf-8','ignore')
        except Exception as e:
            print('fetch err', q, page, e); continue
        mp3s = re.findall(r'data-mp3="([^"]+)"', html)
        oggs = re.findall(r'data-ogg="([^"]+)"', html)
        for m,o in zip(mp3s, oggs): seen[o if o else m] = True
        time.sleep(0.15)
urls = list(seen)[:60]
idx2url = {i:u for i,u in enumerate(urls)}

mp = os.path.join(base, 'keyboard_sounds_manifest.md')
with open(mp, 'w', encoding='utf-8') as fp:
    fp.write("# 键盘音效清单 (assets/sounds/keyboard/)\n\n")
    fp.write("- 命名：`kbd_NN.ogg`（中性序号，按时长升序）。角色→音效映射放设置/代码，不写进文件名。\n")
    fp.write("- OGG 进 APK（约 4–5 KB/个，aapt 默认不二次压缩）；WAV 母版在 `tools/sound_preview/masters/`。\n")
    fp.write("- 全部为**真实键盘录音**：Freesound 条目为 CC0；Mixkit 条目为 Mixkit 免费音效（免费商用、无需署名）。\n")
    fp.write("- `onsets` 为起音数（1=纯单音，2=极短的 click+触底双瞬态，仍在同一次按键内）。\n\n")
    fp.write("| 文件 | 原始名 | 来源 | 时长 | onsets | 建议角色 |\n|---|---|---|---|---|---|\n")
    for key, orig, kind, extra in MAP:
        if kind == 'fs':
            u = idx2url.get(extra, '')
            sid = (re.search(r'/(\d+)_\d+-', u) or [None,'?'])[1] if u else '?'
            src = f"Freesound CC0 · id {sid} · {u}"
        elif kind == 'mixkit':
            src = f"Mixkit · sfx/{extra} · https://assets.mixkit.co/active_storage/sfx/{extra}/{extra}-preview.mp3"
        else:
            src = f"由 `{extra}` 派生的轻量变体（音高/EQ 微调，仍为真实音）"
        role = "六组默认（文字/数字/符号/删除/确认/功能）" if key == "kbd_10" else "待定"
        fp.write(f"| `{key}.ogg` | `{orig}` | {src} | {DUR[key]}ms | {ON[key]} | {role} |\n")
    fp.write("\n> 注：默认映射见 `TapSound.DEFAULT_MAP`（六组统一用 kbd_10）。\n")

print("manifest written ->", mp)
# verify placement
A = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/app/src/main/assets/sounds/keyboard'
M = os.path.join(base, 'masters')
ao = sorted(f for f in os.listdir(A) if f.endswith('.ogg'))
mw = sorted(f for f in os.listdir(M) if f.endswith('.wav'))
print("assets ogg:", len(ao), ao[:3], "...", ao[-1] if ao else '')
print("masters wav:", len(mw))
print("bytes in APK:", sum(os.path.getsize(os.path.join(A,f)) for f in ao), "~", round(sum(os.path.getsize(os.path.join(A,f)) for f in ao)/1024,1), "KB")
