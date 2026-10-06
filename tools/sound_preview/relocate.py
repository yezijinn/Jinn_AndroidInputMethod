import os, shutil, sys, wave, numpy as np, urllib.request, re, time

base = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview'
RK = os.path.join(base, 'real_keyboard')
ASSETS = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/app/src/main/assets/sounds/keyboard'
MASTERS = os.path.join(base, 'masters')
SR = 44100

# 下面这一步会把 assets/ 与 masters/ 清空后重编号，而它要求输入目录真的存在。
# 输入目录早被上一次运行删掉（脚本末尾 rmtree），空跑只会毁掉已入库的 16 个 ogg 与本地母版。
if not os.path.isdir(RK):
    raise SystemExit('输入目录不存在: %s（先把素材放进去再跑）' % RK)
if '--force' not in sys.argv:
    raise SystemExit('要清空 assets/ 与 masters/ 再重编号，加 --force 明确一次')
for d in (ASSETS, MASTERS):
    os.makedirs(d, exist_ok=True)
    for f in os.listdir(d): os.remove(os.path.join(d, f))

# ---- 1) dedupe: drop mx2542 (same recording as mixkit-hard-...-2542) ----
for ext in ('.wav', '.ogg'):
    p = os.path.join(RK, 'mx2542' + ext)
    if os.path.exists(p): os.remove(p)

def load_wav(path):
    w = wave.open(path, 'rb'); n = w.getnframes(); ch = w.getnchannels(); sw = w.getsampwidth()
    raw = w.readframes(n); w.close()
    if sw == 2: d = np.frombuffer(raw, dtype=np.int16).astype(float)/32768
    elif sw == 3:
        a = np.frombuffer(raw, dtype=np.uint8).reshape(-1,3)
        iii = a[:,0].astype(np.int32)|(a[:,1].astype(np.int32)<<8)|(a[:,2].astype(np.int32)<<16)
        iii = np.where(iii>=2**23, iii-2**24, iii); d = iii.astype(float)/(2**23)
    else: return None
    if ch > 1: d = d.reshape(-1, ch).mean(1)
    return d

def onset(d, th=0.20, min_gap=0.05):
    win = int(0.005*SR)
    e = np.array([np.sqrt(np.mean(d[i:i+win]**2)) for i in range(0, len(d)-win, win)]); e = e/np.max(e)
    above = e > th; segs = []; ins = False
    for i, a in enumerate(above):
        if a and not ins: segs.append([i]); ins = True
        elif not a and ins: segs[-1].append(i); ins = False
    if ins: segs[-1].append(len(above))
    m = []
    for s in segs:
        if m and (s[0]-m[-1][-1])*0.005 < min_gap: m[-1][-1] = s[0]
        else: m.append(list(s))
    return len(m)

# ---- 2) rebuild Freesound index->url map (same order as get_real.py) ----
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
            print('fetch err', q, page, e, flush=True); continue
        mp3s = re.findall(r'data-mp3="([^"]+)"', html)
        oggs = re.findall(r'data-ogg="([^"]+)"', html)
        for m,o in zip(mp3s, oggs): seen[o if o else m] = True
        time.sleep(0.15)
    print(f"  mapped query {q} -> {len(seen)}", flush=True)
urls = list(seen)[:60]
idx2url = {i:u for i,u in enumerate(urls)}

VARBASE = {'var_a':'006','var_b':'015','var_c':'028','var_d':'mx2541'}

def source_of(name):
    n = os.path.splitext(name)[0]
    if n in VARBASE:
        return f"由 {VARBASE[n]} 派生(真实音轻量变体)", ''
    if n.startswith('mx'):
        sid = n[2:]
        return "Mixkit", f"https://assets.mixkit.co/active_storage/sfx/{sid}/{sid}-preview.mp3"
    if n.startswith('mixkit-'):
        sid = re.search(r'(\d+)$', n).group(1)
        return "Mixkit", f"https://assets.mixkit.co/active_storage/sfx/{sid}/{sid}-preview.mp3"
    if n.isdigit():
        u = idx2url.get(int(n), '')
        sid = re.search(r'/(\d+)_\d+-', u)
        return ("Freesound (CC0)", u) if u else ("Freesound (CC0)", '')
    return "unknown", ''

# ---- 3) sort by duration asc, assign kbd_NN ----
wavs = [f for f in os.listdir(RK) if f.endswith('.wav')]
info = []
for f in wavs:
    d = load_wav(os.path.join(RK, f)); dur = len(d)/SR
    info.append((dur, f, onset(d)))
info.sort()
print(f"\nfiles to place: {len(info)}", flush=True)

rows = []
for i, (dur, f, on) in enumerate(info, 1):
    key = f"kbd_{i:02d}"
    src, url = source_of(f)
    shutil.copy(os.path.join(RK, f), os.path.join(MASTERS, key + '.wav'))
    ogg = os.path.join(RK, os.path.splitext(f)[0] + '.ogg')
    if os.path.exists(ogg): shutil.copy(ogg, os.path.join(ASSETS, key + '.ogg'))
    role = "待定"   # 建议角色由 gen_manifest.py 的表给出，这里只落素材
    rows.append((key, f, src, url, dur*1000, on, role))
    print(f"  {key}.ogg  <- {f:48s} {dur*1000:5.0f}ms on={on}  {src}", flush=True)

# ---- 4) manifest ----
# 清单的表体由 gen_manifest.py 渲染（它持有编号 / 时长 / 起音数 / 来源四张表），这里不再维护第二份模板 ——
# 两处模板必然漂移，上回就是（旧模板少了「不入库」那句，重跑一次会把它从清单里抹掉）。
print("\n素材已落位；渲染清单: python tools/sound_preview/gen_manifest.py --write")

# ---- 5) remove relocated working dir ----
shutil.rmtree(RK)
print("removed working dir:", RK)
