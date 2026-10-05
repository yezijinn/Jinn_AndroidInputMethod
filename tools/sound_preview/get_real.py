import urllib.request, re, os, wave, subprocess, time, numpy as np

SR = 44100
DL = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview/real_dl'
OUT = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview/selected_real'
os.makedirs(DL, exist_ok=True); os.makedirs(OUT, exist_ok=True)

UA = {'User-Agent':'Mozilla/5.0 (Windows NT 10.0; Win64; x64)'}
def fetch(url):
    req = urllib.request.Request(url, headers=UA)
    return urllib.request.urlopen(req, timeout=30).read().decode('utf-8','ignore')

# 1) gather real preview URLs (CC0 single-key queries), a few pages each
queries = [
    "keyboard+single+key",
    "mechanical+keyboard+keystroke",
    "single+key+press",
    "key+press+mechanical",
    "computer+key+click",
    "mechanical+switch+click",
    "keystroke+single",
]
seen = {}
for q in queries:
    for page in range(1, 4):  # 3 pages per query
        url = f"https://freesound.org/search/?q={q}&f=license+%22CC0+1.0%22&f=duration:[0+TO+1]&s=downloads+desc&advanced=1&g=1&page={page}"
        try:
            html = fetch(url)
        except Exception as e:
            print("fetch err", q, page, e, flush=True); continue
        mp3s = re.findall(r'data-mp3="([^"]+)"', html)
        oggs = re.findall(r'data-ogg="([^"]+)"', html)
        for m,o in zip(mp3s, oggs):
            seen[o if o else m] = True
        time.sleep(0.15)
    print(f"  query[{q}] -> {len(seen)} urls so far", flush=True)

urls = list(seen)[:60]   # cap downloads
print(f"collected {len(urls)} preview urls (capped 60)", flush=True)

# 2) download
def dl(u, dst):
    try:
        req = urllib.request.Request(u, headers=UA)
        data = urllib.request.urlopen(req, timeout=30).read()
        open(dst,'wb').write(data)
        return len(data)
    except Exception as e:
        return -1

names = []
for i,u in enumerate(urls):
    ext = 'ogg' if u.endswith('.ogg') else 'mp3'
    dst = os.path.join(DL, f"{i:03d}.{ext}")
    if os.path.exists(dst) and os.path.getsize(dst) > 2000:
        names.append(dst); continue   # reuse
    sz = dl(u, dst)
    if sz > 2000:
        names.append(dst)
        print(f"  dl {i:03d} {sz}B OK", flush=True)
    else:
        if os.path.exists(dst): os.remove(dst)
        print(f"  dl {i:03d} FAIL", flush=True)
    time.sleep(0.1)
print(f"downloaded {len(names)} files", flush=True)

# 3) process: decode -> trim+normalize -> single-onset check -> keep
def decode_to_wav(src, dstwav):
    r = subprocess.run(['ffmpeg','-y','-i',src,'-ar',str(SR),'-ac','1',dstwav],
                       capture_output=True)
    return r.returncode == 0

def load_wav(path):
    w = wave.open(path,'rb'); n=w.getnframes(); ch=w.getnchannels(); sw=w.getsampwidth()
    raw=w.readframes(n); w.close()
    if sw==2: d=np.frombuffer(raw,dtype=np.int16).astype(float)/32768
    elif sw==3:
        a=np.frombuffer(raw,dtype=np.uint8).reshape(-1,3)
        iii=a[:,0].astype(np.int32)|(a[:,1].astype(np.int32)<<8)|(a[:,2].astype(np.int32)<<16)
        iii=np.where(iii>=2**23,iii-2**24,iii); d=iii.astype(float)/(2**23)
    else: return None
    if ch>1: d=d.reshape(-1,ch).mean(1)
    return d

def write_wav(path, sig):
    pcm=(np.clip(sig,-1,1)*32767).astype(np.int16)
    w=wave.open(path,'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
    w.writeframes(pcm.tobytes()); w.close()

def trim_norm(sig, target=0.85):
    peak=np.max(np.abs(sig))
    if peak<=0: return sig
    thr=max(peak*0.02,1e-4)
    above=np.abs(sig)>thr; idx=np.where(above)[0]
    if len(idx)==0: return sig
    i0=max(0,idx[0]-int(0.002*SR)); i1=min(len(sig)-1,idx[-1]+int(0.002*SR))
    seg=sig[i0:i1+1]
    return seg/np.max(np.abs(seg))*target

def onset_count(d, th=0.20, min_gap=0.05):
    win=int(0.005*SR)
    e=np.array([np.sqrt(np.mean(d[i:i+win]**2)) for i in range(0,len(d)-win,win)])
    e=e/np.max(e); above=e>th; segs=[]; inseg=False
    for i,a in enumerate(above):
        if a and not inseg: segs.append([i]); inseg=True
        elif not a and inseg: inseg=False
    m=[]
    for s in segs:
        if m and (s[0]-m[-1][-1])*0.005<min_gap: m[-1][-1]=s[0]
        else: m.append(list(s))
    return len(m)

MAYBE = 'c:/AI_WORKSPACE/PROJECTS/com.jinn.inputmethod/tools/sound_preview/selected_real_maybe'
for d in (OUT, MAYBE):
    os.makedirs(d, exist_ok=True)
    for f in os.listdir(d): os.remove(os.path.join(d, f))
print("\n=== PROCESS ===", flush=True)
kept=0; kept_maybe=0
for src in names:
    base=os.path.splitext(os.path.basename(src))[0]
    wavtmp=os.path.join(DL,base+'.wav')
    if not decode_to_wav(src,wavtmp): continue
    d=load_wav(wavtmp)
    if d is None: continue
    dur=len(d)/SR
    on=onset_count(d)
    good_single = (on==1 and 0.04<=dur<=0.8)
    maybe_single = (on==2 and dur<=0.45)   # short double-transient (click+body) -> candidate
    if not (good_single or maybe_single):
        print(f"  SKIP {base} on={on} dur={dur*1000:.0f}ms", flush=True)
        os.remove(wavtmp); continue
    seg=trim_norm(d,0.85)
    outwav=os.path.join(OUT if good_single else MAYBE, base+'.wav')
    write_wav(outwav, seg)
    outogg=os.path.join(OUT if good_single else MAYBE, base+'.ogg')
    subprocess.run(['ffmpeg','-y','-i',outwav,outogg], capture_output=True)
    os.remove(wavtmp)
    if good_single:
        kept+=1; tag="KEEP"
    else:
        kept_maybe+=1; tag="MAYBE"
    print(f"  {tag} {base} on={on} dur={len(seg)/SR*1000:.0f}ms peak={np.max(np.abs(seg)):.2f}", flush=True)

print(f"\nSTRICT single: {kept}  -> {OUT}")
print(f"MAYBE (short 2-transient): {kept_maybe}  -> {MAYBE}")
