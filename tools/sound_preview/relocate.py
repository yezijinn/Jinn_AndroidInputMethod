import os, shutil, sys, wave, numpy as np

base = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview'
RK = os.path.join(base, 'real_keyboard')
ASSETS = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/app/src/main/assets/sounds/keyboard'
MASTERS = os.path.join(base, 'masters')
SR = 44100

# ---- 1) dedupe: drop mx2542 (same recording as mixkit-hard-...-2542) ----
for ext in ('.wav', '.ogg'):
    p = os.path.join(RK, 'mx2542' + ext)
    if os.path.exists(p): os.remove(p)

# 往下会把 assets/ 与 masters/ 清空后重编号，而清空是无条件的、回填却是「有同名 ogg 才拷」——
# 能失败的事一律排在清空之前：输入目录要有，素材要成对，还得显式给 --force。输入目录早被上一次
# 运行删掉（脚本末尾 rmtree），空跑只会毁掉已入库的 16 个 ogg 与本地母版，且退 0 不留痕。
if not os.path.isdir(RK):
    raise SystemExit('输入目录不存在: %s（先把素材放进去再跑）' % RK)
ogg_names = {f for f in os.listdir(RK) if f.endswith('.ogg')}
lonely = sorted(f for f in os.listdir(RK) if f.endswith('.wav')
                and os.path.splitext(f)[0] + '.ogg' not in ogg_names)
if lonely:
    raise SystemExit('这些 wav 没有同名 ogg，清空之后补不回来，先补齐：%s' % lonely)
if '--force' not in sys.argv[1:]:
    raise SystemExit('要清空 assets/ 与 masters/ 再重编号，加 --force 明确一次')
for d in (ASSETS, MASTERS):
    os.makedirs(d, exist_ok=True)
    for f in os.listdir(d): os.remove(os.path.join(d, f))

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

# ---- 2) 来源文案 ----
# 原先这里按 Freesound 搜索页的返回顺序重建 id→直链，只为打印时看着详细；那些 id 与直链的真正
# 归属已经写死在 gen_manifest.py 的 SRC 表里，抓取对输出没有任何影响 —— 离线时还要按三十秒
# 超时白等十来分钟，干脆去掉。
VARBASE = {'var_a': '006', 'var_b': '015', 'var_c': '028', 'var_d': 'mx2541'}


def source_of(name):
    n = os.path.splitext(name)[0]
    if n in VARBASE:
        return '由 %s 派生(真实音轻量变体)' % VARBASE[n]
    if n.startswith('mx') or n.startswith('mixkit-'):
        return 'Mixkit'
    if n.isdigit():
        return 'Freesound (CC0)'
    return 'unknown'

# ---- 3) sort by duration asc, assign kbd_NN ----
wavs = [f for f in os.listdir(RK) if f.endswith('.wav')]
info = []
for f in wavs:
    d = load_wav(os.path.join(RK, f)); dur = len(d)/SR
    info.append((dur, f, onset(d)))
info.sort()
print(f"\nfiles to place: {len(info)}", flush=True)

for i, (dur, f, on) in enumerate(info, 1):
    key = f"kbd_{i:02d}"
    shutil.copy(os.path.join(RK, f), os.path.join(MASTERS, key + '.wav'))
    ogg = os.path.join(RK, os.path.splitext(f)[0] + '.ogg')
    if os.path.exists(ogg): shutil.copy(ogg, os.path.join(ASSETS, key + '.ogg'))
    print(f"  {key}.ogg  <- {f:48s} {dur*1000:5.0f}ms on={on}  {source_of(f)}", flush=True)

# ---- 4) manifest ----
# 清单的表体由 gen_manifest.py 渲染（它持有编号 / 时长 / 起音数 / 来源四张表），这里不再维护第二份模板 ——
# 两处模板必然漂移，上回就是（旧模板少了「不入库」那句，重跑一次会把它从清单里抹掉）。
print("\n素材已落位；渲染清单: python tools/sound_preview/gen_manifest.py --write")

# ---- 5) remove relocated working dir ----
shutil.rmtree(RK)
print("removed working dir:", RK)
